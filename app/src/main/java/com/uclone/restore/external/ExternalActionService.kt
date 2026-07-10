package com.uclone.restore.external

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.uclone.restore.UCloneApplication
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ExternalActionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleLock = Any()
    private val lifecyclePolicy = ExternalServiceLifecyclePolicy()
    private var foregroundActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(lifecycleLock) {
            lifecyclePolicy.onStart(startId)
            if (!foregroundActive) {
                val notifier = ExternalActionNotifier(this)
                startForeground(
                    NOTIFICATION_ID,
                    notifier.running(packageName = null, operation = null, message = "正在验证请求"),
                )
                foregroundActive = true
            }
        }
        val container = (application as UCloneApplication).container
        if (intent?.action != ExternalActionContract.ACTION_EXECUTE) {
            finishRejectedStart(startId)
            return START_NOT_STICKY
        }
        val request = ExternalActionRequest.from(intent)
        if (request == null) {
            reject(intent, ExternalActionContract.STATUS_REJECTED, "请求参数无效")
            finishRejectedStart(startId)
            return START_NOT_STICKY
        }
        val settings = container.settingsStore.load()
        ExternalRequestPolicy.rejection(this, request, settings, container.internalRequestToken)?.let { reason ->
            reject(request, ExternalActionContract.STATUS_REJECTED, reason)
            finishRejectedStart(startId)
            return START_NOT_STICKY
        }
        return when (val admission = admitExternalTask(container.taskCoordinator, request)) {
            is ExternalTaskAdmission.Rejected -> {
                reject(request, admission.status, admission.message)
                finishRejectedStart(startId)
                START_NOT_STICKY
            }
            is ExternalTaskAdmission.Accepted -> {
                startAcceptedTask(request, settings, startId)
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAcceptedTask(
        request: ExternalActionRequest,
        settings: com.uclone.restore.model.UCloneSettings,
        startId: Int,
    ) {
        val container = (application as UCloneApplication).container
        val notifier = ExternalActionNotifier(this)
        try {
            synchronized(lifecycleLock) {
                lifecyclePolicy.onAccepted(startId)
                notifier.clearResult()
                startForeground(
                    NOTIFICATION_ID,
                    notifier.running(request.packageName, request.operation, "任务已接收"),
                )
                foregroundActive = true
            }
        } catch (error: Exception) {
            val message = error.message ?: "无法启动任务服务"
            try {
                container.taskCoordinator.failAndComplete(request.requestId, message)
                reject(request, ExternalActionContract.STATUS_FAILED, message)
            } finally {
                finishAcceptedStart(startId, notifier)
            }
            return
        }
        scope.launch {
            try {
                executeAccepted(request, settings, notifier)
            } finally {
                try {
                    container.taskCoordinator.complete(request.requestId)
                } finally {
                    finishAcceptedStart(startId, notifier)
                }
            }
        }
    }

    private fun finishRejectedStart(startId: Int) {
        synchronized(lifecycleLock) {
            val finalization = lifecyclePolicy.onRejected(startId)
            val notifier = ExternalActionNotifier(this).takeIf { finalization.removeForeground }
            applyFinalization(finalization, notifier)
        }
    }

    private fun finishAcceptedStart(startId: Int, notifier: ExternalActionNotifier) {
        synchronized(lifecycleLock) {
            applyFinalization(lifecyclePolicy.onAcceptedFinished(startId), notifier)
        }
    }

    private fun applyFinalization(
        finalization: ExternalServiceFinalization,
        notifier: ExternalActionNotifier?,
    ) {
        if (finalization.removeForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            requireNotNull(notifier).clearRunning()
            foregroundActive = false
        }
        finalization.stopStartId?.let(::stopSelfResult)
    }

    private suspend fun executeAccepted(
        request: ExternalActionRequest,
        settings: com.uclone.restore.model.UCloneSettings,
        notifier: ExternalActionNotifier,
    ) {
        val container = (application as UCloneApplication).container
        broadcastStatus(request, ExternalActionContract.STATUS_ACCEPTED, "任务已接收")
        notifier.notifyAccepted(request.packageName, request.operation, "任务已接收")
        Log.i(TAG, "accepted operation=${request.operation} package=${request.packageName} request=${request.requestId}")
        val report: (TaskProgress) -> Unit = { progress ->
            container.taskRepository.publish(progress)
            val update = progress.toRunningNotificationUpdate()
            notifier.updateRunning(
                request.packageName,
                request.operation,
                update.message,
                update.stageKey,
                update.isTerminal,
            )
        }
        try {
            finishSuccess(request, ExternalActionDispatcher(container).execute(request, settings, report), notifier)
        } catch (cancelled: CancellationException) {
            val message = "任务已中断"
            val interrupted = container.taskCoordinator.interrupt(request.requestId, message)
            broadcastStatus(request, ExternalActionContract.STATUS_FAILED, message, interrupted)
            notifier.notifyResult(request.packageName, request.operation, ExternalActionContract.STATUS_FAILED, message)
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "任务执行失败"
            val failed = container.taskCoordinator.fail(request.requestId, message)
            Log.e(TAG, "failed operation=${request.operation} package=${request.packageName}", error)
            broadcastStatus(request, ExternalActionContract.STATUS_FAILED, message, failed)
            notifier.notifyResult(request.packageName, request.operation, ExternalActionContract.STATUS_FAILED, message)
        }
    }

    private fun finishSuccess(
        request: ExternalActionRequest,
        record: TaskRecord,
        notifier: ExternalActionNotifier,
    ) {
        val status = record.status.toExternalStatus()
        Log.i(TAG, "finished status=$status operation=${request.operation} package=${request.packageName} message=${record.message}")
        broadcastStatus(request, status, record.message, record)
        notifier.notifyResult(request.packageName, request.operation, status, record.message)
        if (record.status.isSuccessful && request.operation in OPERATIONS_CLEARING_HISTORY) {
            (application as UCloneApplication).container.taskRepository.clearHistoryPreservingProgress()
        }
    }

    private fun reject(request: ExternalActionRequest, status: String, message: String) {
        broadcastStatus(request, status, message)
        ExternalActionNotifier(this).notifyRejected(request.packageName, request.operation, status, message)
    }

    private fun reject(intent: Intent, status: String, message: String) {
        broadcastStatus(intent, status, message)
        ExternalActionNotifier(this).notifyRejected(
            intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME),
            intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION),
            status,
            message,
        )
    }

    private fun broadcastStatus(request: ExternalActionRequest, status: String, message: String, task: TaskRecord? = null) {
        if (request.source == ExternalActionContract.SOURCE_APP) return
        broadcastStatus(request.toIntent(), status, message, task)
    }

    private fun broadcastStatus(intent: Intent, status: String, message: String, task: TaskRecord? = null) {
        val source = intent.getStringExtra(ExternalActionContract.EXTRA_SOURCE).orEmpty()
        if (source == ExternalActionContract.SOURCE_APP) return
        val statusIntent = Intent(ExternalActionContract.ACTION_STATUS)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
            .putExtra(ExternalActionContract.EXTRA_OPERATION, intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, intent.getStringExtra(ExternalActionContract.EXTRA_REQUEST_ID).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_SOURCE, source)
            .putExtra(ExternalActionContract.EXTRA_STATUS, status)
            .putExtra(ExternalActionContract.EXTRA_MESSAGE, message)
        task?.let { statusIntent.putExtra(ExternalActionContract.EXTRA_TASK_TYPE, it.type.name) }
        if (source == ExternalActionContract.SOURCE_MODULE || source == ExternalActionContract.SOURCE_LAUNCHER_MODULE) {
            statusIntent.component = ComponentName(
                ExternalActionContract.LAUNCHER_MODULE_PACKAGE,
                ExternalActionContract.LAUNCHER_MODULE_STATUS_RECEIVER,
            )
        }
        sendBroadcast(statusIntent, ExternalActionContract.PERMISSION_CONTROL)
    }

    private fun ExternalActionRequest.toIntent(): Intent = Intent()
        .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
        .putExtra(ExternalActionContract.EXTRA_OPERATION, operation)
        .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, packageName)
        .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, requestId)
        .putExtra(ExternalActionContract.EXTRA_SOURCE, source)
        .putExtra(ExternalActionContract.EXTRA_ROLLBACK_ID, rollbackId)
        .putExtra(ExternalActionContract.EXTRA_INTERNAL_TOKEN, internalToken)

    companion object {
        private const val TAG = "UCloneTaskService"
        private const val NOTIFICATION_ID = 41011
        private val OPERATIONS_CLEARING_HISTORY = setOf(
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
        )

        fun start(context: Context, sourceIntent: Intent?) {
            val serviceIntent = Intent(sourceIntent ?: Intent())
                .setClass(context, ExternalActionService::class.java)
                .setAction(ExternalActionContract.ACTION_EXECUTE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        }

        fun startInternal(context: Context, operation: String, packageName: String, rollbackId: String? = null) {
            start(
                context,
                Intent()
                    .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
                    .putExtra(ExternalActionContract.EXTRA_OPERATION, operation)
                    .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
                    .putExtra(ExternalActionContract.EXTRA_SOURCE, ExternalActionContract.SOURCE_APP)
                    .putExtra(ExternalActionContract.EXTRA_ROLLBACK_ID, rollbackId)
                    .putExtra(
                        ExternalActionContract.EXTRA_INTERNAL_TOKEN,
                        (context.applicationContext as UCloneApplication).container.internalRequestToken,
                    ),
            )
        }
    }
}

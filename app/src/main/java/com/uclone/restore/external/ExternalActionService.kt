package com.uclone.restore.external

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.uclone.restore.UCloneApplication
import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ExternalActionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as UCloneApplication).container
        if (intent?.action != ExternalActionContract.ACTION_EXECUTE) {
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        val request = ExternalActionRequest.from(intent)
        if (request == null) {
            reject(intent, ExternalActionContract.STATUS_REJECTED, "请求参数无效")
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        val settings = container.settingsStore.load()
        ExternalRequestPolicy.rejection(this, request, settings, container.internalRequestToken)?.let { reason ->
            reject(request, ExternalActionContract.STATUS_REJECTED, reason)
            stopIfIdle(startId)
            return START_NOT_STICKY
        }
        return when (val admission = admitExternalTask(container.taskCoordinator, request)) {
            is ExternalTaskAdmission.Rejected -> {
                reject(request, admission.status, admission.message)
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
            notifier.clearResult()
            startForeground(
                NOTIFICATION_ID,
                notifier.running(request.packageName, request.operation, "任务已接收"),
            )
        } catch (error: Exception) {
            val message = error.message ?: "无法启动任务服务"
            container.taskCoordinator.failAndComplete(request.requestId, message)
            reject(request, ExternalActionContract.STATUS_FAILED, message)
            stopSelf(startId)
            return
        }
        scope.launch {
            try {
                executeAccepted(request, settings)
            } finally {
                container.taskCoordinator.complete(request.requestId)
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifier.clearRunning()
                stopSelf()
            }
        }
    }

    private fun stopIfIdle(startId: Int) {
        if (shouldStopRejectedService((application as UCloneApplication).container.taskCoordinator.isBusy())) {
            stopSelf(startId)
        }
    }

    private suspend fun executeAccepted(
        request: ExternalActionRequest,
        settings: com.uclone.restore.model.UCloneSettings,
    ) {
        val container = (application as UCloneApplication).container
        val notifier = ExternalActionNotifier(this)
        broadcastStatus(request, ExternalActionContract.STATUS_ACCEPTED, "任务已接收")
        notifier.notifyAccepted(request.packageName, request.operation, "任务已接收")
        Log.i(TAG, "accepted operation=${request.operation} package=${request.packageName} request=${request.requestId}")
        val report: (TaskProgress) -> Unit = { progress ->
            container.taskRepository.publish(progress)
            notifier.updateRunning(request.packageName, request.operation, progress.externalMessage())
        }
        runCatching {
            ExternalActionDispatcher(container).execute(request, settings, report)
        }.fold(
            onSuccess = { finishSuccess(request, it, notifier) },
            onFailure = { error ->
                val message = error.message ?: "任务执行失败"
                val failed = container.taskCoordinator.fail(request.requestId, message)
                Log.e(TAG, "failed operation=${request.operation} package=${request.packageName}", error)
                broadcastStatus(request, ExternalActionContract.STATUS_FAILED, message, failed)
                notifier.notifyResult(request.packageName, request.operation, ExternalActionContract.STATUS_FAILED, message)
            },
        )
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

    private fun TaskProgress.externalMessage(): String {
        if (task?.status?.isTerminal == true) return task.message
        return task?.currentStage?.displayLabel
            ?: steps.firstOrNull { it.status == StepStatus.RUNNING }?.label
            ?: liveLog.lineSequence().map(String::trim).firstOrNull(String::isNotBlank)
            ?: "执行中"
    }

    private fun reject(request: ExternalActionRequest, status: String, message: String) {
        broadcastStatus(request, status, message)
        ExternalActionNotifier(this).notifyResult(request.packageName, request.operation, status, message)
    }

    private fun reject(intent: Intent, status: String, message: String) {
        broadcastStatus(intent, status, message)
        ExternalActionNotifier(this).notifyResult(
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

internal fun shouldStopRejectedService(taskActive: Boolean): Boolean = !taskActive

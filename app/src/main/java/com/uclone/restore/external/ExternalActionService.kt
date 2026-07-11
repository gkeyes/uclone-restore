package com.uclone.restore.external

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notifier = ExternalActionNotifier(this)
        val needsBootstrapForeground = synchronized(lifecycleLock) {
            lifecyclePolicy.onStart(startId)
        }
        if (needsBootstrapForeground && !startBootstrapForeground(intent, startId, notifier)) {
            return START_NOT_STICKY
        }
        val container = (application as UCloneApplication).container
        recordIntentEvent(intent, startId, ExternalRequestStage.SERVICE_RECEIVED, "前台服务已收到请求")
        intent?.let {
            broadcastStatus(
                it,
                ExternalActionContract.STATUS_SERVICE_RECEIVED,
                "前台服务已收到请求",
            )
        }
        if (intent?.action != ExternalActionContract.ACTION_EXECUTE) {
            reject(intent ?: Intent(), ExternalActionContract.STATUS_REJECTED, "请求 action 无效")
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

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "event=FOREGROUND_SERVICE_TIMEOUT startId=$startId type=$fgsType")
        scope.cancel("Foreground service time limit reached")
        stopForeground(STOP_FOREGROUND_REMOVE)
        ExternalActionNotifier(this).clearRunning()
        stopSelf()
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
            }
            notifier.clearResult()
            promoteToForeground(
                notifier.running(request.packageName, request.operation, "任务已接收"),
                request.source,
            )
        } catch (error: Exception) {
            val message = error.message ?: "无法启动任务服务"
            Log.e(
                TAG,
                "event=FOREGROUND_ACCEPTED_FAILED startId=$startId request=${request.requestId}",
                error,
            )
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

    private fun startBootstrapForeground(
        intent: Intent?,
        startId: Int,
        notifier: ExternalActionNotifier,
    ): Boolean {
        try {
            promoteToForeground(
                notifier.running(packageName = null, operation = null, message = "正在验证请求"),
                intent?.getStringExtra(ExternalActionContract.EXTRA_SOURCE),
            )
            return true
        } catch (error: Exception) {
            val message = foregroundStartFailureMessage(error)
            Log.e(
                TAG,
                "event=FOREGROUND_BOOTSTRAP_FAILED startId=$startId action=${intent?.action.orEmpty()}",
                error,
            )
            intent?.let {
                runCatching { reject(it, ExternalActionContract.STATUS_FAILED, message) }
                    .onFailure { reportError ->
                        Log.e(
                            TAG,
                            "event=FOREGROUND_BOOTSTRAP_REPORT_FAILED startId=$startId",
                            reportError,
                        )
                    }
            }
            runCatching {
                synchronized(lifecycleLock) {
                    applyFinalization(lifecyclePolicy.onBootstrapFailed(startId), notifier)
                }
            }.onFailure { cleanupError ->
                Log.e(
                    TAG,
                    "event=FOREGROUND_BOOTSTRAP_CLEANUP_FAILED startId=$startId",
                    cleanupError,
                )
            }
            return false
        }
    }

    private fun finishRejectedStart(startId: Int) {
        synchronized(lifecycleLock) {
            val finalization = lifecyclePolicy.onRejected(startId)
            val notifier = ExternalActionNotifier(this).takeIf { finalization.removeForeground }
            applyFinalization(finalization, notifier)
        }
    }

    private fun promoteToForeground(notification: android.app.Notification, source: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        val type = when (ExternalForegroundServicePolicy.workType(source, Build.VERSION.SDK_INT)) {
            ExternalForegroundWorkType.DATA_SYNC -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            ExternalForegroundWorkType.SPECIAL_USE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun foregroundStartFailureMessage(error: Exception): String {
        val detail = error.message.orEmpty()
        return if (detail.contains("Time limit already exhausted", ignoreCase = true)) {
            "前台任务服务可用时长已耗尽，请打开 UClone 后重试"
        } else {
            detail.takeIf(String::isNotBlank)?.let { "无法启动前台任务服务：$it" }
                ?: "无法启动前台任务服务"
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
        recordRequestEvent(request, ExternalRequestStage.ACCEPTED, "任务已接收")
        notifier.notifyAccepted(request.packageName, request.operation, "任务已接收")
        Log.i(TAG, "accepted operation=${request.operation} package=${request.packageName} request=${request.requestId}")
        var recordedTaskStage: com.uclone.restore.model.TaskStage? = null
        var runningRecorded = false
        val report: (TaskProgress) -> Unit = { progress ->
            container.taskRepository.publish(progress)
            val progressStage = progress.task?.currentStage
            if (!progress.task?.status?.isTerminal.orFalse() && (!runningRecorded || progressStage != recordedTaskStage)) {
                runningRecorded = true
                recordedTaskStage = progressStage
                recordRequestEvent(
                    request,
                    ExternalRequestStage.RUNNING,
                    progress.task?.message ?: "任务运行中",
                    progressStage,
                )
                broadcastStatus(request, ExternalActionContract.STATUS_RUNNING, progress.task?.message ?: "任务运行中")
            }
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
            finishTask(request, ExternalActionDispatcher(container).execute(request, settings, report), notifier)
        } catch (cancelled: CancellationException) {
            val message = "任务已中断"
            val interrupted = container.taskCoordinator.interrupt(request.requestId, message)
            recordRequestEvent(request, ExternalRequestStage.INTERRUPTED, message)
            broadcastStatus(request, ExternalActionContract.STATUS_INTERRUPTED, message, interrupted)
            notifier.notifyResult(request.packageName, request.operation, ExternalActionContract.STATUS_INTERRUPTED, message)
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "任务执行失败"
            val failed = container.taskCoordinator.fail(request.requestId, message)
            recordRequestEvent(request, ExternalRequestStage.FAILED, message)
            Log.e(TAG, "failed operation=${request.operation} package=${request.packageName}", error)
            broadcastStatus(request, ExternalActionContract.STATUS_FAILED, message, failed)
            notifier.notifyResult(request.packageName, request.operation, ExternalActionContract.STATUS_FAILED, message)
        }
    }

    private fun finishTask(
        request: ExternalActionRequest,
        record: TaskRecord,
        notifier: ExternalActionNotifier,
    ) {
        val rootTaskStillRunning = record.message.contains("另一个 Root 数据任务仍在运行")
        val orphanRecovered = record.message.contains("已隔离上次进程遗留的孤儿任务标记")
        if (orphanRecovered) {
            recordRequestEvent(request, ExternalRequestStage.ORPHANED, "已隔离上次进程遗留的孤儿任务标记")
            broadcastStatus(request, ExternalActionContract.STATUS_ORPHANED, "已隔离上次进程遗留的孤儿任务标记", record)
        }
        val status = if (rootTaskStillRunning) {
            ExternalActionContract.STATUS_STILL_RUNNING
        } else {
            record.status.toExternalStatus()
        }
        recordRequestEvent(
            request,
            when {
                rootTaskStillRunning -> ExternalRequestStage.STILL_RUNNING
                record.status == com.uclone.restore.model.TaskStatus.SUCCESS -> ExternalRequestStage.SUCCESS
                record.status == com.uclone.restore.model.TaskStatus.SUCCESS_WITH_WARNINGS -> ExternalRequestStage.SUCCESS_WITH_WARNINGS
                record.status == com.uclone.restore.model.TaskStatus.INTERRUPTED -> ExternalRequestStage.INTERRUPTED
                else -> ExternalRequestStage.FAILED
            },
            record.message,
        )
        Log.i(TAG, "finished status=$status operation=${request.operation} package=${request.packageName} message=${record.message}")
        broadcastStatus(request, status, record.message, record)
        notifier.notifyResult(request.packageName, request.operation, status, record.message)
        if (record.status.isSuccessful && request.operation in OPERATIONS_CLEARING_HISTORY) {
            (application as UCloneApplication).container.taskRepository.clearHistoryPreservingProgress()
        }
    }

    private fun reject(request: ExternalActionRequest, status: String, message: String) {
        recordRequestEvent(request, status.toExternalRequestStage(), message)
        broadcastStatus(request, status, message)
        ExternalActionNotifier(this).notifyRejected(request.packageName, request.operation, status, message)
    }

    private fun reject(intent: Intent, status: String, message: String) {
        recordIntentEvent(intent, startId = 0, status.toExternalRequestStage(), message)
        broadcastStatus(intent, status, message)
        ExternalActionNotifier(this).notifyRejected(
            intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME),
            intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION),
            status,
            message,
        )
    }

    private fun broadcastStatus(request: ExternalActionRequest, status: String, message: String, task: TaskRecord? = null) {
        if (!hasLauncherModuleStatusRecipient(request.source)) return
        broadcastStatus(request.toIntent(), status, message, task)
    }

    private fun broadcastStatus(intent: Intent, status: String, message: String, task: TaskRecord? = null) {
        val source = intent.getStringExtra(ExternalActionContract.EXTRA_SOURCE).orEmpty()
        if (!hasLauncherModuleStatusRecipient(source)) return
        val statusIntent = Intent(ExternalActionContract.ACTION_STATUS)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .setComponent(
                ComponentName(
                    ExternalActionContract.LAUNCHER_MODULE_PACKAGE,
                    ExternalActionContract.LAUNCHER_MODULE_STATUS_RECEIVER,
                ),
            )
            .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
            .putExtra(ExternalActionContract.EXTRA_OPERATION, intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, intent.getStringExtra(ExternalActionContract.EXTRA_REQUEST_ID).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_SOURCE, source)
            .putExtra(ExternalActionContract.EXTRA_STATUS, status)
            .putExtra(ExternalActionContract.EXTRA_MESSAGE, message)
        task?.let { statusIntent.putExtra(ExternalActionContract.EXTRA_TASK_TYPE, it.type.name) }
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
        .apply {
            this@toIntent.targetUserId?.let { putExtra(ExternalActionContract.EXTRA_TARGET_USER_ID, it) }
        }

    private fun recordRequestEvent(
        request: ExternalActionRequest,
        stage: ExternalRequestStage,
        message: String,
        taskStage: com.uclone.restore.model.TaskStage? = null,
    ) {
        if (request.source == ExternalActionContract.SOURCE_APP) return
        recordEvent(
            ExternalRequestEvent(
                requestId = request.requestId,
                operation = request.operation,
                packageName = request.packageName,
                source = request.source,
                stage = stage,
                occurredAt = System.currentTimeMillis(),
                message = message,
                taskStage = taskStage,
            ),
        )
    }

    private fun recordIntentEvent(
        intent: Intent?,
        startId: Int,
        stage: ExternalRequestStage,
        message: String,
    ) {
        val source = intent?.getStringExtra(ExternalActionContract.EXTRA_SOURCE).orEmpty()
        if (source == ExternalActionContract.SOURCE_APP) return
        recordEvent(
            ExternalRequestEvent(
                requestId = intent?.getStringExtra(ExternalActionContract.EXTRA_REQUEST_ID)
                    ?.takeIf(String::isNotBlank)
                    ?: "invalid-$startId-${System.currentTimeMillis()}",
                operation = intent?.getStringExtra(ExternalActionContract.EXTRA_OPERATION).orEmpty(),
                packageName = intent?.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME).orEmpty(),
                source = source.ifBlank { "unknown" },
                stage = stage,
                occurredAt = System.currentTimeMillis(),
                message = message,
            ),
        )
    }

    private fun recordEvent(event: ExternalRequestEvent) {
        runCatching { (application as UCloneApplication).container.externalRequestStore.record(event) }
            .onFailure { error -> Log.e(TAG, "event=EXTERNAL_REQUEST_PERSIST_FAILED request=${event.requestId}", error) }
    }

    private fun String.toExternalRequestStage(): ExternalRequestStage = when (this) {
        ExternalActionContract.STATUS_BUSY,
        ExternalActionContract.STATUS_ALREADY_RUNNING,
        -> ExternalRequestStage.BUSY
        ExternalActionContract.STATUS_INTERRUPTED -> ExternalRequestStage.INTERRUPTED
        ExternalActionContract.STATUS_STILL_RUNNING -> ExternalRequestStage.STILL_RUNNING
        ExternalActionContract.STATUS_ORPHANED -> ExternalRequestStage.ORPHANED
        ExternalActionContract.STATUS_FAILED_PROCESS_DIED -> ExternalRequestStage.FAILED_PROCESS_DIED
        ExternalActionContract.STATUS_FAILED -> ExternalRequestStage.FAILED
        else -> ExternalRequestStage.REJECTED
    }

    private fun Boolean?.orFalse(): Boolean = this == true

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

        fun startInternal(
            context: Context,
            operation: String,
            packageName: String,
            rollbackId: String? = null,
            targetUserId: Int? = null,
        ) {
            start(
                context,
                Intent()
                    .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
                    .putExtra(ExternalActionContract.EXTRA_OPERATION, operation)
                    .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, packageName)
                    .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
                    .putExtra(ExternalActionContract.EXTRA_SOURCE, ExternalActionContract.SOURCE_APP)
                    .putExtra(ExternalActionContract.EXTRA_ROLLBACK_ID, rollbackId)
                    .apply {
                        targetUserId?.let { putExtra(ExternalActionContract.EXTRA_TARGET_USER_ID, it) }
                    }
                    .putExtra(
                        ExternalActionContract.EXTRA_INTERNAL_TOKEN,
                        (context.applicationContext as UCloneApplication).container.internalRequestToken,
                    ),
            )
        }
    }
}

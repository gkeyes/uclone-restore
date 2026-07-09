package com.uclone.restore.external

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.IBinder
import com.uclone.restore.UCloneApplication
import com.uclone.restore.model.AppRule
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.UCloneSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ExternalActionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val externalTaskRunning = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as UCloneApplication).container
        if (intent?.action != ExternalActionContract.ACTION_EXECUTE) {
            if (!externalTaskRunning.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        val notifier = ExternalActionNotifier(this)
        if (!container.syncEngine.tryBeginOperation()) {
            broadcastStatus(intent, ExternalActionContract.STATUS_BUSY, "已有任务正在执行")
            notifier.notifyResult(
                intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME),
                intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION),
                ExternalActionContract.STATUS_BUSY,
                "已有任务正在执行",
            )
            if (!externalTaskRunning.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        externalTaskRunning.set(true)
        try {
            notifier.clearResult()
            startForeground(
                NOTIFICATION_ID,
                notifier.running(
                    intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME),
                    intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION),
                    "正在启动",
                ),
            )
        } catch (error: Exception) {
            externalTaskRunning.set(false)
            container.syncEngine.finishOperation()
            broadcastStatus(intent, ExternalActionContract.STATUS_FAILED, error.message ?: "无法启动外部任务服务")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                execute(intent, container)
            } finally {
                val cleanupNotifier = ExternalActionNotifier(this@ExternalActionService)
                externalTaskRunning.set(false)
                container.syncEngine.finishOperation()
                stopForeground(STOP_FOREGROUND_REMOVE)
                cleanupNotifier.clearRunning()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private suspend fun execute(intent: Intent, container: com.uclone.restore.AppContainer) {
        val notifier = ExternalActionNotifier(this)
        val request = ExternalActionRequest.from(intent)
        if (request == null) {
            broadcastStatus(intent, ExternalActionContract.STATUS_REJECTED, "外部请求参数无效")
            notifier.notifyResult(
                intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME),
                intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION),
                ExternalActionContract.STATUS_REJECTED,
                "外部请求参数无效",
            )
            return
        }
        val settings = container.settingsStore.load()
        val rejected = rejectReason(request.packageName, settings)
        if (rejected != null) {
            broadcastStatus(request, ExternalActionContract.STATUS_REJECTED, rejected)
            notifier.notifyResult(request.packageName, request.operation, ExternalActionContract.STATUS_REJECTED, rejected)
            return
        }
        broadcastStatus(request, ExternalActionContract.STATUS_ACCEPTED, "任务已接收")
        notifier.updateRunning(request.packageName, request.operation, "开始执行")
        val report: suspend (TaskProgress) -> Unit = {}
        val task = runCatching {
            when (request.operation) {
                ExternalActionContract.OPERATION_BACKUP_DEFAULT ->
                    container.syncEngine.captureSnapshot(request.packageName, ruleFor(request.packageName, settings), settings, report)
                ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP ->
                    container.syncEngine.restoreSnapshot(request.packageName, settings, report)
                ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE ->
                    container.syncEngine.pushMainToClone(request.packageName, ruleFor(request.packageName, settings), settings, report)
                ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK ->
                    container.syncEngine.restoreCloneRollback(request.packageName, settings, report)
                ExternalActionContract.OPERATION_SWITCH_TO_CLONE ->
                    switchToCloneState(request.packageName, settings, report)
                ExternalActionContract.OPERATION_RESTORE_MAIN ->
                    restoreMainState(request.packageName, settings, report)
                ExternalActionContract.OPERATION_SWITCH_OR_RESTORE ->
                    switchOrRestore(request.packageName, settings, report)
                else -> error("不支持的模块操作：${request.operation}")
            }
        }
        task.fold(
            onSuccess = { record ->
                val status = if (record.status == TaskStatus.SUCCESS) {
                    ExternalActionContract.STATUS_SUCCESS
                } else {
                    ExternalActionContract.STATUS_FAILED
                }
                broadcastStatus(request, status, record.message, record)
                notifier.notifyResult(request.packageName, request.operation, status, record.message)
            },
            onFailure = { error ->
                val message = error.message ?: "模块操作失败"
                broadcastStatus(request, ExternalActionContract.STATUS_FAILED, message)
                notifier.notifyResult(request.packageName, request.operation, ExternalActionContract.STATUS_FAILED, message)
            },
        )
    }

    private suspend fun switchOrRestore(
        packageName: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord {
        val rollbackId = (application as UCloneApplication).container.syncEngine.switchMarkerId(packageName, settings)
        return if (rollbackId == null) {
            (application as UCloneApplication).container.syncEngine.switchToCloneState(
                packageName,
                ruleFor(packageName, settings),
                settings,
                report,
            )
        } else {
            (application as UCloneApplication).container.syncEngine.restoreSwitchMainState(packageName, rollbackId, settings, report)
        }
    }

    private suspend fun switchToCloneState(
        packageName: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord {
        val container = (application as UCloneApplication).container
        if (container.syncEngine.switchMarkerId(packageName, settings) != null) {
            error("已处于分身态，请先还原主系统")
        }
        return container.syncEngine.switchToCloneState(packageName, ruleFor(packageName, settings), settings, report)
    }

    private suspend fun restoreMainState(
        packageName: String,
        settings: UCloneSettings,
        report: suspend (TaskProgress) -> Unit,
    ): TaskRecord {
        val rollbackId = (application as UCloneApplication).container.syncEngine.switchMarkerId(packageName, settings)
            ?: error("没有可用的切换回滚点")
        return (application as UCloneApplication).container.syncEngine.restoreSwitchMainState(packageName, rollbackId, settings, report)
    }

    private fun rejectReason(packageName: String, settings: UCloneSettings): String? {
        if (!settings.allowModuleControl) return "模块控制未开启"
        if (packageName == this.packageName) return "不允许控制 UClone 自身"
        val info = runCatching {
            packageManager.getApplicationInfo(packageName, 0)
        }.getOrElse {
            return "目标 App 不存在：$packageName"
        }
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (info.flags and systemFlags != 0) return "默认不允许控制系统 App"
        return null
    }

    private fun ruleFor(packageName: String, settings: UCloneSettings): AppRule =
        AppRule(
            packageName = packageName,
            includeCe = settings.includeCe,
            includeDe = settings.includeDe,
            includeExternal = settings.includeExternal,
            includeMedia = settings.includeMedia,
            includeObb = settings.includeObb,
            includePermissions = settings.includePermissions,
            excludeCache = settings.excludeCache,
        )

    private fun broadcastStatus(request: ExternalActionRequest, status: String, message: String, task: TaskRecord? = null) {
        broadcastStatus(
            Intent()
                .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
                .putExtra(ExternalActionContract.EXTRA_OPERATION, request.operation)
                .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, request.packageName)
                .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, request.requestId)
                .putExtra(ExternalActionContract.EXTRA_SOURCE, request.source),
            status,
            message,
            task,
        )
    }

    private fun broadcastStatus(intent: Intent, status: String, message: String, task: TaskRecord? = null) {
        val statusIntent = Intent(ExternalActionContract.ACTION_STATUS)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
            .putExtra(ExternalActionContract.EXTRA_OPERATION, intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, intent.getStringExtra(ExternalActionContract.EXTRA_REQUEST_ID).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_SOURCE, intent.getStringExtra(ExternalActionContract.EXTRA_SOURCE).orEmpty())
            .putExtra(ExternalActionContract.EXTRA_STATUS, status)
            .putExtra(ExternalActionContract.EXTRA_MESSAGE, message)
        task?.let {
            statusIntent.putExtra(ExternalActionContract.EXTRA_TASK_TYPE, it.type.name)
        }
        val source = intent.getStringExtra(ExternalActionContract.EXTRA_SOURCE).orEmpty()
        if (source == ExternalActionContract.SOURCE_MODULE || source == ExternalActionContract.SOURCE_LAUNCHER_MODULE) {
            statusIntent.component = ComponentName(
                ExternalActionContract.LAUNCHER_MODULE_PACKAGE,
                ExternalActionContract.LAUNCHER_MODULE_STATUS_RECEIVER,
            )
        }
        sendBroadcast(statusIntent, ExternalActionContract.PERMISSION_CONTROL)
    }

    companion object {
        private const val NOTIFICATION_ID = 41011
    }
}

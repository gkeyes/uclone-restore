package com.uclone.restore.external

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.uclone.restore.MainActivity
import com.uclone.restore.R

class ExternalActionNotifier(private val context: Context) {
    fun running(packageName: String?, operation: String?, message: String): Notification {
        ensureChannels()
        return builder(RUNNING_CHANNEL_ID, operation)
            .setContentText("${displayName(packageName)}：$message")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun clearResult() {
        manager.cancel(RESULT_NOTIFICATION_ID)
    }

    fun clearRunning() {
        manager.cancel(RUNNING_NOTIFICATION_ID)
    }

    fun updateRunning(packageName: String?, operation: String?, message: String) {
        manager.notify(RUNNING_NOTIFICATION_ID, running(packageName, operation, message))
    }

    fun notifyAccepted(packageName: String?, operation: String?, message: String) {
        ensureChannels()
        val notification = builder(RESULT_CHANNEL_ID, operation)
            .setContentText("${displayName(packageName)}：$message")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    fun notifyResult(packageName: String?, operation: String?, status: String, message: String) {
        ensureChannels()
        val success = status == ExternalActionContract.STATUS_SUCCESS
        val rejected = status == ExternalActionContract.STATUS_REJECTED ||
            status == ExternalActionContract.STATUS_BUSY ||
            status == ExternalActionContract.STATUS_ALREADY_RUNNING
        val stateText = when {
            success -> "成功"
            rejected -> "未执行"
            else -> "失败"
        }
        val notification = builder(RESULT_CHANNEL_ID, operation)
            .setContentText("${displayName(packageName)}：$stateText · $message")
            .setAutoCancel(true)
            .setPriority(if (success) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun builder(channelId: String, operation: String?): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("UClone ${operationLabel(operation)}")
            .setContentIntent(contentIntent())

    private fun contentIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    private fun displayName(packageName: String?): String {
        val target = packageName?.takeIf(String::isNotBlank) ?: return "目标 App"
        return runCatching {
            val info = context.packageManager.getApplicationInfo(target, 0)
            context.packageManager.getApplicationLabel(info).toString().takeIf(String::isNotBlank)
        }.getOrNull() ?: target
    }

    private fun operationLabel(operation: String?): String = when (operation) {
        ExternalActionContract.OPERATION_BACKUP_DEFAULT -> "备份"
        ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP -> "恢复"
        ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE -> "推送分身"
        ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK -> "分身回滚"
        ExternalActionContract.OPERATION_SWITCH_TO_CLONE -> "切换"
        ExternalActionContract.OPERATION_RESTORE_MAIN -> "还原"
        ExternalActionContract.OPERATION_SWITCH_OR_RESTORE -> "切换/还原"
        ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST -> "分身恢复"
        ExternalActionContract.OPERATION_RESTORE_ROLLBACK -> "恢复回滚"
        ExternalActionContract.OPERATION_DELETE_SNAPSHOT -> "删除快照"
        ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP -> "删除回滚"
        ExternalActionContract.OPERATION_PROBE_CLONE_CE -> "分身检测"
        ExternalActionContract.OPERATION_UNLOCK_CLONE -> "分身解锁"
        ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM -> "分身调试"
        ExternalActionContract.OPERATION_AUDIT_RESTORE -> "恢复审计"
        ExternalActionContract.OPERATION_CLEAR_LOGS -> "清理日志"
        ExternalActionContract.OPERATION_RESET_WORKSPACE -> "重置"
        ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER -> "跨用户安装"
        ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER -> "安装并迁移权限"
        ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER -> "安装并同步数据"
        ExternalActionContract.OPERATION_START_CLONE_USER -> "启动分身"
        ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER -> "进入分身"
        ExternalActionContract.OPERATION_STOP_CLONE_USER -> "关闭分身"
        else -> "快捷操作"
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(RUNNING_CHANNEL_ID, "UClone external actions", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "UClone action results", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private companion object {
        const val RUNNING_CHANNEL_ID = "uclone_external_actions"
        const val RESULT_CHANNEL_ID = "uclone_external_results"
        const val RUNNING_NOTIFICATION_ID = 41011
        const val RESULT_NOTIFICATION_ID = 41012
    }
}

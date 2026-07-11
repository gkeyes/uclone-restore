package com.uclone.restore.external

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.uclone.restore.model.UCloneSettings

internal object ExternalRequestPolicy {
    fun rejection(
        context: Context,
        request: ExternalActionRequest,
        settings: UCloneSettings,
        internalRequestToken: String,
    ): String? {
        request.sourceAccessRejection(settings.allowModuleControl, internalRequestToken)?.let { return it }
        request.sourceOperationRejection()?.let { return it }
        val installOperation = request.operation in INSTALL_OPERATIONS
        if (installOperation && request.targetUserId !in setOf(settings.mainUserId, settings.cloneUserId)) {
            return "目标用户必须是 user${settings.mainUserId} 或 user${settings.cloneUserId}"
        }
        if (request.source == ExternalActionContract.SOURCE_APP && !installOperation) return null
        val fromLauncherShortcut = request.source == ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT
        if (fromLauncherShortcut && request.packageName !in settings.favoritePackages) {
            return "快捷入口已失效，请打开 UClone 刷新收藏快捷方式"
        }
        if (request.packageName == context.packageName) return "不允许控制 UClone 自身"
        val info = runCatching {
            context.packageManager.getApplicationInfo(
                request.packageName,
                if (installOperation) PackageManager.MATCH_UNINSTALLED_PACKAGES else 0,
            )
        }.getOrElse {
            return "目标 App 不存在：${request.packageName}"
        }
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (info.flags and systemFlags != 0 && (!installOperation || !settings.allowSystemAppInstall)) {
            return if (installOperation) "系统 App 跨用户安装未在高级设置中启用" else "默认不允许控制系统 App"
        }
        return null
    }
}

internal fun ExternalActionRequest.sourceOperationRejection(): String? = when (source) {
    ExternalActionContract.SOURCE_APP -> null
    ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT ->
        if (operation == ExternalActionContract.OPERATION_SWITCH_OR_RESTORE) null else "桌面快捷入口不允许执行此操作"
    ExternalActionContract.SOURCE_MODULE,
    ExternalActionContract.SOURCE_LAUNCHER_MODULE,
    -> if (operation in MODULE_ALLOWED_OPERATIONS) null else "模块不允许执行此操作"
    else -> "未知的任务来源"
}

private val MODULE_ALLOWED_OPERATIONS = setOf(
    ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
    ExternalActionContract.OPERATION_SWITCH_TO_CLONE,
    ExternalActionContract.OPERATION_RESTORE_MAIN,
    ExternalActionContract.OPERATION_BACKUP_DEFAULT,
    ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP,
    ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE,
    ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK,
)

private val INSTALL_OPERATIONS = setOf(
    ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
    ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
    ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER,
)

internal fun ExternalActionRequest.sourceAccessRejection(
    allowModuleControl: Boolean,
    internalRequestToken: String,
): String? = when (source) {
    ExternalActionContract.SOURCE_APP ->
        if (internalToken == internalRequestToken) null else "无效的内部任务请求"
    ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT ->
        if (internalToken == internalRequestToken) null else "无效的桌面快捷请求"
    else -> if (allowModuleControl) null else "模块控制未开启"
}

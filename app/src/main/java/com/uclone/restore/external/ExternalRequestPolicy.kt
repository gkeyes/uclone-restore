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
        if (request.operation in INSTALL_OPERATIONS) {
            return installRejection(context, request, settings)
        }
        if (request.source == ExternalActionContract.SOURCE_APP) return null
        val fromLauncherShortcut = request.source == ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT
        if (fromLauncherShortcut && request.packageName !in settings.favoritePackages) {
            return "快捷入口已失效，请打开 UClone 刷新收藏快捷方式"
        }
        if (request.packageName == context.packageName) return "不允许控制 UClone 自身"
        val info = context.applicationInfoIncludingOtherUsers(request.packageName) ?: run {
            return "目标 App 不存在：${request.packageName}"
        }
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (info.flags and systemFlags != 0) return "默认不允许控制系统 App"
        return null
    }

    private fun installRejection(
        context: Context,
        request: ExternalActionRequest,
        settings: UCloneSettings,
    ): String? {
        if (settings.mainUserId == settings.cloneUserId) return "主系统和分身系统用户不能相同"
        if (request.targetUserId !in setOf(settings.mainUserId, settings.cloneUserId)) {
            return "安装目标不是当前配置的主系统或分身系统"
        }
        if (request.packageName == context.packageName) return "不允许跨用户安装 UClone 自身"
        val info = context.applicationInfoIncludingOtherUsers(request.packageName)
            ?: return "目标 App 不存在：${request.packageName}"
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (info.flags and systemFlags != 0) return "0.3 暂不支持跨用户安装系统 App"
        return null
    }
}

private fun Context.applicationInfoIncludingOtherUsers(packageName: String): ApplicationInfo? = runCatching {
    packageManager.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
}.getOrNull()

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

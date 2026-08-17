package com.uclone.crossuser.api

object CrossUserProtocol {
    const val SCHEMA_VERSION = 1
    const val MAIN_APP_PACKAGE = "com.uclone.restore"
    const val PROBE_PACKAGE = "com.gkeyes.uclone.crossuserprobe"
    const val PROBE_COMPONENT = "$PROBE_PACKAGE/.MainActivity"
    const val WECHAT_PACKAGE = "com.tencent.mm"
    const val WECHAT_COMPONENT = "$WECHAT_PACKAGE/.ui.LauncherUI"
    const val BACKEND_BRIDGE_TRUSTED_DISPLAY = "bridge_trusted_display"
    const val CAPABILITY_VISIBLE_BACKGROUND_USERS_RUNTIME = "visible_background_users_runtime"

    const val STATUS_SUPPORTED = "SUPPORTED"
    const val STATUS_UNSUPPORTED = "UNSUPPORTED"
    const val STATUS_BLOCKED_PERMISSION = "BLOCKED_BY_PERMISSION"
    const val STATUS_BLOCKED_SELINUX = "BLOCKED_BY_SELINUX"
    const val STATUS_UNKNOWN = "UNKNOWN"

    const val SESSION_IDLE = "IDLE"
    const val SESSION_PRECHECKING = "PRECHECKING"
    const val SESSION_DISPLAY_READY = "DISPLAY_READY"
    const val SESSION_USER_STARTING = "USER_STARTING"
    const val SESSION_TARGET_LAUNCHING = "TARGET_LAUNCHING"
    const val SESSION_WAITING_FIRST_FRAME = "WAITING_FIRST_FRAME"
    const val SESSION_ACTIVE = "ACTIVE"
    const val SESSION_CLOSING = "CLOSING"
    const val SESSION_CLOSED = "CLOSED"
    const val SESSION_FAILED = "FAILED"
    const val SESSION_UNSUPPORTED = "UNSUPPORTED"
    const val SESSION_RECOVERY_REQUIRED = "RECOVERY_REQUIRED"

    fun isAllowedTarget(packageName: String, componentName: String): Boolean =
        componentForPackage(packageName) == componentName

    fun componentForPackage(packageName: String): String? = when (packageName) {
        PROBE_PACKAGE -> PROBE_COMPONENT
        WECHAT_PACKAGE -> WECHAT_COMPONENT
        else -> null
    }
}

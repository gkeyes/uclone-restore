package com.uclone.restore.module.relay

object ModuleConstants {
    const val MODULE_PACKAGE = "com.uclone.restore.module"
    const val UCLONE_PACKAGE = "com.uclone.restore"
    const val UCLONE_ACTIVITY = "com.uclone.restore.external.ExternalActionActivity"
    const val UCLONE_SERVICE = "com.uclone.restore.external.ExternalActionService"
    const val CONTROL_PERMISSION = "com.uclone.restore.permission.CONTROL"

    const val PROVIDER_AUTHORITY = "com.uclone.restore.module.relay"
    const val METHOD_QUERY_MENU_STATE = "queryMenuState"
    const val METHOD_RECORD_HOOK_EVENT = "recordHookEvent"

    const val PREFS_NAME = "uclone_module_settings"
    const val KEY_HOOK_ENABLED = "hook_enabled"
    const val KEY_ALLOWED_PACKAGES = "allowed_packages"
    const val KEY_HOOK_EVENTS = "hook_events"
    const val KEY_RELAY_EVENTS = "relay_events"
    const val KEY_CONSECUTIVE_HOOK_ERRORS = "consecutive_hook_errors"
    const val KEY_HOOK_AUTO_DISABLED = "hook_auto_disabled"

    const val DEFAULT_ALLOWED_LAUNCHERS = "com.miui.home,com.android.launcher3"
    const val MENU_LABEL = "UClone 切换/还原"
    const val HOOK_ERROR_DISABLE_THRESHOLD = 8
}

package com.uclone.restore.module.relay

object ModuleRelayContract {
    const val STATUS = "status"
    const val STATUS_ACCEPTED = "ACCEPTED"
    const val STATUS_REJECTED = "REJECTED"
    const val STATUS_MENU_READY = "MENU_READY"
    const val STATUS_SENT = "SENT"
    const val STATUS_SERVICE_RECEIVED = "SERVICE_RECEIVED"
    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_SUCCESS_WITH_WARNINGS = "SUCCESS_WITH_WARNINGS"
    const val STATUS_BUSY = "BUSY"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_INTERRUPTED = "INTERRUPTED"
    const val STATUS_STILL_RUNNING = "STILL_RUNNING"
    const val STATUS_ORPHANED = "ORPHANED"
    const val STATUS_FAILED_PROCESS_DIED = "FAILED_PROCESS_DIED"

    const val EXTRA_OPERATION = "operation"
    const val EXTRA_PACKAGE_NAME = "packageName"
    const val EXTRA_COMPONENT_NAME = "componentName"
    const val EXTRA_TARGET_USER_ID = "targetUserId"
    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_SHOW_MENU = "showMenu"
    const val EXTRA_MENU_LABEL = "menuLabel"
    const val EXTRA_PENDING_INTENT = "pendingIntent"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_HOOK_EVENT = "hookEvent"
    const val EXTRA_EVENT_KIND = "eventKind"

    const val OPERATION_SWITCH_OR_RESTORE = "SWITCH_OR_RESTORE"

    const val UCLONE_ACTION_EXECUTE = "com.uclone.restore.action.EXECUTE"
    const val UCLONE_EXTRA_PROTOCOL_VERSION = "com.uclone.restore.extra.PROTOCOL_VERSION"
    const val UCLONE_EXTRA_OPERATION = "com.uclone.restore.extra.OPERATION"
    const val UCLONE_EXTRA_PACKAGE_NAME = "com.uclone.restore.extra.PACKAGE_NAME"
    const val UCLONE_EXTRA_REQUEST_ID = "com.uclone.restore.extra.REQUEST_ID"
    const val UCLONE_EXTRA_SOURCE = "com.uclone.restore.extra.SOURCE"
    const val UCLONE_EXTRA_STATUS = "com.uclone.restore.extra.STATUS"
    const val UCLONE_EXTRA_MESSAGE = "com.uclone.restore.extra.MESSAGE"
    const val UCLONE_PROTOCOL_VERSION = 1
    const val UCLONE_SOURCE = "launcher_module"
}

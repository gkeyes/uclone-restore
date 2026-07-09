package com.uclone.restore.external

object ExternalActionContract {
    const val PERMISSION_CONTROL = "com.uclone.restore.permission.CONTROL"
    const val LAUNCHER_MODULE_PACKAGE = "com.uclone.restore.module"
    const val LAUNCHER_MODULE_STATUS_RECEIVER = "com.uclone.restore.module.relay.UCloneStatusReceiver"

    const val ACTION_EXECUTE = "com.uclone.restore.action.EXECUTE"
    const val ACTION_STATUS = "com.uclone.restore.action.STATUS"

    const val EXTRA_PROTOCOL_VERSION = "com.uclone.restore.extra.PROTOCOL_VERSION"
    const val EXTRA_OPERATION = "com.uclone.restore.extra.OPERATION"
    const val EXTRA_PACKAGE_NAME = "com.uclone.restore.extra.PACKAGE_NAME"
    const val EXTRA_REQUEST_ID = "com.uclone.restore.extra.REQUEST_ID"
    const val EXTRA_SOURCE = "com.uclone.restore.extra.SOURCE"
    const val EXTRA_STATUS = "com.uclone.restore.extra.STATUS"
    const val EXTRA_ERROR_CODE = "com.uclone.restore.extra.ERROR_CODE"
    const val EXTRA_MESSAGE = "com.uclone.restore.extra.MESSAGE"
    const val EXTRA_TASK_TYPE = "com.uclone.restore.extra.TASK_TYPE"

    const val PROTOCOL_VERSION = 1

    const val OPERATION_SWITCH_OR_RESTORE = "SWITCH_OR_RESTORE"
    const val OPERATION_SWITCH_TO_CLONE = "SWITCH_TO_CLONE"
    const val OPERATION_RESTORE_MAIN = "RESTORE_MAIN"
    const val OPERATION_BACKUP_DEFAULT = "BACKUP_DEFAULT"
    const val OPERATION_RESTORE_LATEST_BACKUP = "RESTORE_LATEST_BACKUP"
    const val OPERATION_PUSH_MAIN_TO_CLONE = "PUSH_MAIN_TO_CLONE"
    const val OPERATION_RESTORE_LATEST_CLONE_ROLLBACK = "RESTORE_LATEST_CLONE_ROLLBACK"

    const val STATUS_ACCEPTED = "ACCEPTED"
    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_REJECTED = "REJECTED"
    const val STATUS_BUSY = "BUSY"
    const val STATUS_NEED_CONFIRMATION = "NEED_CONFIRMATION"
    const val STATUS_NEED_USER_ACTION = "NEED_USER_ACTION"

    const val SOURCE_MODULE = "module"
    const val SOURCE_LAUNCHER_MODULE = "launcher_module"
    const val SOURCE_LAUNCHER_SHORTCUT = "launcher_shortcut"
}

package com.uclone.restore.external

import com.uclone.restore.model.TaskType

internal fun taskTypeForOperation(operation: String): TaskType = when (operation) {
    ExternalActionContract.OPERATION_BACKUP_DEFAULT -> TaskType.CAPTURE_SNAPSHOT_FROM_CLONE
    ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP -> TaskType.RESTORE_SNAPSHOT_TO_MAIN
    ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST -> TaskType.RESTORE_FROM_CLONE_LATEST
    ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
    ExternalActionContract.OPERATION_SWITCH_TO_CLONE,
    -> TaskType.SWITCH_TO_CLONE_STATE
    ExternalActionContract.OPERATION_RESTORE_MAIN -> TaskType.RESTORE_SWITCH_MAIN_STATE
    ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE -> TaskType.PUSH_MAIN_TO_CLONE
    ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK -> TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE
    ExternalActionContract.OPERATION_RESTORE_ROLLBACK -> TaskType.ROLLBACK_MAIN_DATA
    ExternalActionContract.OPERATION_DELETE_SNAPSHOT -> TaskType.DELETE_SNAPSHOT
    ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP -> TaskType.DELETE_RESTORE_BACKUP
    ExternalActionContract.OPERATION_PROBE_CLONE_CE -> TaskType.PROBE_CLONE_CE
    ExternalActionContract.OPERATION_UNLOCK_CLONE -> TaskType.UNLOCK_CLONE_WITH_CREDENTIAL
    ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM -> TaskType.DEBUG_CLONE_SYSTEM
    ExternalActionContract.OPERATION_AUDIT_RESTORE -> TaskType.AUDIT_RESTORE_CONSISTENCY
    ExternalActionContract.OPERATION_CLEAR_LOGS -> TaskType.CLEAR_LOGS
    ExternalActionContract.OPERATION_RESET_WORKSPACE -> TaskType.RESET_WORKSPACE
    ExternalActionContract.OPERATION_START_CLONE_USER -> TaskType.START_CLONE_USER
    ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER -> TaskType.SWITCH_TO_CLONE_USER
    ExternalActionContract.OPERATION_STOP_CLONE_USER -> TaskType.STOP_CLONE_USER
    else -> error("不支持的任务操作：$operation")
}

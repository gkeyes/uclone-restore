package com.uclone.restore.external

import com.uclone.restore.model.TaskStatus

internal fun TaskStatus.toExternalStatus(): String = when (this) {
    TaskStatus.SUCCESS -> ExternalActionContract.STATUS_SUCCESS
    TaskStatus.SUCCESS_WITH_WARNINGS -> ExternalActionContract.STATUS_SUCCESS_WITH_WARNINGS
    TaskStatus.INTERRUPTED -> ExternalActionContract.STATUS_INTERRUPTED
    TaskStatus.ACCEPTED,
    TaskStatus.RUNNING,
    TaskStatus.AUTO_ROLLING_BACK,
    TaskStatus.ROLLED_BACK,
    TaskStatus.FAILED,
    TaskStatus.FAILED_FATAL,
    -> ExternalActionContract.STATUS_FAILED
}

package com.uclone.restore.external

import com.uclone.restore.model.TaskStatus

internal fun TaskStatus.toExternalStatus(): String =
    if (isSuccessful) {
        ExternalActionContract.STATUS_SUCCESS
    } else {
        ExternalActionContract.STATUS_FAILED
    }

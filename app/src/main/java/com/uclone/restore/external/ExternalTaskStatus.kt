package com.uclone.restore.external

import com.uclone.restore.model.TaskStatus

internal fun TaskStatus.toExternalStatus(): String = when (this) {
    TaskStatus.ACCEPTED -> ExternalActionContract.STATUS_ACCEPTED
    TaskStatus.RUNNING,
    TaskStatus.AUTO_ROLLING_BACK,
    -> ExternalActionContract.STATUS_RUNNING
    TaskStatus.ROLLED_BACK,
    TaskStatus.FAILED,
    TaskStatus.FAILED_FATAL,
    -> ExternalActionContract.STATUS_FAILED
    TaskStatus.RECOVERY_REQUIRED -> ExternalActionContract.STATUS_RECOVERY_REQUIRED
    TaskStatus.SUCCESS -> ExternalActionContract.STATUS_SUCCESS
    TaskStatus.SUCCESS_WITH_WARNINGS -> ExternalActionContract.STATUS_SUCCESS_WITH_WARNINGS
    TaskStatus.INTERRUPTED -> ExternalActionContract.STATUS_INTERRUPTED
}

internal fun TaskStatus.toExternalRequestStage(): ExternalRequestStage = when (this) {
    TaskStatus.ACCEPTED -> ExternalRequestStage.ACCEPTED
    TaskStatus.RUNNING,
    TaskStatus.AUTO_ROLLING_BACK,
    -> ExternalRequestStage.RUNNING
    TaskStatus.ROLLED_BACK,
    TaskStatus.FAILED,
    TaskStatus.FAILED_FATAL,
    -> ExternalRequestStage.FAILED
    TaskStatus.RECOVERY_REQUIRED -> ExternalRequestStage.RECOVERY_REQUIRED
    TaskStatus.SUCCESS -> ExternalRequestStage.SUCCESS
    TaskStatus.SUCCESS_WITH_WARNINGS -> ExternalRequestStage.SUCCESS_WITH_WARNINGS
    TaskStatus.INTERRUPTED -> ExternalRequestStage.INTERRUPTED
}

internal fun String.toExternalRequestStage(): ExternalRequestStage? = when (this) {
    ExternalActionContract.STATUS_SERVICE_RECEIVED -> ExternalRequestStage.SERVICE_RECEIVED
    ExternalActionContract.STATUS_ACCEPTED -> ExternalRequestStage.ACCEPTED
    ExternalActionContract.STATUS_RUNNING,
    ExternalActionContract.STATUS_ALREADY_RUNNING,
    -> ExternalRequestStage.RUNNING
    ExternalActionContract.STATUS_SUCCESS -> ExternalRequestStage.SUCCESS
    ExternalActionContract.STATUS_SUCCESS_WITH_WARNINGS -> ExternalRequestStage.SUCCESS_WITH_WARNINGS
    ExternalActionContract.STATUS_FAILED -> ExternalRequestStage.FAILED
    ExternalActionContract.STATUS_INTERRUPTED -> ExternalRequestStage.INTERRUPTED
    ExternalActionContract.STATUS_STILL_RUNNING -> ExternalRequestStage.STILL_RUNNING
    ExternalActionContract.STATUS_ORPHANED -> ExternalRequestStage.ORPHANED
    ExternalActionContract.STATUS_FAILED_PROCESS_DIED -> ExternalRequestStage.FAILED_PROCESS_DIED
    ExternalActionContract.STATUS_REJECTED -> ExternalRequestStage.REJECTED
    ExternalActionContract.STATUS_RECOVERY_REQUIRED -> ExternalRequestStage.RECOVERY_REQUIRED
    ExternalActionContract.STATUS_BUSY -> ExternalRequestStage.BUSY
    else -> null
}

internal fun ExternalRequestStage.toExternalStatus(): String? = when (this) {
    ExternalRequestStage.MENU_READY,
    ExternalRequestStage.SENT,
    -> null
    ExternalRequestStage.SERVICE_RECEIVED -> ExternalActionContract.STATUS_SERVICE_RECEIVED
    ExternalRequestStage.ACCEPTED -> ExternalActionContract.STATUS_ACCEPTED
    ExternalRequestStage.RUNNING -> ExternalActionContract.STATUS_RUNNING
    ExternalRequestStage.SUCCESS -> ExternalActionContract.STATUS_SUCCESS
    ExternalRequestStage.SUCCESS_WITH_WARNINGS -> ExternalActionContract.STATUS_SUCCESS_WITH_WARNINGS
    ExternalRequestStage.BUSY -> ExternalActionContract.STATUS_BUSY
    ExternalRequestStage.REJECTED -> ExternalActionContract.STATUS_REJECTED
    ExternalRequestStage.FAILED -> ExternalActionContract.STATUS_FAILED
    ExternalRequestStage.INTERRUPTED -> ExternalActionContract.STATUS_INTERRUPTED
    ExternalRequestStage.STILL_RUNNING -> ExternalActionContract.STATUS_STILL_RUNNING
    ExternalRequestStage.ORPHANED -> ExternalActionContract.STATUS_ORPHANED
    ExternalRequestStage.FAILED_PROCESS_DIED -> ExternalActionContract.STATUS_FAILED_PROCESS_DIED
    ExternalRequestStage.RECOVERY_REQUIRED -> ExternalActionContract.STATUS_RECOVERY_REQUIRED
}

internal val ExternalRequestStage.isTerminalProtocolStage: Boolean
    get() = when (this) {
        ExternalRequestStage.MENU_READY,
        ExternalRequestStage.SENT,
        ExternalRequestStage.SERVICE_RECEIVED,
        ExternalRequestStage.ACCEPTED,
        ExternalRequestStage.RUNNING,
        ExternalRequestStage.STILL_RUNNING,
        ExternalRequestStage.ORPHANED,
        -> false
        ExternalRequestStage.SUCCESS,
        ExternalRequestStage.SUCCESS_WITH_WARNINGS,
        ExternalRequestStage.BUSY,
        ExternalRequestStage.REJECTED,
        ExternalRequestStage.FAILED,
        ExternalRequestStage.INTERRUPTED,
        ExternalRequestStage.FAILED_PROCESS_DIED,
        ExternalRequestStage.RECOVERY_REQUIRED,
        -> true
    }

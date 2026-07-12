package com.uclone.restore.external

import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.TaskAudit
import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskSubmissionResult

internal fun admitExternalTask(
    coordinator: TaskCoordinator,
    request: ExternalActionRequest,
    requestStore: ExternalRequestStore? = null,
): ExternalTaskAdmission {
    requestStore?.terminal(request.requestId)?.let { terminal ->
        return ExternalTaskAdmission.Rejected(
            record = null,
            status = requireNotNull(terminal.stage.toExternalStatus()),
            message = "该 requestId 已有终态记录，未重复执行",
        )
    }
    return when (
        val submission = coordinator.accept(
        request.requestId,
        taskTypeForOperation(request.operation),
        request.packageName,
        TaskAudit(
            source = request.source,
            backupKind = when (request.operation) {
                ExternalActionContract.OPERATION_DELETE_SNAPSHOT -> BackupKind.ACTIVE_SNAPSHOT
                ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP -> BackupKind.PASSIVE_BACKUP
                ExternalActionContract.OPERATION_DELETE_CLONE_ROLLBACK -> BackupKind.CLONE_ROLLBACK
                ExternalActionContract.OPERATION_RESET_WORKSPACE -> BackupKind.WORKSPACE
                else -> null
            },
            backupId = request.rollbackId,
        ),
        )
    ) {
        is TaskSubmissionResult.Accepted -> ExternalTaskAdmission.Accepted(submission.record)
        is TaskSubmissionResult.AlreadyRunning -> ExternalTaskAdmission.Rejected(
            record = submission.record,
            status = ExternalActionContract.STATUS_ALREADY_RUNNING,
            message = "同一任务已在执行",
        )
        is TaskSubmissionResult.AlreadyCompleted -> ExternalTaskAdmission.Rejected(
            record = submission.record,
            status = submission.record.status.toExternalStatus(),
            message = "该 requestId 已有终态记录，未重复执行",
        )
        is TaskSubmissionResult.Busy -> ExternalTaskAdmission.Rejected(
            record = submission.record,
            status = ExternalActionContract.STATUS_BUSY,
            message = "已有任务正在执行",
        )
        is TaskSubmissionResult.RecoveryRequired -> ExternalTaskAdmission.Rejected(
            record = null,
            status = ExternalActionContract.STATUS_RECOVERY_REQUIRED,
            message = submission.message,
        )
    }
}

internal sealed interface ExternalTaskAdmission {
    val record: TaskRecord?

    data class Accepted(override val record: TaskRecord) : ExternalTaskAdmission

    data class Rejected(
        override val record: TaskRecord?,
        val status: String,
        val message: String,
    ) : ExternalTaskAdmission
}

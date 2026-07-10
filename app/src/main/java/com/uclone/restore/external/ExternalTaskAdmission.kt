package com.uclone.restore.external

import com.uclone.restore.model.TaskRecord
import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskSubmissionResult

internal fun admitExternalTask(
    coordinator: TaskCoordinator,
    request: ExternalActionRequest,
): ExternalTaskAdmission = when (
    val submission = coordinator.accept(
        request.requestId,
        taskTypeForOperation(request.operation),
        request.packageName,
    )
) {
    is TaskSubmissionResult.Accepted -> ExternalTaskAdmission.Accepted(submission.record)
    is TaskSubmissionResult.AlreadyRunning -> ExternalTaskAdmission.Rejected(
        record = submission.record,
        status = ExternalActionContract.STATUS_ALREADY_RUNNING,
        message = "同一任务已在执行",
    )
    is TaskSubmissionResult.Busy -> ExternalTaskAdmission.Rejected(
        record = submission.record,
        status = ExternalActionContract.STATUS_BUSY,
        message = "已有任务正在执行",
    )
}

internal sealed interface ExternalTaskAdmission {
    val record: TaskRecord

    data class Accepted(override val record: TaskRecord) : ExternalTaskAdmission

    data class Rejected(
        override val record: TaskRecord,
        val status: String,
        val message: String,
    ) : ExternalTaskAdmission
}

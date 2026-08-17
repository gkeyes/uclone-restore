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
    is TaskSubmissionResult.ResourceBusy -> ExternalTaskAdmission.Rejected(
        record = null,
        status = ExternalActionContract.STATUS_BUSY,
        message = "跨用户运行会话正在占用分身资源",
    )
}

internal sealed interface ExternalTaskAdmission {
    data class Accepted(val record: TaskRecord) : ExternalTaskAdmission

    data class Rejected(
        val record: TaskRecord?,
        val status: String,
        val message: String,
    ) : ExternalTaskAdmission
}

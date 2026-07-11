package com.uclone.restore.external

import com.uclone.restore.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalTaskStatusTest {
    @Test
    fun successfulTasksPreserveWarningStatus() {
        assertEquals(ExternalActionContract.STATUS_SUCCESS, TaskStatus.SUCCESS.toExternalStatus())
        assertEquals(
            ExternalActionContract.STATUS_SUCCESS_WITH_WARNINGS,
            TaskStatus.SUCCESS_WITH_WARNINGS.toExternalStatus(),
        )
        assertEquals(ExternalActionContract.STATUS_FAILED, TaskStatus.ROLLED_BACK.toExternalStatus())
        assertEquals(ExternalActionContract.STATUS_FAILED, TaskStatus.FAILED_FATAL.toExternalStatus())
    }
}

package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.root.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskOutcomeTest {
    @Test
    fun automaticRollbackHasDedicatedTerminalStatus() {
        val result = ShellResult(90, "AUTO_ROLLBACK_SUCCESS originalExit=58", "")

        assertEquals(TaskStatus.ROLLED_BACK, TaskOutcome.status(result))
        assertEquals("操作失败，已自动恢复操作前数据", TaskOutcome.failureMessage(TaskStatus.ROLLED_BACK))
    }

    @Test
    fun failedAutomaticRollbackIsFatal() {
        val result = ShellResult(91, "", "AUTO_ROLLBACK_FAILED originalExit=58")

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
    }

    @Test
    fun forcedTimeoutTerminationIsFatalBecauseRollbackCannotBeConfirmed() {
        val result = ShellResult(124, "", "Timeout termination required SIGKILL after rollback grace period")

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
    }

    @Test
    fun permissionWarningsHaveExplicitPartialSuccessStatus() {
        val result = ShellResult(0, "WARN_APPOPS_FAILED:CAMERA:allow", "")

        assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
    }

    @Test
    fun appOpsResetAndPersistenceWarningsHaveExplicitPartialSuccessStatus() {
        listOf("WARN_APPOPS_RESET_FAILED", "WARN_APPOPS_WRITE_SETTINGS_FAILED").forEach { warning ->
            val result = ShellResult(0, warning, "")

            assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
        }
    }

    @Test
    fun cloneAutoStopWarningsHaveExplicitPartialSuccessStatus() {
        listOf("WARN_STOP_CLONE_REQUEST_FAILED", "WARN_STOP_CLONE_PENDING").forEach { warning ->
            val result = ShellResult(0, warning, "")

            assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
        }
    }
}

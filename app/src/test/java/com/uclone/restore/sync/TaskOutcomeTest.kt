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
    fun fatalInstallSyncFailureStillReportsThatThePackageWasPreserved() {
        val output = "AUTO_ROLLBACK_FAILED originalExit=58\nINSTALL_PACKAGE_PRESERVED targetUser=10"
        val result = ShellResult(91, output, "")

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
        assertEquals(
            "App 已安装到另一侧，但数据同步和自动回滚未完成；安装结果已保留，请勿启动目标 App，并查看日志",
            TaskOutcome.failureMessage(TaskStatus.FAILED_FATAL, output),
        )
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

    @Test
    fun installedAppIsPreservedAsPartialSuccessWhenDataSyncFails() {
        val result = ShellResult(
            0,
            "WARN_INSTALL_SYNC_FAILED:targetUser=10:exit=54\nINSTALL_PARTIAL_SUCCESS targetUser=10",
            "",
        )

        assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
    }

    @Test
    fun permissionCaptureCompatibilityWarningsNeverBecomeAFileTransactionFailure() {
        val result = ShellResult(0, "WARN_PERMISSION_CAPTURE_APPOPS_COMMAND:user=10", "")

        assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
    }
}

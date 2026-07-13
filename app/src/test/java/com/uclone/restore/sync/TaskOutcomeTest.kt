package com.uclone.restore.sync

import com.uclone.restore.model.TaskOutcomeCode
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
    fun forcedCloneUpdateRollbackExplainsThatMainDataWasNotRestored() {
        val result = ShellResult(
            90,
            "AUTO_ROLLBACK_SUCCESS originalExit=58",
            "ERR_FORCE_UPDATE_CLONE_DATA:exit=90",
        )

        assertEquals(
            "分数据更新失败，已回滚分系统；主数据未还原，当前仍保持分身态",
            TaskOutcome.failureMessage(TaskStatus.ROLLED_BACK, result),
        )
    }

    @Test
    fun mainRestoreRollbackExplainsThatCloneDataWasAlreadyUpdated() {
        val result = ShellResult(
            90,
            "FORCE_UPDATE_CLONE_DATA_DONE=1\nAUTO_ROLLBACK_SUCCESS originalExit=58",
            "ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE:exit=90",
        )

        assertEquals(
            "分数据已更新，但主数据还原失败；主系统已回滚到还原前的分身态",
            TaskOutcome.failureMessage(TaskStatus.ROLLED_BACK, result),
        )
    }

    @Test
    fun failedAutomaticRollbackIsFatal() {
        val result = ShellResult(91, "", "AUTO_ROLLBACK_FAILED originalExit=58")

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
    }

    @Test
    fun failedCompositeRollbackIdentifiesTheUnsafeUser() {
        val cloneUpdate = ShellResult(
            91,
            "AUTO_ROLLBACK_FAILED originalExit=58",
            "ERR_FORCE_UPDATE_CLONE_DATA:exit=91",
        )
        val mainRestore = ShellResult(
            91,
            "FORCE_UPDATE_CLONE_DATA_DONE=1\nAUTO_ROLLBACK_FAILED originalExit=58",
            "ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE:exit=91",
        )

        assertEquals(
            "分数据更新与自动回滚均失败；分身目标 App 保持冻结，主数据未还原",
            TaskOutcome.failureMessage(TaskStatus.FAILED_FATAL, cloneUpdate),
        )
        assertEquals(
            "分数据已更新，但主数据还原与自动回滚均失败；主系统 App 保持冻结",
            TaskOutcome.failureMessage(TaskStatus.FAILED_FATAL, mainRestore),
        )
    }

    @Test
    fun compositeRecoveryRequiredIdentifiesWhichUserRemainsFrozen() {
        val cloneUpdate = ShellResult(
            92,
            "RECOVERY_REQUIRED:request=req package=com.example.app",
            "ERR_FORCE_UPDATE_CLONE_DATA:exit=92",
        )
        val mainRestore = ShellResult(
            92,
            "FORCE_UPDATE_CLONE_DATA_DONE=1\nRECOVERY_REQUIRED:request=req package=com.example.app",
            "ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE:exit=92",
        )

        assertEquals(
            "分数据更新事务未完成；分身目标 App 保持冻结，主数据未还原",
            TaskOutcome.failureMessage(TaskStatus.RECOVERY_REQUIRED, cloneUpdate),
        )
        assertEquals(
            "分数据已更新，但主数据还原事务未完成；主系统 App 保持冻结",
            TaskOutcome.failureMessage(TaskStatus.RECOVERY_REQUIRED, mainRestore),
        )
    }

    @Test
    fun failedAutomaticRollbackExitRemainsFatalAfterSuccessfulInstall() {
        val result = ShellResult(
            91,
            """
                INSTALL_VERIFIED:user=10 package:com.example.app uid:1010001
                WARN_INSTALL_SYNC_FAILED:targetUser=10:exit=91
                INSTALL_PARTIAL_FATAL targetUser=10
            """.trimIndent(),
            "",
        )

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
    }

    @Test
    fun installWarningCannotDowngradeFailedAutomaticRollback() {
        val result = ShellResult(
            0,
            "WARN_INSTALL_SYNC_FAILED:targetUser=10:exit=91",
            "AUTO_ROLLBACK_FAILED originalExit=58",
        )

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
    }

    @Test
    fun successfulInstallWithRecoveredSyncFailureRemainsPartialSuccess() {
        val result = ShellResult(
            0,
            """
                WARN_INSTALL_SYNC_FAILED:targetUser=10:exit=90
                AUTO_ROLLBACK_SUCCESS originalExit=58
                INSTALL_PARTIAL_SUCCESS targetUser=10
            """.trimIndent(),
            "",
        )

        assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
    }

    @Test
    fun forcedTimeoutTerminationIsFatalBecauseRollbackCannotBeConfirmed() {
        val result = ShellResult(124, "", "Timeout termination required SIGKILL after rollback grace period")

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
    }

    @Test
    fun unverifiedRootTreeTerminationIsFatalAndHasAStableOutcomeCode() {
        val result = ShellResult(124, "", "ROOT_TREE_TERMINATION_UNVERIFIED")

        assertEquals(TaskStatus.FAILED_FATAL, TaskOutcome.status(result))
        assertEquals(
            TaskOutcomeCode.ROOT_TERMINATION_UNVERIFIED,
            TaskOutcome.code(result, TaskStatus.FAILED_FATAL),
        )
        assertEquals(
            "Root 子进程是否终止无法确认；请勿启动相关 App，并先执行事务恢复检查",
            TaskOutcome.failureMessage(TaskStatus.FAILED_FATAL, result),
        )
    }

    @Test
    fun interruptedRootCommandHasDedicatedTerminalStatus() {
        val result = ShellResult(130, "", "Command interrupted")

        assertEquals(TaskStatus.INTERRUPTED, TaskOutcome.status(result))
        assertEquals("任务已中断", TaskOutcome.failureMessage(TaskStatus.INTERRUPTED))
    }

    @Test
    fun permissionWarningsHaveExplicitPartialSuccessStatus() {
        val result = ShellResult(0, "WARN_APPOPS_FAILED:CAMERA:allow", "")

        assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
    }

    @Test
    fun skippedSourcePermissionCaptureIsPartialSuccessAfterDataCompletes() {
        val result = ShellResult(
            0,
            "RESTORED:/data/user/0/com.example.app ITEMS=10\nWARN_SOURCE_PERMISSION_CAPTURE_SKIPPED:10",
            "",
        )

        assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(result))
    }

    @Test
    fun restoredDataWithUnknownStateIsPartialSuccess() {
        val result = ShellResult(
            0,
            "RESTORE_SUMMARY: restoredParts=3 restoredItems=10 backupParts=3\n" +
                "WARN_DATA_STATE_REMAINS_UNKNOWN:/data/adb/uclone/rollback/com.example.app/legacy",
            "",
        )

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
    fun explicitlyAllowedRestoreCompatibilityRisksRemainVisible() {
        listOf(
            "WARN_VERSION_MISMATCH_ALLOWED:expected=10:actual=11",
            "WARN_LEGACY_PACKAGE_IDENTITY_ALLOWED:/data/adb/uclone/rollback/app/old",
        ).forEach { warning ->
            assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, TaskOutcome.status(ShellResult(0, warning, "")))
        }
    }

    @Test
    fun markerTextEmbeddedInAnOrdinaryLogValueDoesNotChangeTaskStatus() {
        val result = ShellResult(
            0,
            "LOG_PATH=/data/adb/uclone/logs/WARN_APPOPS_FAILED:old.log",
            "",
        )

        assertEquals(TaskStatus.SUCCESS, TaskOutcome.status(result))
    }

    @Test
    fun processControlWordsEmbeddedInOrdinaryTextDoNotChangeTaskStatus() {
        val result = ShellResult(
            0,
            "LOG_PATH=/data/adb/uclone/ROOT_TREE_TERMINATION_UNVERIFIED/Command interrupted.log",
            "detail=Timeout termination required SIGKILL was not emitted as an event",
        )

        assertEquals(TaskStatus.SUCCESS, TaskOutcome.status(result))
    }
}

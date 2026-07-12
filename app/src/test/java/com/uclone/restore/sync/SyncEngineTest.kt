package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.root.ShellOutput
import com.uclone.restore.root.ShellStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineTest {
    @Test
    fun loadWorkspaceIndex_readsEveryWorkspaceSectionWithOneRootCall() = runBlocking {
        val shell = WorkspaceIndexShell()
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val index = engine.loadWorkspaceIndex(UCloneSettings(rootDir = "/data/adb/uclone"))

        assertEquals(1, shell.commands.size)
        assertTrue("ROOT='/data/adb/uclone'" in shell.commands.single())
        assertTrue("SNAPSHOT_ROOT=" in shell.commands.single())
        assertTrue("SWITCH_ROOT=" in shell.commands.single())
        assertTrue("ROLLBACK_ROOT=" in shell.commands.single())
        assertTrue("CLONE_ROLLBACK_ROOT=" in shell.commands.single())
        assertEquals(1_700_000_000_000, index.snapshots.getValue("com.example.app").updatedAt)
        assertEquals(2048L, index.snapshots.getValue("com.example.app").sizeKb)
        assertEquals("rollback-2", index.switchMarkers["com.example.app"])
        assertEquals(listOf("rollback-1", "rollback-2"), index.rollbackIds("com.example.app"))
        assertEquals("rollback-2", index.restoreBackups.single().rollbackId)
        assertTrue(index.restoreBackups.single().isActiveSwitchBackup)
        assertEquals("latest", index.cloneRollbackBackups.single().rollbackId)
        assertTrue(index.cloneRollbackBackups.single().isCloneRollback)
    }

    @Test
    fun loadWorkspaceIndex_doesNotReplaceStateWithAnEmptyIndexWhenRootScanFails() = runBlocking {
        val shell = object : RootShellExecutor {
            override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult =
                ShellResult(exitCode = 74, stdout = "", stderr = "workspace scan failed")
        }
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val error = runCatching { engine.loadWorkspaceIndex(UCloneSettings()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue("workspace scan failed" in error?.message.orEmpty())
    }

    @Test
    fun hostTimeoutIsDisabledForEveryTransactionalMutationTask() {
        val transactional = setOf(
            TaskType.RESTORE_SNAPSHOT_TO_MAIN,
            TaskType.ROLLBACK_MAIN_DATA,
            TaskType.RESTORE_FROM_CLONE_LATEST,
            TaskType.SWITCH_TO_CLONE_STATE,
            TaskType.PUSH_MAIN_TO_CLONE,
            TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
            TaskType.RESTORE_SWITCH_MAIN_STATE,
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER,
        )

        transactional.forEach { assertEquals(0, taskHostTimeoutSeconds(it), it.name) }
        assertEquals(900, taskHostTimeoutSeconds(TaskType.CAPTURE_SNAPSHOT_FROM_CLONE))
        assertEquals(900, taskHostTimeoutSeconds(TaskType.PROBE_CLONE_CE))
    }

    @Test
    fun restoreFromCloneLatest_doesNotRestoreOldActiveSnapshotWhenCaptureFails() = runBlocking {
        val shell = FakeRootShell()
        val settings = UCloneSettings(rootDir = "/data/adb/uclone")
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.restoreFromCloneLatest(
            packageName = "com.example.app",
            rule = AppRule(packageName = "com.example.app"),
            settings = settings,
            report = {},
        )

        assertEquals(TaskType.RESTORE_FROM_CLONE_LATEST, result.type)
        assertEquals(TaskStatus.FAILED, result.status)
        val command = shell.commands.single()
        val captureGuard = command.indexOf(") || exit ${'$'}?")
        val restoreStep = command.indexOf("COMPOSITE_STEP=RESTORE_SNAPSHOT_TO_MAIN")
        assertTrue(captureGuard in 0 until restoreStep)
        assertTrue(shell.commands.any { "START_LOCAL=" in it })
        assertTrue(shell.commands.any { "END_LOCAL=" in it && "DURATION_MS=" in it })
    }

    @Test
    fun restoreFromCloneLatest_keepsCompositeTypeAndRequestAcrossBothSteps() = runBlocking {
        val shell = CompositeSuccessShell()
        val repository = TaskLogStore(shell)
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = repository,
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.restoreFromCloneLatest(
            packageName = "com.example.app",
            rule = AppRule(packageName = "com.example.app"),
            settings = UCloneSettings(rootDir = "/data/adb/uclone"),
            report = {},
            requestId = "composite-request",
        )

        assertEquals(TaskStatus.SUCCESS, result.status)
        assertEquals(TaskType.RESTORE_FROM_CLONE_LATEST, result.type)
        assertEquals("composite-request", result.requestId)
        assertEquals(1, repository.all().size)
        assertEquals(TaskType.RESTORE_FROM_CLONE_LATEST, repository.all().single().type)
        assertTrue(shell.captureExecuted)
        assertTrue(shell.restoreExecuted)
    }

    @Test
    fun restoreSwitchMainState_usesForcedCloneUpdateInsideOneTask() = runBlocking {
        val shell = FakeRootShell()
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.restoreSwitchMainState(
            packageName = "com.example.app",
            rollbackId = "main-state-1",
            rule = AppRule(packageName = "com.example.app"),
            settings = UCloneSettings(forceUpdateCloneDataBeforeMainRestore = true),
            report = {},
            requestId = "force-update-request",
        )

        assertEquals(TaskType.RESTORE_SWITCH_MAIN_STATE, result.type)
        assertEquals("force-update-request", result.requestId)
        assertEquals(1, shell.commands.size)
        assertTrue("COMPOSITE_STEP=FORCE_UPDATE_CLONE_DATA" in shell.commands.single())
        assertTrue("COMPOSITE_STEP=RESTORE_SWITCH_MAIN_STATE" in shell.commands.single())
    }

    @Test
    fun switchToCloneState_recordsRequestAndScriptMetrics() = runBlocking {
        val shell = MetricsRootShell()
        val settings = UCloneSettings(rootDir = "/data/adb/uclone")
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.switchToCloneState(
            packageName = "com.example.app",
            rule = AppRule(packageName = "com.example.app"),
            settings = settings,
            report = {},
            requestId = "module-request-42",
        )

        assertEquals("module-request-42", result.requestId)
        assertEquals(1, result.metrics.rootProcessStarts)
        assertEquals(1, result.metrics.rootCommandCount)
        assertEquals(42, result.metrics.scannedFiles)
        assertEquals(40, result.metrics.copiedFiles)
        assertEquals(4096, result.metrics.copiedBytes)
        assertEquals(8192, result.metrics.peakTemporaryBytes)
        assertEquals(500, result.metrics.targetDowntimeMs)
        assertEquals(TaskStage.RESTORE_DATA, result.metrics.stages.single().stage)
        assertEquals(250, result.metrics.stages.single().durationMs)
    }

    @Test
    fun automaticRollbackResultIsPersistedAsRolledBack() = runBlocking {
        val shell = RollbackResultShell("AUTO_ROLLBACK_SUCCESS originalExit=58", 90)
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.restoreSnapshot("com.example.app", UCloneSettings(), {}, "rollback-request")

        assertEquals(TaskStatus.ROLLED_BACK, result.status)
        assertEquals("操作失败，已自动恢复操作前数据", result.message)
        assertEquals("rollback-request", result.requestId)
    }

    @Test
    fun failedAutomaticRollbackIsPersistedAsFatal() = runBlocking {
        val shell = RollbackResultShell("AUTO_ROLLBACK_FAILED originalExit=58", 91)
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.restoreSnapshot("com.example.app", UCloneSettings(), {}, "fatal-request")

        assertEquals(TaskStatus.FAILED_FATAL, result.status)
        assertEquals("操作失败且自动回滚失败，请勿启动目标 App，并查看日志", result.message)
    }

    @Test
    fun sharedUidGateRejectionExplainsWhyNoDataWasChanged() = runBlocking {
        val shell = RollbackResultShell(
            "ERR_GATE_SHARED_UID:user=0 uid=10123 packages=com.example.app com.example.peer",
            77,
        )
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.restoreSnapshot("com.example.app", UCloneSettings(), {}, "shared-uid-request")

        assertEquals(TaskStatus.FAILED, result.status)
        assertEquals("此 App 与其他包共享 UID，为避免并发访问数据，任务未执行", result.message)
    }

    @Test
    fun stageBeginIsPublishedImmediatelyWhileRootCommandRuns() = runBlocking {
        val shell = StageStreamingShell()
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )
        val progress = mutableListOf<com.uclone.restore.model.TaskProgress>()

        engine.restoreSnapshot("com.example.app", UCloneSettings(), progress::add, "stage-request")

        assertTrue(progress.any { it.task?.currentStage == TaskStage.RESTORE_DATA })
    }

    @Test
    fun truncatedSuccessfulOutputIsVisibleAsWarning() = runBlocking {
        val shell = TruncatedOutputShell()
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.restoreSnapshot("com.example.app", UCloneSettings(), {}, "truncated-request")

        assertEquals(TaskStatus.SUCCESS_WITH_WARNINGS, result.status)
        assertTrue("完整内容请查看任务日志" in result.message)
    }

    @Test
    fun cloneCredentialUsesProtectedStdinInsteadOfRootCommandArguments() = runBlocking {
        val shell = ProtectedInputShell()
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        engine.unlockCloneWithCredential(
            settings = UCloneSettings(cloneUnlockCredential = "654321"),
            report = {},
        )

        assertEquals("654321\n", shell.standardInput)
        assertFalse(shell.command.orEmpty().contains("654321"))
    }

    @Test
    fun forceStopFailureTakesPriorityOverNothingCopied() = runBlocking {
        val shell = ForceStopFailureShell()
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = TestSigningIdentityProvider,
        )

        val result = engine.captureSnapshot(
            packageName = "com.example.app",
            rule = AppRule(packageName = "com.example.app"),
            settings = UCloneSettings(rootDir = "/data/adb/uclone"),
            report = {},
        )

        assertEquals(TaskStatus.FAILED, result.status)
        assertEquals("无法停止分身 App，未读取或写入数据", result.message)
    }

    @Test
    fun interruptedTransactionRecoveryBindsSigningIdentityToTheRealPackage() = runBlocking {
        var identityPackage: String? = null
        val shell = object : RootShellExecutor {
            override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult =
                ShellResult(0, "TRANSACTION_RECOVERY_ABORTED=original-request", "")
        }
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
            signingIdentityProvider = PackageSigningIdentityProvider { packageName ->
                identityPackage = packageName
                "0".repeat(64)
            },
        )

        val result = engine.recoverInterruptedTransaction(
            transactionRequestId = "original-request",
            packageName = "com.example.app",
            settings = UCloneSettings(),
            report = {},
            requestId = "recovery-request",
        )

        assertEquals("com.example.app", identityPackage)
        assertEquals("com.example.app", result.packageName)
        assertEquals(TaskType.RECOVER_INTERRUPTED_TRANSACTION, result.type)
    }

    private class FakeRootShell : RootShellExecutor {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult {
            commands += command
            return when {
                command == "id" -> ShellResult(0, "uid=0(root)", "")
                "CAPTURE_REQUIRE_CE" in command -> ShellResult(
                    44,
                    "",
                    "ERR_CAPTURE_CE_MISSING:10",
                )
                else -> ShellResult(0, "", "")
            }
        }
    }

    private class WorkspaceIndexShell : RootShellExecutor {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult {
            commands += command
            return ShellResult(
                exitCode = 0,
                stdout = listOf(
                    "SNAPSHOT\tcom.example.app\t1700000000\t2048",
                    "MAIN_ROLLBACK\tcom.example.app\trollback-1\t1699999900\t1024\tolder",
                    "MAIN_ROLLBACK\tcom.example.app\trollback-2\t1700000100\t4096\tnewer",
                    "SWITCH\tcom.example.app\trollback-2",
                    "CLONE_ROLLBACK\tcom.example.app\tlatest\t1700000200\t8192\tclone backup",
                ).joinToString("\n"),
                stderr = "",
            )
        }
    }

    private class MetricsRootShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = when {
            command == "id" -> ShellResult(0, "uid=0(root)", "")
            "SOURCE_KIND='switch_temp'" in command -> ShellResult(
                exitCode = 0,
                stdout = """
                    UCLONE_METRIC:stage=RESTORE_DATA started_at=1000 finished_at=1250
                    UCLONE_METRIC:scanned_files=42 copied_files=40 copied_bytes=4096 peak_temporary_bytes=8192 target_downtime_ms=500
                    RESTORE_SUMMARY: restoredParts=2 restoredItems=40 backupParts=2
                """.trimIndent(),
                stderr = "",
            )
            else -> ShellResult(0, "", "")
        }
    }

    private class RollbackResultShell(
        private val marker: String,
        private val exitCode: Int,
    ) : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = when {
            command == "id" -> ShellResult(0, "uid=0(root)", "")
            "SOURCE_KIND='active'" in command -> ShellResult(exitCode, marker, "")
            else -> ShellResult(0, "", "")
        }
    }

    private class CompositeSuccessShell : RootShellExecutor {
        var captureExecuted = false
        var restoreExecuted = false

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = when {
            "CAPTURE_REQUIRE_CE" in command && "SOURCE_KIND='active'" in command -> {
                captureExecuted = true
                restoreExecuted = true
                ShellResult(
                    0,
                    "SNAPSHOT_READY=/data/adb/uclone/snapshots/com.example.app/active\nRESTORE_SUMMARY: restoredParts=2 restoredItems=10 backupParts=2",
                    "",
                )
            }
            else -> ShellResult(0, "", "")
        }
    }

    private class StageStreamingShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult =
            ShellResult(0, "", "")

        override suspend fun execStreaming(
            command: String,
            timeoutSeconds: Long,
            onOutput: (ShellOutput) -> Unit,
        ): ShellResult {
            onOutput(ShellOutput(ShellStream.STDOUT, "ROOT=uid=0(root)"))
            onOutput(ShellOutput(ShellStream.STDOUT, "UCLONE_STAGE_BEGIN:RESTORE_DATA"))
            return ShellResult(0, "RESTORE_SUMMARY: restoredParts=1 restoredItems=1 backupParts=1", "")
        }
    }

    private class TruncatedOutputShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(
            exitCode = 0,
            stdout = "RESTORE_SUMMARY: restoredParts=1 restoredItems=1 backupParts=1",
            stderr = "",
            outputTruncated = true,
        )
    }

    private class ProtectedInputShell : RootShellExecutor {
        var command: String? = null
        var standardInput: String? = null

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")

        override suspend fun execStreamingWithInput(
            command: String,
            standardInput: String,
            timeoutSeconds: Long,
            onOutput: (ShellOutput) -> Unit,
        ): ShellResult {
            this.command = command
            this.standardInput = standardInput
            return ShellResult(0, "USER10_CE_READY=1", "")
        }
    }

    private class ForceStopFailureShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = when (command) {
            "id" -> ShellResult(0, "uid=0(root)", "")
            else -> ShellResult(
                exitCode = 44,
                stdout = "",
                stderr = "ERR_FORCE_STOP_FAILED:10:com.example.app\nERR_NOTHING_COPIED: no source data",
            )
        }
    }
}

private val TestSigningIdentityProvider = PackageSigningIdentityProvider {
    "0".repeat(64)
}

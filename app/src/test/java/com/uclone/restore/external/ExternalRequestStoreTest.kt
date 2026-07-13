package com.uclone.restore.external

import com.uclone.restore.model.TaskAudit
import com.uclone.restore.model.TaskMetrics
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.sync.TaskCoordinator
import com.uclone.restore.sync.TaskLogStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ExternalRequestStoreTest {
    @Test
    fun eventsSurviveStoreRecreation() {
        val file = Files.createTempDirectory("uclone-external-events")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        ExternalRequestStore(file).record(event("request-1", ExternalRequestStage.SERVICE_RECEIVED))

        val restored = ExternalRequestStore(file).all().single()

        assertEquals("request-1", restored.requestId)
        assertEquals(ExternalRequestStage.SERVICE_RECEIVED, restored.stage)
    }

    @Test
    fun terminalRequestIdsSurviveDiagnosticHistoryEvictionAndStoreRecreation() {
        val file = Files.createTempDirectory("uclone-external-dedupe")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file, maxEvents = 2)
        store.record(event("completed-request", ExternalRequestStage.SUCCESS))
        store.record(event("new-request-1", ExternalRequestStage.SERVICE_RECEIVED))
        store.record(event("new-request-2", ExternalRequestStage.SERVICE_RECEIVED))
        store.record(event("new-request-3", ExternalRequestStage.SERVICE_RECEIVED))

        val restored = ExternalRequestStore(file, maxEvents = 2)

        assertEquals(
            ExternalRequestStage.SUCCESS,
            restored.terminal("completed-request")?.stage,
        )
        assertEquals(
            listOf("new-request-3", "new-request-2"),
            restored.all().map(ExternalRequestEvent::requestId),
        )
    }

    @Test
    fun runningDuplicateAcknowledgementDoesNotCreateATerminalRecord() {
        val file = Files.createTempDirectory("uclone-external-running")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file)

        store.record(event("request-1", ExternalRequestStage.RUNNING))

        assertEquals(null, store.terminal("request-1"))
    }

    @Test
    fun malformedTerminalIndexBlocksReplayAdmissionInsteadOfTreatingItAsEmpty() {
        val file = Files.createTempDirectory("uclone-external-corrupt-terminal")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        file.resolveSibling("external_requests_v1_terminals.jsonl").writeText("{not-json}\n")
        val store = ExternalRequestStore(file)

        assertNotNull(store.replayProtectionFailure())
        val admission = admitExternalTask(
            coordinator = TaskCoordinator(TaskLogStore(NoopShell)),
            request = request("request-after-corruption"),
            requestStore = store,
        )

        val rejected = assertIs<ExternalTaskAdmission.Rejected>(admission)
        assertEquals(ExternalActionContract.STATUS_REJECTED, rejected.status)
    }

    @Test
    fun explicitClearRepairsReplayProtectionAfterTerminalIndexCorruption() {
        val file = Files.createTempDirectory("uclone-external-clear-corrupt-terminal")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        file.resolveSibling("external_requests_v1_terminals.jsonl").writeText("{not-json}\n")
        val store = ExternalRequestStore(file)

        store.clear()

        assertEquals(null, store.replayProtectionFailure())
        assertIs<ExternalTaskAdmission.Accepted>(
            admitExternalTask(
                coordinator = TaskCoordinator(TaskLogStore(NoopShell)),
                request = request("request-after-explicit-clear"),
                requestStore = store,
            ),
        )
    }

    @Test
    fun onlyConfirmedMainAppMaintenanceCanRepairCorruptReplayProtection() {
        val file = Files.createTempDirectory("uclone-external-repair-admission")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        file.resolveSibling("external_requests_v1_terminals.jsonl").writeText("{not-json}\n")
        val store = ExternalRequestStore(file)
        val coordinator = TaskCoordinator(TaskLogStore(NoopShell))

        assertIs<ExternalTaskAdmission.Rejected>(
            admitExternalTask(coordinator, request("module-request"), store),
        )
        assertIs<ExternalTaskAdmission.Accepted>(
            admitExternalTask(
                coordinator,
                request(
                    requestId = "clear-request",
                    operation = ExternalActionContract.OPERATION_CLEAR_LOGS,
                    source = ExternalActionContract.SOURCE_APP,
                    packageName = "user10",
                ),
                store,
            ),
        )
    }

    @Test
    fun postmortemTerminalCorrectsProcessDeathButNotACompletedBusinessResult() {
        val file = Files.createTempDirectory("uclone-external-terminal-reconcile")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file)
        store.record(event("request-1", ExternalRequestStage.FAILED_PROCESS_DIED))

        val corrected = store.recordReconciledTerminal(event("request-1", ExternalRequestStage.SUCCESS))
        val rejected = store.recordReconciledTerminal(event("request-1", ExternalRequestStage.FAILED))

        assertEquals(true, corrected)
        assertEquals(false, rejected)
        assertEquals(ExternalRequestStage.SUCCESS, store.terminal("request-1")?.stage)
    }

    @Test
    fun processDeathRecoveryIsExternalOnlyAndIdempotent() {
        val file = Files.createTempDirectory("uclone-external-recovery")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file)
        val external = interruptedTask("external", ExternalActionContract.SOURCE_LAUNCHER_MODULE)
        val internal = interruptedTask("internal", ExternalActionContract.SOURCE_APP)

        val firstRecovery = ExternalRequestRecovery.recordProcessDeaths(
            listOf(external, internal),
            store,
            occurredAt = 10L,
        )
        val duplicateRecovery = ExternalRequestRecovery.recordProcessDeaths(
            listOf(external, internal),
            store,
            occurredAt = 20L,
        )

        val events = store.all()
        assertEquals(1, firstRecovery.size)
        assertEquals(0, duplicateRecovery.size)
        assertEquals(1, events.size)
        assertEquals("external", events.single().requestId)
        assertEquals(ExternalRequestStage.FAILED_PROCESS_DIED, events.single().stage)
        assertEquals(10L, events.single().occurredAt)
    }

    @Test
    fun processDeathRecoveryCanIgnoreOldInterruptedHistory() {
        val file = Files.createTempDirectory("uclone-external-recovery-cutoff")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file)

        val recovered = ExternalRequestRecovery.recordProcessDeaths(
            tasks = listOf(interruptedTask("old", ExternalActionContract.SOURCE_MODULE)),
            store = store,
            occurredAt = 100L,
            interruptedAfter = 3L,
        )

        assertEquals(emptyList(), recovered)
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun processDeathRecoveryIncludesLauncherShortcutRequests() {
        val file = Files.createTempDirectory("uclone-shortcut-recovery")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file)

        val recovered = ExternalRequestRecovery.recordProcessDeaths(
            tasks = listOf(interruptedTask("shortcut", ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT)),
            store = store,
            occurredAt = 10L,
        )

        assertEquals(1, recovered.size)
        assertEquals(ExternalRequestStage.FAILED_PROCESS_DIED, recovered.single().stage)
    }

    @Test
    fun processDeathRecoveryReportsStillRunningWhenRootMarkerIsLive() {
        val file = Files.createTempDirectory("uclone-live-root-recovery")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file)

        val recovered = ExternalRequestRecovery.recordProcessDeaths(
            tasks = listOf(interruptedTask("live-request", ExternalActionContract.SOURCE_MODULE)),
            store = store,
            occurredAt = 10L,
            liveRequestIds = setOf("live-request"),
        )

        assertEquals(ExternalRequestStage.STILL_RUNNING, recovered.single().stage)
        assertEquals("UClone 界面进程已重启，Root 数据任务仍在运行", recovered.single().message)
    }

    @Test
    fun priorStillRunningEventDoesNotSuppressLaterProcessDeathTerminal() {
        val file = Files.createTempDirectory("uclone-live-then-dead-recovery")
            .resolve("external_requests_v1.jsonl")
            .toFile()
        val store = ExternalRequestStore(file)
        val task = interruptedTask("live-request", ExternalActionContract.SOURCE_MODULE)
        ExternalRequestRecovery.recordProcessDeaths(
            tasks = listOf(task),
            store = store,
            occurredAt = 10L,
            liveRequestIds = setOf("live-request"),
        )

        val terminal = ExternalRequestRecovery.recordProcessDeaths(
            tasks = listOf(task),
            store = store,
            occurredAt = 20L,
        )

        assertEquals(ExternalRequestStage.FAILED_PROCESS_DIED, terminal.single().stage)
        assertEquals(ExternalRequestStage.FAILED_PROCESS_DIED, store.terminal("live-request")?.stage)
    }

    private fun event(requestId: String, stage: ExternalRequestStage) = ExternalRequestEvent(
        requestId = requestId,
        operation = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
        packageName = "com.example.app",
        source = ExternalActionContract.SOURCE_LAUNCHER_MODULE,
        stage = stage,
        occurredAt = 1L,
        message = "test",
    )

    private fun interruptedTask(requestId: String, source: String) = TaskRecord(
        id = 1L,
        requestId = requestId,
        packageName = "com.example.app",
        type = TaskType.SWITCH_TO_CLONE_STATE,
        startedAt = 1L,
        finishedAt = 2L,
        status = TaskStatus.INTERRUPTED,
        logPath = "/data/adb/uclone/logs/task.log",
        message = "任务中断",
        metrics = TaskMetrics(),
        audit = TaskAudit(source = source),
    )

    private fun request(
        requestId: String,
        operation: String = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
        source: String = ExternalActionContract.SOURCE_LAUNCHER_MODULE,
        packageName: String = "com.example.app",
    ) = ExternalActionRequest(
        operation = operation,
        packageName = packageName,
        requestId = requestId,
        source = source,
        rollbackId = null,
        internalToken = null,
    )

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}

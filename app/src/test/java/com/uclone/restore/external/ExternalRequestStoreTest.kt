package com.uclone.restore.external

import com.uclone.restore.model.TaskAudit
import com.uclone.restore.model.TaskMetrics
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

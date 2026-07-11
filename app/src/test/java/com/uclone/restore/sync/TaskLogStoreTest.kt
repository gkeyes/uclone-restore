package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.TaskAudit
import com.uclone.restore.model.TaskMetrics
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskStageMetric
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskLogStoreTest {
    @Test
    fun recordsSurviveStoreRecreation() {
        val historyFile = Files.createTempDirectory("uclone-history").resolve("task_history_v2.jsonl").toFile()
        val firstStore = TaskLogStore(NoopShell, historyFile)
        val task = firstStore.running(
            requestId = "request-123",
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = "com.example.app",
            logPath = "/data/adb/uclone/logs/example.log",
        )

        firstStore.finish(
            task = task,
            status = TaskStatus.SUCCESS,
            message = "切换完成",
            metrics = TaskMetrics(
                rootProcessStarts = 5,
                rootCommandCount = 5,
                scannedFiles = 42,
                copiedFiles = 40,
                copiedBytes = 4096,
                stages = listOf(
                    TaskStageMetric(
                        stage = TaskStage.RESTORE_DATA,
                        startedAt = 1_000,
                        finishedAt = 1_250,
                    ),
                ),
            ),
        )

        val secondStore = TaskLogStore(NoopShell, historyFile)
        val records = secondStore.all()

        assertEquals(1, records.size)
        assertEquals("com.example.app", records.single().packageName)
        assertEquals(TaskType.SWITCH_TO_CLONE_STATE, records.single().type)
        assertEquals(TaskStatus.SUCCESS, records.single().status)
        assertEquals("request-123", records.single().requestId)
        assertEquals(5, records.single().metrics.rootProcessStarts)
        assertEquals(250, records.single().metrics.stages.single().durationMs)
        assertEquals("切换完成", records.single().message)
        assertTrue(records.single().finishedAt != null)
        assertTrue(!historyFile.resolveSibling("${historyFile.name}.tmp").exists())
    }

    @Test
    fun deletionAuditSurvivesStoreRecreation() {
        val historyFile = Files.createTempDirectory("uclone-history-audit").resolve("task_history_v2.jsonl").toFile()
        val firstStore = TaskLogStore(NoopShell, historyFile)
        val task = firstStore.running(
            requestId = "delete-request",
            type = TaskType.DELETE_RESTORE_BACKUP,
            packageName = "com.example.app",
            logPath = "/data/adb/uclone/logs/delete.log",
            audit = TaskAudit(
                source = "app",
                backupKind = BackupKind.PASSIVE_BACKUP,
                backupId = "backup-1",
                path = "/data/adb/uclone/rollback/com.example.app/backup-1",
                sizeKb = 512,
            ),
        )
        firstStore.finish(task, TaskStatus.SUCCESS, "deleted")

        val audit = TaskLogStore(NoopShell, historyFile).all().single().audit

        assertEquals("app", audit.source)
        assertEquals(BackupKind.PASSIVE_BACKUP, audit.backupKind)
        assertEquals("backup-1", audit.backupId)
        assertEquals("/data/adb/uclone/rollback/com.example.app/backup-1", audit.path)
        assertEquals(512L, audit.sizeKb)
    }

    @Test
    fun persistedRunningRecordLoadsAsInterruptedFailure() {
        val historyFile = Files.createTempDirectory("uclone-history-running").resolve("task_history_v2.jsonl").toFile()
        TaskLogStore(NoopShell, historyFile).running(
            requestId = "interrupted-request",
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = "com.example.app",
            logPath = "/data/adb/uclone/logs/running.log",
        )

        val records = TaskLogStore(NoopShell, historyFile).all()

        assertEquals(TaskStatus.INTERRUPTED, records.single().status)
        assertEquals("任务中断", records.single().message)
        assertTrue(records.single().finishedAt != null)
    }

    @Test
    fun legacyTsvHistoryIsImportedOnce() {
        val directory = Files.createTempDirectory("uclone-history-legacy")
        val historyFile = directory.resolve("task_history_v2.jsonl").toFile()
        val legacyFile = directory.resolve("task_history.tsv").toFile()
        legacyFile.writeText(
            listOf(
                "100",
                "com.example.app",
                TaskType.RESTORE_SNAPSHOT_TO_MAIN.name,
                "1000",
                "2000",
                TaskStatus.SUCCESS.name,
                "%2Fdata%2Fadb%2Fuclone%2Flogs%2Flegacy.log",
                "%E6%81%A2%E5%A4%8D%E5%AE%8C%E6%88%90",
            ).joinToString("\t"),
        )

        val records = TaskLogStore(NoopShell, historyFile, legacyFile).all()

        assertEquals(1, records.size)
        assertEquals("legacy-100", records.single().requestId)
        assertEquals("恢复完成", records.single().message)
        assertTrue(historyFile.isFile)
    }

    @Test
    fun persistenceFailurePreventsTaskFromEnteringMemory() {
        val directory = Files.createTempDirectory("uclone-history-unwritable")
        val blockedParent = directory.resolve("not-a-directory").toFile().apply { writeText("blocked") }
        val store = TaskLogStore(NoopShell, blockedParent.resolve("task_history_v2.jsonl"))

        assertFailsWith<IllegalStateException> {
            store.running(
                requestId = "must-not-run",
                type = TaskType.SWITCH_TO_CLONE_STATE,
                packageName = "com.example.app",
                logPath = "/data/adb/uclone/logs/blocked.log",
            )
        }

        assertTrue(store.all().isEmpty())
    }

    @Test
    fun unknownOptionalStageDoesNotDropHistoryRecord() {
        val historyFile = Files.createTempDirectory("uclone-history-future-stage")
            .resolve("task_history_v2.jsonl")
            .toFile()
        historyFile.writeText(
            """{"schemaVersion":2,"id":200,"requestId":"future-stage","packageName":"com.example.app","type":"SWITCH_TO_CLONE_STATE","startedAt":1000,"finishedAt":2000,"status":"SUCCESS","logPath":"/data/adb/uclone/logs/future.log","message":"done","currentStage":"FUTURE_STAGE","metrics":{"rootProcessStarts":1,"rootCommandCount":1,"scannedFiles":0,"copiedFiles":0,"copiedBytes":0,"peakTemporaryBytes":0,"targetDowntimeMs":null,"stages":[{"stage":"FUTURE_STAGE","startedAt":1000,"finishedAt":1100}]}}""",
        )

        val record = TaskLogStore(NoopShell, historyFile).all().single()

        assertEquals("future-stage", record.requestId)
        assertEquals(null, record.currentStage)
        assertTrue(record.metrics.stages.isEmpty())
    }

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}

package com.uclone.restore.sync

import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.TaskAudit
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskPostmortemReconcilerTest {
    @Test
    fun terminalTrailerIsRequiredAndParsedFromTheLastLines() {
        assertNull(TaskTerminalLogReader.parse("UCLONE_POSTMORTEM_LOG_BEGIN\nRESULT=DONE\nEXIT=0\n"))

        val completed = TaskTerminalLogReader.parse(completedLog(exitCode = 0))

        assertNotNull(completed)
        assertEquals(0, completed.exitCode)
        assertEquals(1_783_700_000_000L, completed.endedAt)
        assertEquals(8_000L, completed.durationMs)
        assertFalse(completed.truncated)
    }

    @Test
    fun readerConfinesThePersistedLogToTheConfiguredWorkspace() {
        val script = TaskTerminalLogReader.script("/data/adb/uclone", interruptedTask())

        assertTrue(script.contains("ERR_UNSAFE_WORKSPACE_ROOT:"))
        assertTrue(script.contains("ERR_POSTMORTEM_LOG_OUTSIDE_WORKSPACE"))
        assertTrue(script.contains("ERR_POSTMORTEM_REQUEST_MISMATCH"))
        assertTrue(script.contains("ERR_POSTMORTEM_TYPE_MISMATCH"))
        assertTrue(script.contains("readlink -f"))
        assertTrue(script.contains("[ ! -L \"\$LOG_PATH\" ]"))
    }

    @Test
    fun completedRootLogReplacesInterruptedHistoryWithItsRealTerminalState() = kotlinx.coroutines.test.runTest {
        val repository = TaskLogStore(NoopShell)
        val accepted = repository.accepted(
            TaskType.DELETE_SNAPSHOT,
            "com.example.app",
            "request-1",
            TaskAudit(source = "launcher_module", backupKind = BackupKind.ACTIVE_SNAPSHOT),
        )
        val running = repository.running(
            TaskType.DELETE_SNAPSHOT,
            "com.example.app",
            "/data/adb/uclone/logs/delete.log",
            "request-1",
        )
        val interrupted = repository.finish(running, TaskStatus.INTERRUPTED, "任务中断")
        val readerOutput = completedLog(
            exitCode = 0,
            body = "DELETED_SNAPSHOT=/data/adb/uclone/snapshots/com.example.app/active SIZE_KB=25 ITEMS=4",
        )
        val reconciler = TaskPostmortemReconciler(FixedShell(ShellResult(0, readerOutput, "")), repository)

        val reconciled = reconciler.reconcile("/data/adb/uclone", interrupted)

        assertNotNull(reconciled)
        assertEquals(accepted.id, reconciled.id)
        assertEquals(TaskStatus.SUCCESS, reconciled.status)
        assertEquals(1_783_700_000_000L, reconciled.finishedAt)
        assertEquals(25L, reconciled.audit.sizeKb)
        assertEquals(TaskStatus.SUCCESS, repository.find("request-1")?.status)
    }

    @Test
    fun incompleteOrUnreadableLogsDoNotOverwriteInterruptedHistory() = kotlinx.coroutines.test.runTest {
        val repository = TaskLogStore(NoopShell)
        val running = repository.running(
            TaskType.SWITCH_TO_CLONE_STATE,
            "com.example.app",
            "/data/adb/uclone/logs/switch.log",
            "request-2",
        )
        val interrupted = repository.finish(running, TaskStatus.INTERRUPTED, "任务中断")
        val reconciler = TaskPostmortemReconciler(
            FixedShell(ShellResult(0, "UCLONE_POSTMORTEM_LOG_BEGIN\nSTATE=RUNNING\n", "")),
            repository,
        )

        assertNull(reconciler.reconcile("/data/adb/uclone", interrupted))
        assertEquals(TaskStatus.INTERRUPTED, repository.find("request-2")?.status)
    }

    private fun interruptedTask() = com.uclone.restore.model.TaskRecord(
        id = 1L,
        requestId = "request-1",
        packageName = "com.example.app",
        type = TaskType.SWITCH_TO_CLONE_STATE,
        startedAt = 1_783_699_992_000L,
        finishedAt = 1_783_700_000_000L,
        status = TaskStatus.INTERRUPTED,
        logPath = "/data/adb/uclone/logs/switch.log",
        message = "任务中断",
    )

    private fun completedLog(exitCode: Int, body: String = "RESULT=DONE"): String = """
        UCLONE_POSTMORTEM_TRUNCATED=0
        UCLONE_POSTMORTEM_LOG_BEGIN
        TASK=SWITCH_TO_CLONE_STATE
        REQUEST_ID=request-1
        $body
        END=1783700000000
        END_LOCAL=2026-07-11 00:00:00.000 +0800
        DURATION_MS=8000
        EXIT=$exitCode
    """.trimIndent() + "\n"

    private class FixedShell(private val result: ShellResult) : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = result
    }

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}

package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskLogStoreTest {
    @Test
    fun recordsSurviveStoreRecreation() {
        val historyFile = Files.createTempDirectory("uclone-history").resolve("task_history.tsv").toFile()
        val firstStore = TaskLogStore(NoopShell, historyFile)
        val task = firstStore.running(
            type = TaskType.SWITCH_TO_CLONE_STATE,
            packageName = "com.example.app",
            logPath = "/data/adb/uclone/logs/example.log",
        )

        firstStore.finish(task, TaskStatus.SUCCESS, "切换完成")

        val secondStore = TaskLogStore(NoopShell, historyFile)
        val records = secondStore.all()

        assertEquals(1, records.size)
        assertEquals("com.example.app", records.single().packageName)
        assertEquals(TaskType.SWITCH_TO_CLONE_STATE, records.single().type)
        assertEquals(TaskStatus.SUCCESS, records.single().status)
        assertEquals("切换完成", records.single().message)
        assertTrue(records.single().finishedAt != null)
    }

    private object NoopShell : RootShellExecutor {
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = ShellResult(0, "", "")
    }
}

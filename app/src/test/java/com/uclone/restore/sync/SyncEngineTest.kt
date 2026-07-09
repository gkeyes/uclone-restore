package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.RootEnvironmentChecker
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SyncEngineTest {
    @Test
    fun restoreFromCloneLatest_doesNotRestoreOldActiveSnapshotWhenCaptureFails() = runBlocking {
        val shell = FakeRootShell()
        val settings = UCloneSettings(rootDir = "/data/adb/uclone")
        val engine = SyncEngine(
            shell = shell,
            environmentChecker = RootEnvironmentChecker(shell),
            logStore = TaskLogStore(shell),
            appPackage = "com.uclone.restore",
        )

        val result = engine.restoreFromCloneLatest(
            packageName = "com.example.app",
            rule = AppRule(packageName = "com.example.app"),
            settings = settings,
            report = {},
        )

        assertEquals(TaskType.CAPTURE_SNAPSHOT_FROM_CLONE, result.type)
        assertEquals(TaskStatus.FAILED, result.status)
        assertFalse(shell.commands.any { "SOURCE_KIND='active'" in it })
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
}

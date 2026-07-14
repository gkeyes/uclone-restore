package com.uclone.restore.root

import com.uclone.restore.model.CheckResult
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.User10CeState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootEnvironmentCheckerTest {
    @Test
    fun targetRomUserDiscoveryUsesProvenPmCommand() = runBlocking {
        val shell = RecordingShell()

        val status = RootEnvironmentChecker(shell).check(
            UCloneSettings(mainUserId = 0, cloneUserId = 10),
        )

        assertTrue(status.user0Present)
        assertTrue(status.user10Present)
        assertTrue(shell.commands.contains("pm list users"))
        assertFalse(shell.commands.any { it == "cmd user list" })
    }

    @Test
    fun cloneStateRefreshRunsOnlyTheStateProbeAndPreservesOtherChecks() = runBlocking {
        val shell = RecordingShell()
        val current = EnvironmentStatus(
            root = CheckResult(true, "root-ok"),
            currentUser = "0",
            user0Present = true,
            user10Present = true,
            user10State = "RUNNING_LOCKED",
            dataAdbWritable = CheckResult(true, "workspace-ok"),
            snapshotDirReady = CheckResult(true, "snapshots-ok"),
        )

        val refreshed = RootEnvironmentChecker(shell).refreshCloneState(
            UCloneSettings(mainUserId = 0, cloneUserId = 10),
            current,
        )

        assertEquals(listOf("am get-started-user-state 10"), shell.commands)
        assertEquals(current.root, refreshed.root)
        assertEquals(current.dataAdbWritable, refreshed.dataAdbWritable)
        assertEquals("RUNNING_UNLOCKED", refreshed.user10State)
        assertEquals(User10CeState.RunningUnlocked, refreshed.user10CeState)
    }

    private class RecordingShell : RootShellExecutor {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult {
            commands += command
            return when {
                command == "id" -> ShellResult(0, "uid=0(root) gid=0(root)", "")
                command == "pm list users" -> ShellResult(
                    0,
                    "Users:\n\tUserInfo{0:main:4c13} running\n\tUserInfo{10:clone:413} running\n",
                    "",
                )
                command == "am get-current-user" -> ShellResult(0, "0\n", "")
                command == "am get-started-user-state 10" -> ShellResult(0, "RUNNING_UNLOCKED\n", "")
                "echo WRITABLE" in command -> ShellResult(0, "WRITABLE\n", "")
                command.startsWith("mkdir -p ") -> ShellResult(0, "", "")
                "READABLE items=" in command -> ShellResult(0, "READABLE items=1\n", "")
                else -> error("Unexpected command: $command")
            }
        }
    }
}

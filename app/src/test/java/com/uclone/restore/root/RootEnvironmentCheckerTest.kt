package com.uclone.restore.root

import com.uclone.restore.model.UCloneSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class RootEnvironmentCheckerTest {
    @Test
    fun customWorkspaceInitializationCreatesGuardIdentityAfterChildValidation() = runBlocking {
        val shell = RecordingEnvironmentShell()

        RootEnvironmentChecker(shell).check(UCloneSettings(rootDir = "/data/adb/custom-uclone"))

        val initialization = shell.commands.first { "workspace.identity" in it }
        assertContains(initialization, "UNSAFE_WORKSPACE_CHILD")
        assertContains(initialization, "com.uclone.restore.workspace.v1")
        assertTrue(initialization.indexOf("UNSAFE_WORKSPACE_CHILD") < initialization.indexOf("workspace.identity"))
    }

    private class RecordingEnvironmentShell : RootShellExecutor {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult {
            commands += command
            return when {
                command == "id" -> ShellResult(0, "uid=0(root)", "")
                command == "pm list users" -> ShellResult(0, "UserInfo{0:Owner} UserInfo{10:Clone}", "")
                command == "am get-current-user" -> ShellResult(0, "0", "")
                command.startsWith("am get-started-user-state") -> ShellResult(0, "RUNNING_UNLOCKED", "")
                "workspace.identity" in command -> ShellResult(0, "WRITABLE:/data/adb/custom-uclone", "")
                command.startsWith("mkdir -p") -> ShellResult(0, "", "")
                else -> ShellResult(0, "READABLE items=1", "")
            }
        }
    }
}

package com.uclone.restore.root

import com.uclone.restore.model.UCloneSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootEnvironmentCheckerTest {
    @Test
    fun workspaceInitializationUsesTheSharedIdentityAndSymlinkGuard() = runBlocking {
        val shell = RecordingEnvironmentShell()

        RootEnvironmentChecker(shell).check(UCloneSettings(rootDir = "/data/adb/custom-uclone"))

        val initialization = shell.commands.first { "workspace.identity" in it }
        assertContains(initialization, "ERR_WORKSPACE_SYMLINK")
        assertContains(initialization, "ERR_UNSAFE_WORKSPACE_TARGET")
        assertContains(initialization, "com.uclone.restore.workspace.v1")
        assertContains(initialization, "snapshots rollback clone_rollback switches logs tmp audit config locks transactions")
    }

    @Test
    fun runtimeSettingsValidationChecksBothUsersAndWorkspaceIdentity() = runBlocking {
        val shell = RecordingEnvironmentShell()

        val result = RootEnvironmentChecker(shell).validateSettingsTarget(UCloneSettings())

        assertTrue(result.ok)
        val command = shell.commands.single { "SETTINGS_TARGET_VALID" in it }
        assertContains(command, "UserInfo{0:")
        assertContains(command, "UserInfo{10:")
        assertContains(command, "workspace.identity")
    }

    @Test
    fun runtimeSettingsValidationRejectsMissingCloneUser() = runBlocking {
        val shell = RecordingEnvironmentShell(
            settingsValidationResult = ShellResult(76, "", "ERR_SETTINGS_CLONE_USER_MISSING:10"),
        )

        val result = RootEnvironmentChecker(shell).validateSettingsTarget(UCloneSettings())

        assertFalse(result.ok)
        assertContains(result.detail, "user10 不存在")
    }

    private class RecordingEnvironmentShell(
        private val settingsValidationResult: ShellResult = ShellResult(0, "SETTINGS_TARGET_VALID", ""),
    ) : RootShellExecutor {
        val commands = mutableListOf<String>()

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult {
            commands += command
            return when {
                "SETTINGS_TARGET_VALID" in command -> settingsValidationResult
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

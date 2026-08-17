package com.uclone.restore.crossuser

import com.uclone.crossuser.runtime.CrossUserRuntimeErrors
import com.uclone.restore.crossuser.user.CrossUserCloneCredentialUnlocker
import com.uclone.restore.crossuser.user.CrossUserCloneUnlockException
import com.uclone.restore.crossuser.user.CrossUserCloneUnlockResult
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellOutput
import com.uclone.restore.root.ShellResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CrossUserCloneCredentialUnlockerTest {
    @Test
    fun credentialIsSentOnlyThroughProtectedStandardInput() = runBlocking {
        val shell = RecordingShell(ShellResult(0, "CROSSUSER_UNLOCK_RESULT=UNLOCKED\n", ""))
        val unlocker = CrossUserCloneCredentialUnlocker(shell)

        val result = unlocker.unlockStartedUser(userId = 10, credential = "654321")

        assertEquals(CrossUserCloneUnlockResult.UNLOCKED, result)
        assertEquals("654321\n", shell.standardInput)
        assertFalse(shell.command.orEmpty().contains("654321"))
        assertFalse(shell.command.orEmpty().contains("start-user"))
        assertFalse(shell.command.orEmpty().contains("switch-user"))
    }

    @Test
    fun missingCredentialFailsBeforeStartingRootProcess() = runBlocking {
        val shell = RecordingShell(ShellResult(0, "", ""))
        val unlocker = CrossUserCloneCredentialUnlocker(shell)

        val failure = assertFailsWith<CrossUserCloneUnlockException> {
            unlocker.unlockStartedUser(userId = 10, credential = "  ")
        }

        assertEquals(CrossUserRuntimeErrors.CLONE_CREDENTIAL_MISSING, failure.errorCode)
        assertEquals(null, shell.command)
    }

    @Test
    fun rejectedCredentialUsesStableErrorWithoutEchoingShellOutput() = runBlocking {
        val shell = RecordingShell(
            ShellResult(
                84,
                "",
                "ERR_CROSSUSER_PIN_VERIFY_FAILED:BAD_CREDENTIAL\nsecret-output-must-not-surface",
            ),
        )
        val unlocker = CrossUserCloneCredentialUnlocker(shell)

        val failure = assertFailsWith<CrossUserCloneUnlockException> {
            unlocker.unlockStartedUser(userId = 10, credential = "654321")
        }

        assertEquals(CrossUserRuntimeErrors.CLONE_CREDENTIAL_REJECTED, failure.errorCode)
        assertFalse(failure.message.orEmpty().contains("secret-output"))
    }

    private class RecordingShell(
        private val response: ShellResult,
    ) : RootShellExecutor {
        var command: String? = null
        var standardInput: String? = null

        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult = response

        override suspend fun execStreamingWithInput(
            command: String,
            standardInput: String,
            timeoutSeconds: Long,
            onOutput: (ShellOutput) -> Unit,
        ): ShellResult {
            this.command = command
            this.standardInput = standardInput
            return response
        }
    }
}

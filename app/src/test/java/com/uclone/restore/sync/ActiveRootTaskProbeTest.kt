package com.uclone.restore.sync

import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveRootTaskProbeTest {
    @Test
    fun probeParsesLiveRootTaskWithoutMutatingMarker() = runBlocking {
        val shell = RecordingShell("ACTIVE_ROOT_TASK\trequest-1\t1\n")

        val state = ActiveRootTaskProbe(shell).probe("/data/adb/uclone")

        assertEquals(ActiveRootTaskState("request-1", isLive = true), state)
        assertTrue("active_task.claim" in shell.command)
        assertTrue("active_task/state" in shell.command)
        assertTrue("kill -0" in shell.command)
        val probeBody = shell.command.substringAfter("CLAIM_STATE=")
        assertTrue("rm " !in probeBody)
        assertTrue("mv " !in probeBody)
    }

    @Test
    fun probeFailureCannotBeMisreportedAsNoActiveTask() = runBlocking {
        val shell = object : RootShellExecutor {
            override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult =
                ShellResult(75, "", "ERR_ACTIVE_ROOT_TASK_UNSAFE_CLAIM")
        }

        val error = runCatching { ActiveRootTaskProbe(shell).probe("/data/adb/uclone") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    private class RecordingShell(private val output: String) : RootShellExecutor {
        var command: String = ""
        override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult {
            this.command = command
            return ShellResult(0, output, "")
        }
    }
}

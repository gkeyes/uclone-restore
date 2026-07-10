package com.uclone.restore.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RootTaskScriptTest {
    @Test
    fun wrapperRunsTaskAndPersistentLogInOneRootCommand() {
        val script = RootTaskScript.wrap(
            logPath = "/data/adb/uclone/logs/task.log",
            header = "TASK=TEST\nREQUEST_ID=request-1\n",
            body = "echo BODY_OK",
            startedAt = 1000,
        )

        assertEquals(1, Regex("/system/bin/tee -a").findAll(script).count())
        assertTrue("set -o pipefail" in script)
        assertTrue("ERR_ROOT_UNAVAILABLE" in script)
        assertTrue("DURATION_MS=" in script)
        assertTrue("echo BODY_OK" in script)
        assertTrue("/data/adb/uclone/logs/task.log" in script)
    }

    @Test
    fun wrapperStreamsAndPreservesBodyFailureExitCode() {
        val directory = Files.createTempDirectory("uclone-root-task-").toFile().apply { deleteOnExit() }
        val log = directory.resolve("task.log").apply { deleteOnExit() }
        val androidScript = RootTaskScript.wrap(
            logPath = log.absolutePath,
            header = "TASK=TEST\n",
            body = "echo BODY_OK; exit 7",
            startedAt = System.currentTimeMillis(),
        )
        val portableScript = portable(androidScript)
            .replace("*uid=0*)", "*uid=*)")

        val process = ProcessBuilder("/bin/sh", "-c", portableScript).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(7, exitCode, error)
        assertTrue("BODY_OK" in output)
        assertTrue("BODY_OK" in log.readText())
        assertTrue("EXIT=7" in log.readText())
    }

    @Test
    fun rootUnavailableStillEmitsCompleteFooter() {
        val directory = Files.createTempDirectory("uclone-root-denied-").toFile().apply { deleteOnExit() }
        val log = directory.resolve("task.log").apply { deleteOnExit() }
        val script = portable(
            RootTaskScript.wrap(
                logPath = log.absolutePath,
                header = "TASK=TEST\n",
                body = "echo SHOULD_NOT_RUN",
                startedAt = System.currentTimeMillis(),
            ),
        )

        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals(126, exitCode)
        assertTrue("ERR_ROOT_UNAVAILABLE:" in output)
        assertTrue("END=" in output)
        assertTrue("DURATION_MS=" in output)
        assertTrue("EXIT=126" in output)
        assertTrue("EXIT=126" in log.readText())
        assertTrue("SHOULD_NOT_RUN" !in output)
    }

    private fun portable(script: String): String = script
        .replace("/system/bin/mkdir", "/bin/mkdir")
        .replace("/system/bin/date", "/bin/date")
        .replace("/system/bin/id", "/usr/bin/id")
        .replace("/system/bin/printf", "/usr/bin/printf")
        .replace("/system/bin/tee", "/usr/bin/tee")
}

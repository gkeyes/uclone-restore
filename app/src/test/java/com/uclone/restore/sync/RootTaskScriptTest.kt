package com.uclone.restore.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootTaskScriptTest {
    @Test
    fun wrapperRunsTaskAndPersistentLogInOneRootCommand() {
        val script = RootTaskScript.wrap(
            logPath = "/data/adb/uclone/logs/task.log",
            header = "TASK=TEST\nREQUEST_ID=request-1\n",
            body = "echo BODY_OK",
            startedAt = 1000,
            activeTask = activeTask("/data/adb/uclone"),
        )

        assertEquals(1, Regex("/system/bin/tee -a").findAll(script).count())
        assertTrue("set -o pipefail" in script)
        assertTrue("ERR_ROOT_UNAVAILABLE" in script)
        assertTrue("DURATION_MS=" in script)
        assertTrue("TASK_DURATION_MS=${'$'}(awk" in script)
        assertFalse("TASK_END -" in script)
        assertTrue("echo BODY_OK" in script)
        assertTrue("/data/adb/uclone/logs/task.log" in script)
    }

    @Test
    fun wrapperStreamsAndPreservesBodyFailureExitCode() {
        val directory = prepareWorkspace(Files.createTempDirectory("uclone-root-task-").toFile())
        val log = directory.resolve("task.log").apply { deleteOnExit() }
        val androidScript = RootTaskScript.wrap(
            logPath = log.absolutePath,
            header = "TASK=TEST\n",
            body = "echo BODY_OK; exit 7",
            startedAt = System.currentTimeMillis(),
            activeTask = activeTask(directory.canonicalPath),
        )
        val portableScript = portable(androidScript)
            .replace("*uid=0*)", "*uid=*)")

        val process = ProcessBuilder("/bin/bash", "-c", portableScript).start()
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
                activeTask = activeTask(directory.absolutePath),
            ),
        )

        val process = ProcessBuilder("/bin/bash", "-c", script).start()
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

    @Test
    fun activeTaskMarkerIsAtomicRecoverableAndStageAware() {
        val script = RootTaskScript.wrap(
            logPath = "/data/adb/uclone/logs/task.log",
            header = "TASK=TEST\n",
            body = "uclone_active_stage COPYING; echo BODY_OK",
            startedAt = 1_000,
            activeTask = ActiveRootTask(
                rootDir = "/data/adb/uclone",
                requestId = "request-1",
                taskType = "TEST",
                packageName = "com.example.app",
                startedAt = 1_000,
            ),
        )

        assertTrue("ACTIVE_CLAIM=\"${'$'}ACTIVE_LOCK_ROOT/active_task.claim\"" in script)
        assertTrue("ACTIVE_LEGACY_STATE=\"${'$'}ACTIVE_LEGACY_LOCK/state\"" in script)
        assertTrue("ACTIVE_ORPHANED_ROOT=\"${'$'}ACTIVE_LOCK_ROOT/orphaned\"" in script)
        assertTrue("ln \"${'$'}ACTIVE_CLAIM_TMP\" \"${'$'}ACTIVE_CLAIM\"" in script)
        assertTrue("mv -f \"${'$'}ACTIVE_STATE_TMP\" \"${'$'}ACTIVE_CLAIM\"" in script)
        assertTrue("kill -0 \"${'$'}EXISTING_PID\"" in script)
        assertTrue("bootId=${'$'}ACTIVE_BOOT_ID" in script)
        assertTrue("pidStartTicks=${'$'}ACTIVE_PID_START_TICKS" in script)
        assertTrue("EXISTING_BOOT_ID" in script)
        assertTrue("LIVE_PID_START_TICKS" in script)
        assertTrue("ERR_ACTIVE_ROOT_TASK:" in script)
        assertTrue("UCLONE_RECOVERY:ORPHANED" in script)
        assertTrue("ERR_ACTIVE_ROOT_TASK_INITIALIZING:" in script)
        assertTrue("ACTIVE_INITIALIZING_AGE" in script)
        assertTrue("legacy_${'$'}{ORPHANED_AT}_${'$'}{EXISTING_PID}" in script)
        assertTrue("claim_${'$'}{ORPHANED_AT}_${'$'}{EXISTING_PID}" in script)
        assertTrue("uclone_active_stage COPYING" in script)
        assertTrue("UCLONE_REQUEST_ID=\"${'$'}ACTIVE_REQUEST_ID\"" in script)
        assertTrue("trap 'uclone_release_active_task' EXIT" in script)
    }

    private fun portable(script: String): String {
        val common = script
            .replace("/system/bin/mkdir", "/bin/mkdir")
            .replace("/system/bin/date", "/bin/date")
            .replace("/system/bin/id", "/usr/bin/id")
            .replace("/system/bin/printf", "/usr/bin/printf")
            .replace("/system/bin/tee", "/usr/bin/tee")
        val platform = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
            common
                .replace("stat -c '%u:%g'", "stat -f '%u:%g'")
                .replace("stat -c '%u'", "stat -f '%u'")
                .replace("stat -c '%a'", "stat -f '%Lp'")
                .replace("stat -c %Y", "stat -f %m")
        } else {
            common
        }
        return platform.replace(
            "[ \"${'$'}UCLONE_IDENTITY_OWNER\" = \"0:0\" ]",
            "[ \"${'$'}UCLONE_IDENTITY_OWNER\" = \"${'$'}(/usr/bin/id -u):${'$'}(/usr/bin/id -g)\" ]",
        ).replace(
            "[ \"${'$'}UCLONE_GUARD_OWNER\" = \"0\" ]",
            "[ \"${'$'}UCLONE_GUARD_OWNER\" = \"${'$'}(/usr/bin/id -u)\" ]",
        )
    }

    private fun prepareWorkspace(directory: File): File = directory.canonicalFile.apply {
        deleteOnExit()
        resolve("config/workspace.identity").apply {
            check(checkNotNull(parentFile).mkdirs())
            writeText("com.uclone.restore.workspace.v1\n")
            Files.setPosixFilePermissions(
                toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            deleteOnExit()
        }
    }

    private fun activeTask(rootDir: String) = ActiveRootTask(
        rootDir = rootDir,
        requestId = "request-test",
        taskType = "TEST",
        packageName = "com.example.app",
        startedAt = 1_000,
    )
}

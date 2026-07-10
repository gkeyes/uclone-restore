package com.uclone.restore.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoragePreflightShellTest {
    @Test
    fun realShellAcceptsSmallRequirementAndReportsCapacity() {
        val root = Files.createTempDirectory("uclone-space-ok-").toFile().apply { deleteOnExit() }
        val script = """
            ROOT='${root.absolutePath}'
            ${ShellScripts.storagePreflightScript()}
            uclone_require_space 1 test_ok
        """.trimIndent()

        val result = runShell(script)

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue("SPACE_PREFLIGHT:test_ok" in result.stdout)
    }

    @Test
    fun realShellRejectsImpossibleRequirementBeforeCallerCanMutate() {
        val root = Files.createTempDirectory("uclone-space-fail-").toFile().apply { deleteOnExit() }
        val script = """
            ROOT='${root.absolutePath}'
            ${ShellScripts.storagePreflightScript()}
            uclone_require_space 999999999999 test_fail
            echo SHOULD_NOT_RUN
        """.trimIndent()

        val result = runShell(script)

        assertEquals(75, result.exitCode)
        assertTrue("ERR_INSUFFICIENT_SPACE:test_fail" in result.stderr)
        assertTrue("SHOULD_NOT_RUN" !in result.stdout)
    }

    @Test
    fun firstCandidateSelectionSkipsExistingButEmptyDirectory() {
        val root = Files.createTempDirectory("uclone-space-candidates-").toFile().apply { deleteOnExit() }
        val empty = root.resolve("empty").apply { mkdirs(); deleteOnExit() }
        val populated = root.resolve("populated").apply { mkdirs(); deleteOnExit() }
        populated.resolve("payload.bin").apply {
            writeBytes(ByteArray(4096) { 1 })
            deleteOnExit()
        }
        val script = """
            ROOT='${root.absolutePath}'
            ${ShellScripts.storagePreflightScript()}
            UCLONE_ESTIMATED_KB=0
            uclone_add_first_dir_kb '${empty.absolutePath}' '${populated.absolutePath}'
            echo "ESTIMATED_KB=${'$'}UCLONE_ESTIMATED_KB"
        """.trimIndent()

        val result = runShell(script)
        val estimated = result.stdout.substringAfter("ESTIMATED_KB=").trim().toLong()

        assertEquals(0, result.exitCode, result.stderr)
        assertTrue(estimated > 0)
    }

    @Test
    fun arithmeticOverflowInputIsRejected() {
        val root = Files.createTempDirectory("uclone-space-overflow-").toFile().apply { deleteOnExit() }
        val script = """
            ROOT='${root.absolutePath}'
            ${ShellScripts.storagePreflightScript()}
            uclone_require_space 999999999999999999999 overflow
            echo SHOULD_NOT_RUN
        """.trimIndent()

        val result = runShell(script)

        assertEquals(75, result.exitCode)
        assertTrue("ERR_SPACE_ESTIMATE:overflow" in result.stderr)
        assertTrue("SHOULD_NOT_RUN" !in result.stdout)
    }

    private fun runShell(script: String): ShellExecution {
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return ShellExecution(process.waitFor(), stdout, stderr)
    }

    private data class ShellExecution(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}

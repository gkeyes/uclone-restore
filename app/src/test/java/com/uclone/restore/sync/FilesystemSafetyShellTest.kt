package com.uclone.restore.sync

import com.uclone.restore.root.shellQuote
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesystemSafetyShellTest {
    @Test
    fun removeTreeRejectsSymlinkRootWithoutTouchingItsTarget() {
        val root = Files.createTempDirectory("uclone-remove-tree-").toFile()
        val external = Files.createTempDirectory("uclone-remove-target-").toFile()
        external.resolve("keep.txt").writeText("keep")
        val link = root.resolve("link")
        Files.createSymbolicLink(link.toPath(), external.toPath())

        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            FilesystemSafetyShell.functions() + "\nuclone_remove_tree ${shellQuote(link.absolutePath)}",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(1, process.waitFor(), output)
        assertContains(output, "ERR_UNSAFE_REMOVE_ROOT")
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("keep", external.resolve("keep.txt").readText())
        root.deleteRecursively()
        external.deleteRecursively()
    }

    @Test
    fun filesystemGuardFailsClosedWhenMountTableCannotBeRead() {
        val root = Files.createTempDirectory("uclone-filesystem-root-").toFile()
        val bin = Files.createTempDirectory("uclone-filesystem-bin-").toFile()
        bin.resolve("stat").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' 7\n")
            check(setExecutable(true))
        }
        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            FilesystemSafetyShell.functions() + "\nuclone_assert_single_filesystem ${shellQuote(root.absolutePath)}",
        ).redirectErrorStream(true).apply {
            environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
            environment()["UCLONE_MOUNTINFO_PATH"] = root.resolve("missing-mountinfo").absolutePath
        }.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(1, process.waitFor(), output)
        assertContains(output, "ERR_FILESYSTEM_MOUNTINFO_UNAVAILABLE")
        root.deleteRecursively()
        bin.deleteRecursively()
    }

    @Test
    fun filesystemGuardRejectsNestedMountFromMountInfoWithoutWalkingPayload() {
        val root = Files.createTempDirectory("uclone-filesystem-root-").toFile()
        val mountInfo = root.resolve("mountinfo")
        mountInfo.writeText("35 24 0:31 / ${root.absolutePath}/snapshots rw,relatime - tmpfs tmpfs rw\n")
        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            FilesystemSafetyShell.functions() + "\nuclone_assert_single_filesystem ${shellQuote(root.absolutePath)}",
        ).redirectErrorStream(true).apply {
            environment()["UCLONE_MOUNTINFO_PATH"] = mountInfo.absolutePath
        }.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(1, process.waitFor(), output)
        assertContains(output, "ERR_CROSS_FILESYSTEM_PATH")
        root.deleteRecursively()
    }

    @Test
    fun archivePathAvoidsNewerToyboxOnlyTarOptions() {
        val script = FilesystemSafetyShell.functions()

        assertContains(script, "tar -cpf - .")
        assertContains(script, "/proc/self/mountinfo")
        assertFalse(script.contains("--null"))
        assertFalse(script.contains("--no-recursion"))
        assertFalse(script.contains("! -exec sh -c"))
        assertContains(script, "UCLONE_MOUNTINFO_PATH")
    }

    @Test
    fun extractionFailsWhenTheSourceArchiveFailsInsteadOfAcceptingAnEmptyPipe() {
        val root = Files.createTempDirectory("uclone-extract-pipe-").toFile()
        val missingSource = root.resolve("missing-source")
        val destination = root.resolve("destination").apply { mkdirs() }
        val process = ProcessBuilder(
            "/bin/bash",
            "-c",
            FilesystemSafetyShell.functions() +
                "\nuclone_extract_workspace_tree ${shellQuote(missingSource.absolutePath)} ${shellQuote(destination.absolutePath)}",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()

        assertTrue(process.waitFor() != 0, output)
        root.deleteRecursively()
    }

    @Test
    fun extractionRequiresPipefailRatherThanFallingBackToPipelineLastStatus() {
        val script = FilesystemSafetyShell.functions()

        assertContains(script, "set -o pipefail")
        assertContains(script, "ERR_PIPEFAIL_UNAVAILABLE")
    }

    @Test
    fun removeTreeSuppressesToyboxDeletePathOutputButStillDeletes() {
        val root = Files.createTempDirectory("uclone-quiet-remove-").toFile()
        root.resolve("nested/deeper").mkdirs()
        root.resolve("nested/deeper/file.txt").writeText("payload")
        val support = Files.createTempDirectory("uclone-quiet-remove-support-").toFile()
        val mountInfo = support.resolve("mountinfo").apply { writeText("") }
        val bin = support.resolve("bin").apply { mkdirs() }
        bin.resolve("stat").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' 7\n")
            check(setExecutable(true))
        }
        bin.resolve("find").apply {
            writeText(
                """
                    #!/bin/sh
                    /usr/bin/find "${'$'}@"
                    result="${'$'}?"
                    for arg in "${'$'}@"; do
                      [ "${'$'}arg" != "-delete" ] || echo TOYBOX_DELETE_PATH_OUTPUT
                    done
                    exit "${'$'}result"
                """.trimIndent() + "\n",
            )
            check(setExecutable(true))
        }
        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            FilesystemSafetyShell.functions() + "\nuclone_remove_tree ${shellQuote(root.absolutePath)}",
        ).redirectErrorStream(true).apply {
            environment()["PATH"] = "${bin.absolutePath}:${System.getenv("PATH")}"
            environment()["UCLONE_MOUNTINFO_PATH"] = mountInfo.absolutePath
        }.start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        assertFalse(output.contains("TOYBOX_DELETE_PATH_OUTPUT"), output)
        assertFalse(root.exists())
        support.deleteRecursively()
    }
}

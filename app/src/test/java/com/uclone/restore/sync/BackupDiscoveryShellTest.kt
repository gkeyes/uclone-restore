package com.uclone.restore.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupDiscoveryShellTest {
    @Test
    fun partialDirectoryWithoutManifestIsNotListedAsRestorable() {
        val root = Files.createTempDirectory("uclone-backup-index-").toFile().apply { deleteOnExit() }
        val rollbackRoot = root.resolve("rollback").apply { mkdirs(); deleteOnExit() }
        val switchRoot = root.resolve("switches").apply { mkdirs(); deleteOnExit() }
        val packageRoot = rollbackRoot.resolve("com.example.app").apply { mkdirs(); deleteOnExit() }
        packageRoot.resolve("partial").apply { mkdirs(); deleteOnExit() }
        packageRoot.resolve("complete").apply {
            mkdirs()
            deleteOnExit()
            resolve("manifest.json").apply {
                writeText("{\"reason\":\"complete backup\"}\n")
                deleteOnExit()
            }
        }

        val process = ProcessBuilder(
            "/bin/sh",
            "-c",
            restoreBackupListScript(rollbackRoot.absolutePath, switchRoot.absolutePath),
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), error)
        assertTrue("complete" in output)
        assertTrue("complete backup" in output)
        assertFalse("partial" in output)
    }
}

package com.uclone.restore.sync

import com.uclone.restore.model.PassiveBackupStateKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupDiscoveryShellTest {
    @Test
    fun workspaceIndexScriptDiscoversEveryWorkspaceSection() {
        val root = Files.createTempDirectory("uclone-workspace-index-").toFile().apply { deleteOnExit() }
        root.resolve("snapshots/com.example.app/active").apply {
            mkdirs()
            resolve("manifest.json").writeText("{\"snapshotSizeKb\":\"12\"}\n")
        }
        root.resolve("rollback/com.example.app/rollback-1").apply {
            mkdirs()
            resolve("manifest.json").writeText("{\"reason\":\"main backup\"}\n")
        }
        root.resolve("switches/com.example.app").apply {
            mkdirs()
            resolve("active").writeText("rollback-1\n")
        }
        root.resolve("clone_rollback/com.example.app/latest").apply {
            mkdirs()
            resolve("manifest.json").writeText("{\"reason\":\"clone backup\"}\n")
        }
        val process = ProcessBuilder("/bin/sh", "-c", workspaceIndexScript(root.absolutePath)).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val index = WorkspaceIndexParser.parse(output)
        val exitCode = process.waitFor()
        root.deleteRecursively()

        assertEquals(0, exitCode, error)
        assertEquals(12L, index.snapshots.getValue("com.example.app").sizeKb)
        assertEquals("rollback-1", index.switchMarkers["com.example.app"])
        assertEquals("main backup", index.restoreBackups.single().reason)
        assertTrue(index.restoreBackups.single().isActiveSwitchBackup)
        assertEquals(PassiveBackupStateKind.MAIN, index.restoreBackups.single().stateKind)
        assertEquals("clone backup", index.cloneRollbackBackups.single().reason)
    }

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

    @Test
    fun workspaceIndexNeverChangesWorkspaceOwnership() {
        val script = workspaceIndexScript("/data/adb/uclone")

        assertFalse(script.contains("WORKSPACE_OWNER_REPAIR"))
        assertFalse(script.contains("chown"))
    }

    @Test
    fun workspaceIndexKeepsLatestBackupForEachDataState() {
        val output = listOf(
            "MAIN_ROLLBACK\tcom.example.app\tmain-old\t10\t100\tmain old\tmain",
            "MAIN_ROLLBACK\tcom.example.app\tmain-new\t30\t120\tmain new\tmain",
            "MAIN_ROLLBACK\tcom.example.app\tclone-new\t20\t110\tclone new\tclone",
        ).joinToString("\n")

        val backups = WorkspaceIndexParser.parse(output).restoreBackups

        assertEquals(2, backups.size)
        assertEquals(setOf(PassiveBackupStateKind.MAIN, PassiveBackupStateKind.CLONE), backups.map { it.stateKind }.toSet())
        assertEquals("main-new", backups.first { it.stateKind == PassiveBackupStateKind.MAIN }.rollbackId)
        assertEquals("clone-new", backups.first { it.stateKind == PassiveBackupStateKind.CLONE }.rollbackId)
    }

}

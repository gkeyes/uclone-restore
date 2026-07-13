package com.uclone.restore.sync

import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.root.shellQuote
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
            resolve("manifest.json").writeText(
                "{\"schemaVersion\":5,\"packageName\":\"com.example.app\",\"reason\":\"main backup\",\"stateKind\":\"main\",\"sourceUser\":\"0\",\"targetUser\":\"0\",\"backupKind\":\"rollback\",\"sourceSigningCertificateSha256\":\"${"a".repeat(64)}\"}\n",
            )
        }
        root.resolve("switches/com.example.app").apply {
            mkdirs()
            resolve("active").writeText("rollback-1\n")
        }
        root.resolve("clone_rollback/com.example.app/latest").apply {
            mkdirs()
            resolve("manifest.json").writeText("{\"reason\":\"clone backup\"}\n")
        }
        val rootPath = root.canonicalPath
        val discoveryScript = workspaceIndexScript(rootPath, mainUserId = 0).replace(
            WorkspacePathGuard.inspect(rootPath),
            "ROOT=${shellQuote(rootPath)}\nROOT_REAL=${shellQuote(rootPath)}",
        )
        val process = ProcessBuilder("/bin/sh", "-c", discoveryScript).start()
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
        val script = workspaceIndexScript("/data/adb/uclone", mainUserId = 0)

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

    @Test
    fun workspaceIndexTreatsUnknownAndBrokenActiveMarkersAsUnknownState() {
        val root = Files.createTempDirectory("uclone-unknown-switch-").toFile().apply { deleteOnExit() }
        root.resolve("switches/com.unknown.app").apply {
            mkdirs()
            resolve("active").writeText("$UNKNOWN_SWITCH_MARKER\n")
        }
        root.resolve("switches/com.broken.app").apply {
            mkdirs()
            resolve("active").writeText("missing-return-point\n")
        }
        root.resolve("rollback/com.wrong-package.app/return-point").apply {
            mkdirs()
            resolve("manifest.json").writeText(
                "{\"packageName\":\"com.other.app\",\"stateKind\":\"main\",\"sourceUser\":\"0\",\"targetUser\":\"0\"}\n",
            )
        }
        root.resolve("switches/com.wrong-package.app").apply {
            mkdirs()
            resolve("active").writeText("return-point\n")
        }
        root.resolve("rollback/com.wrong-state.app/return-point").apply {
            mkdirs()
            resolve("manifest.json").writeText(
                "{\"packageName\":\"com.wrong-state.app\",\"stateKind\":\"clone\",\"sourceUser\":\"0\",\"targetUser\":\"0\"}\n",
            )
        }
        root.resolve("switches/com.wrong-state.app").apply {
            mkdirs()
            resolve("active").writeText("return-point\n")
        }
        root.resolve("rollback/com.wrong-user.app/return-point").apply {
            mkdirs()
            resolve("manifest.json").writeText(
                "{\"packageName\":\"com.wrong-user.app\",\"stateKind\":\"main\",\"sourceUser\":\"10\",\"targetUser\":\"10\"}\n",
            )
        }
        root.resolve("switches/com.wrong-user.app").apply {
            mkdirs()
            resolve("active").writeText("return-point\n")
        }
        root.resolve("switches/com.unsafe-id.app").apply {
            mkdirs()
            resolve("active").writeText("../outside\n")
        }
        root.resolve("rollback/com.symlink-manifest.app/return-point").apply {
            mkdirs()
            resolve("manifest-target.json").writeText(
                "{\"packageName\":\"com.symlink-manifest.app\",\"stateKind\":\"main\",\"sourceUser\":\"0\",\"targetUser\":\"0\"}\n",
            )
            Files.createSymbolicLink(
                resolve("manifest.json").toPath(),
                resolve("manifest-target.json").toPath(),
            )
        }
        root.resolve("switches/com.symlink-manifest.app").apply {
            mkdirs()
            resolve("active").writeText("return-point\n")
        }
        val rootPath = root.canonicalPath
        val discoveryScript = workspaceIndexScript(rootPath, mainUserId = 0).replace(
            WorkspacePathGuard.inspect(rootPath),
            "ROOT=${shellQuote(rootPath)}\nROOT_REAL=${shellQuote(rootPath)}",
        )

        val process = ProcessBuilder("/bin/sh", "-c", discoveryScript).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        val index = WorkspaceIndexParser.parse(output)
        val exitCode = process.waitFor()
        root.deleteRecursively()

        assertEquals(0, exitCode, error)
        assertEquals(
            setOf(
                "com.unknown.app",
                "com.broken.app",
                "com.wrong-package.app",
                "com.wrong-state.app",
                "com.wrong-user.app",
                "com.unsafe-id.app",
                "com.symlink-manifest.app",
            ),
            index.unknownSwitchPackages,
        )
        assertEquals(AppDataState.Unknown, index.dataState("com.unknown.app"))
        assertEquals(AppDataState.Unknown, index.dataState("com.broken.app"))
        assertEquals(AppDataState.Unknown, index.dataState("com.wrong-package.app"))
        assertEquals(AppDataState.Unknown, index.dataState("com.wrong-state.app"))
        assertEquals(AppDataState.Unknown, index.dataState("com.wrong-user.app"))
        assertEquals(AppDataState.Unknown, index.dataState("com.unsafe-id.app"))
        assertEquals(AppDataState.Unknown, index.dataState("com.symlink-manifest.app"))
        assertEquals(AppDataState.Main, index.dataState("com.main.app"))
    }

}

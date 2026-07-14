package com.uclone.restore.sync

import com.uclone.restore.model.PassiveBackupStateKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WorkspaceIndexTest {
    @Test
    fun absentSwitchMarkerMeansMainAndValidMarkerMeansClone() {
        val index = WorkspaceIndexParser.parse(
            listOf(
                "SWITCH\tcom.example.clone\tpersistent_main",
                "MAIN_ROLLBACK\tcom.example.clone\tpersistent_main\t100\t20\tmain state\tMAIN\tpersistent_state",
                "WORKSPACE_INDEX_OK",
            ).joinToString("\n"),
        )

        assertEquals(AppDataState.Main, index.dataState("com.example.main"))
        assertEquals("persistent_main", assertIs<AppDataState.Clone>(index.dataState("com.example.clone")).mainReturnPointId)
    }

    @Test
    fun explicitMainMarkerIsTrackedSeparatelyFromLegacyMissingMarker() {
        val index = WorkspaceIndexParser.parse(
            listOf(
                "MAIN\tcom.example.confirmed",
                "WORKSPACE_INDEX_OK",
            ).joinToString("\n"),
        )

        assertEquals(AppDataState.Main, index.dataState("com.example.confirmed"))
        assertEquals(true, index.hasConfirmedMainState("com.example.confirmed"))
        assertEquals(false, index.hasConfirmedMainState("com.example.legacy"))
    }

    @Test
    fun malformedOrUnreadableWorkspaceFailsClosedAsUnknown() {
        val malformed = WorkspaceIndexParser.parse("SWITCH\tcom.example.app\nWORKSPACE_INDEX_OK")
        val incomplete = WorkspaceIndexParser.parse("SWITCH\tcom.example.app\treturn_point")
        val unreadable = WorkspaceIndex(readSucceeded = false)

        assertEquals(false, malformed.readSucceeded)
        assertEquals(AppDataState.Unknown, malformed.dataState("com.example.app"))
        assertEquals(AppDataState.Unknown, incomplete.dataState("com.example.app"))
        assertEquals(AppDataState.Unknown, unreadable.dataState("com.example.app"))
    }

    @Test
    fun mainAndClonePersistentBackupsKeepTheirSourceLabels() {
        val index = WorkspaceIndexParser.parse(
            listOf(
                "MAIN_ROLLBACK\tcom.example.app\tpersistent_main\t100\t20\tmain state\tMAIN\tpersistent_state",
                "MAIN_ROLLBACK\tcom.example.app\tpersistent_clone\t200\t30\tclone state\tCLONE\tpersistent_state",
                "CLONE_ROLLBACK\tcom.example.app\tlatest\t300\t40\tclone target\tpersistent_state",
                "WORKSPACE_INDEX_OK",
            ).joinToString("\n"),
        )

        assertEquals(setOf(PassiveBackupStateKind.MAIN, PassiveBackupStateKind.CLONE), index.mainRollbackBackups.mapNotNull { it.stateKind }.toSet())
        assertEquals(true, index.mainRollbackBackups.all { it.isPersistentStateBackup })
        assertEquals(PassiveBackupStateKind.CLONE, index.cloneRollbackBackups.single().stateKind)
        assertEquals(true, index.cloneRollbackBackups.single().isPersistentStateBackup)
    }

    @Test
    fun fixedMainReturnPointRemainsVisibleWhenANewerMainTransactionUndoExists() {
        val index = WorkspaceIndexParser.parse(
            listOf(
                "MAIN_ROLLBACK\tcom.example.app\tpersistent_main\t100\t20\tfixed main\tMAIN\tpersistent_state",
                "MAIN_ROLLBACK\tcom.example.app\ttransaction_2\t200\t30\tnewer undo\tMAIN\ttransaction_undo",
                "WORKSPACE_INDEX_OK",
            ).joinToString("\n"),
        )

        assertEquals(
            setOf("persistent_main", "transaction_2"),
            index.restoreBackups.map { it.rollbackId }.toSet(),
        )
    }

    @Test
    fun executableIndexOnlyReportsCloneForACompleteMainReturnPoint() {
        val root = Files.createTempDirectory("uclone-workspace-index")
        val packageName = "com.example.app"
        val rollbackId = "persistent_main"
        val backup = root.resolve("rollback/$packageName/$rollbackId")
        createCompleteMainReturn(backup)
        val marker = root.resolve("switches/$packageName/active")
        Files.createDirectories(marker.parent)
        Files.write(marker, "$rollbackId\n".toByteArray())

        val valid = runShell(workspaceIndexScript(root.toString()))
        assertEquals(0, valid.exitCode, valid.output)
        assertContains(valid.output, "SWITCH\t$packageName\t$rollbackId")
        assertEquals(rollbackId, assertIs<AppDataState.Clone>(WorkspaceIndexParser.parse(valid.output).dataState(packageName)).mainReturnPointId)

        Files.delete(backup.resolve(".state/obb"))
        val incomplete = runShell(workspaceIndexScript(root.toString()))
        assertEquals(0, incomplete.exitCode, incomplete.output)
        assertContains(incomplete.output, "UNKNOWN\t$packageName")
        assertEquals(AppDataState.Unknown, WorkspaceIndexParser.parse(incomplete.output).dataState(packageName))
    }

    @Test
    fun executableIndexRejectsLegacyPersistentCloneEvenWithoutAStateKind() {
        val root = Files.createTempDirectory("uclone-workspace-index-legacy-clone")
        val packageName = "com.example.app"
        val rollbackId = "persistent_clone"
        val backup = root.resolve("rollback/$packageName/$rollbackId")
        createCompleteMainReturn(backup)
        Files.write(backup.resolve("manifest.json"), "{}\n".toByteArray())
        val marker = root.resolve("switches/$packageName/active")
        Files.createDirectories(marker.parent)
        Files.write(marker, "$rollbackId\n".toByteArray())

        val result = runShell(workspaceIndexScript(root.toString()))

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "UNKNOWN\t$packageName")
        assertEquals(AppDataState.Unknown, WorkspaceIndexParser.parse(result.output).dataState(packageName))
    }

    @Test
    fun executableIndexReportsExplicitMainMarker() {
        val root = Files.createTempDirectory("uclone-workspace-index-main")
        val packageName = "com.example.app"
        val marker = root.resolve("switches/$packageName/active")
        Files.createDirectories(marker.parent)
        Files.write(marker, "$MAIN_SWITCH_MARKER\n".toByteArray())

        val result = runShell(workspaceIndexScript(root.toString()))
        val parsed = WorkspaceIndexParser.parse(result.output)

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "MAIN\t$packageName")
        assertEquals(true, parsed.hasConfirmedMainState(packageName))
        assertEquals(AppDataState.Main, parsed.dataState(packageName))
    }

    private fun createCompleteMainReturn(backup: Path) {
        val state = Files.createDirectories(backup.resolve(".state"))
        Files.write(backup.resolve("manifest.json"), "{\"stateKind\":\"MAIN\"}\n".toByteArray())
        listOf("ce", "de", "external", "media", "obb").forEach { part ->
            Files.write(state.resolve(part), "absent\n".toByteArray())
        }
    }

    private fun runShell(script: String): ShellRun {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return ShellRun(process.waitFor(), output)
    }

    private data class ShellRun(val exitCode: Int, val output: String)
}

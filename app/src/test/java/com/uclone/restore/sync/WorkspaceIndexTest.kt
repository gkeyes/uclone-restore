package com.uclone.restore.sync

import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.PassiveBackupStateKind
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
    fun backupRecordsParseEverySupportedField() {
        val index = WorkspaceIndexParser.parse(
            listOf(
                "BACKUP\tmanual\tcom.example.app\tmanual_1\t100\t20\t10\t0\tCLONE\t命名备份\t手动命名备份\t中文备注\t/data/adb/uclone/snapshots/com.example.app/manual/manual_1\t",
                "BACKUP\tmain_rollback\tcom.example.app\tpersistent_main\t90\t10\t0\t0\tMAIN\t主系统侧备份\t固定 MAIN\t/data/adb/uclone/rollback/com.example.app/persistent_main\t当前 CLONE 状态依赖的 MAIN 返回点",
                "WORKSPACE_INDEX_OK",
            ).joinToString("\n"),
        )

        val manual = index.backups.single { it.ref.kind == BackupKind.MANUAL }
        val protected = index.backups.single { it.ref.kind == BackupKind.MAIN_ROLLBACK }
        assertEquals("manual_1", manual.ref.id)
        assertEquals("中文备注", manual.name)
        assertEquals(10, manual.sourceUserId)
        assertEquals(0, manual.capturedFromUserId)
        assertEquals(PassiveBackupStateKind.CLONE, manual.stateKind)
        assertEquals("/data/adb/uclone/snapshots/com.example.app/manual/manual_1", manual.path)
        assertFalse(protected.canDelete)
        assertEquals("当前 CLONE 状态依赖的 MAIN 返回点", protected.deleteBlockedReason)
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

    @Test
    fun executableIndexUnifiesManualHistoryActiveAndProtectedRollbackBackups() {
        val root = Files.createTempDirectory("uclone-workspace-index-backups")
        val packageName = "com.example.app"
        val active = Files.createDirectories(root.resolve("snapshots/$packageName/active"))
        Files.write(active.resolve("manifest.json"), "{\"snapshotSizeKb\":\"11\",\"sourceUser\":\"10\",\"capturedFromUserId\":\"10\",\"stateKind\":\"CLONE\"}\n".toByteArray())
        val history = Files.createDirectories(root.resolve("snapshots/$packageName/history/hist_1"))
        Files.write(history.resolve("manifest.json"), "{\"sizeKb\":\"12\",\"stateKind\":\"CLONE\"}\n".toByteArray())
        val manual = Files.createDirectories(root.resolve("snapshots/$packageName/manual/manual_1"))
        Files.write(manual.resolve("manifest.json"), "{\"sizeKb\":\"13\",\"reason\":\"手动命名备份\",\"stateKind\":\"MAIN\"}\n".toByteArray())
        val metadata = Files.createDirectories(manual.resolve(".uclone-meta"))
        Files.write(metadata.resolve("name"), "微信 主系统\n".toByteArray())
        Files.write(metadata.resolve("source_user_id"), "0\n".toByteArray())
        Files.write(metadata.resolve("captured_from_user_id"), "10\n".toByteArray())
        Files.write(metadata.resolve("source_state_kind"), "MAIN\n".toByteArray())
        val mainRollback = root.resolve("rollback/$packageName/persistent_main")
        createCompleteMainReturn(mainRollback)
        val marker = root.resolve("switches/$packageName/active")
        Files.createDirectories(marker.parent)
        Files.write(marker, "persistent_main\n".toByteArray())

        val result = runShell(workspaceIndexScript(root.toString()))
        val index = WorkspaceIndexParser.parse(result.output)

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "BACKUP\tmanual\t$packageName\tmanual_1")
        assertEquals(
            setOf(BackupKind.ACTIVE, BackupKind.HISTORY, BackupKind.MANUAL, BackupKind.MAIN_ROLLBACK),
            index.backups.map { it.ref.kind }.toSet(),
        )
        val named = index.backups.single { it.ref.kind == BackupKind.MANUAL }
        assertEquals("微信 主系统", named.name)
        assertEquals(0, named.sourceUserId)
        assertEquals(10, named.capturedFromUserId)
        assertEquals(PassiveBackupStateKind.MAIN, named.stateKind)
        assertFalse(index.backups.single { it.ref.kind == BackupKind.MAIN_ROLLBACK }.canDelete)
    }

    @Test
    fun executableIndexUsesStringAndNumericManifestSizesWithoutRunningDu() {
        val root = Files.createTempDirectory("uclone-workspace-index-size")
        val packageName = "com.example.app"
        val mainBackup = root.resolve("rollback/$packageName/persistent_main")
        createCompleteMainReturn(mainBackup)
        Files.write(
            mainBackup.resolve("manifest.json"),
            "{\"stateKind\":\"MAIN\",\"sizeKb\":\"321\"}\n".toByteArray(),
        )
        val cloneBackup = Files.createDirectories(root.resolve("clone_rollback/$packageName/latest"))
        Files.write(cloneBackup.resolve("manifest.json"), "{\"sizeKb\":654}\n".toByteArray())
        val fakeBin = fakeCommand("du", "echo FAKE_DU_CALLED >&2\nexit 91")

        val result = runShell(workspaceIndexScript(root.toString()), fakeBin)
        val main = result.output.lineSequence().first { it.startsWith("MAIN_ROLLBACK\t") }.split('\t')
        val clone = result.output.lineSequence().first { it.startsWith("CLONE_ROLLBACK\t") }.split('\t')

        assertEquals(0, result.exitCode, result.output)
        assertEquals("321", main[4])
        assertEquals("654", clone[4])
        assertFalse("FAKE_DU_CALLED" in result.output)
    }

    @Test
    fun executableIndexFallsBackToDuForLegacyManifestWithoutSize() {
        val root = Files.createTempDirectory("uclone-workspace-index-legacy-size")
        val packageName = "com.example.app"
        val backup = Files.createDirectories(root.resolve("rollback/$packageName/legacy"))
        Files.write(backup.resolve("manifest.json"), "{\"stateKind\":\"MAIN\"}\n".toByteArray())
        val fakeBin = fakeCommand("du", "echo \"777 ${'$'}2\"")

        val result = runShell(workspaceIndexScript(root.toString()), fakeBin)
        val main = result.output.lineSequence().first { it.startsWith("MAIN_ROLLBACK\t") }.split('\t')

        assertEquals(0, result.exitCode, result.output)
        assertEquals("777", main[4])
    }

    @Test
    fun executableIndexRejectsNonIntegerManifestSizesAndFallsBackToDu() {
        val root = Files.createTempDirectory("uclone-workspace-index-invalid-size")
        val packageName = "com.example.app"
        val decimal = Files.createDirectories(root.resolve("rollback/$packageName/decimal"))
        Files.write(decimal.resolve("manifest.json"), "{\"sizeKb\":12.5}\n".toByteArray())
        val exponent = Files.createDirectories(root.resolve("clone_rollback/$packageName/latest"))
        Files.write(exponent.resolve("manifest.json"), "{\"sizeKb\":12e3}\n".toByteArray())
        val fakeBin = fakeCommand("du", "echo \"777 ${'$'}2\"")

        val result = runShell(workspaceIndexScript(root.toString()), fakeBin)
        val main = result.output.lineSequence().first { it.startsWith("MAIN_ROLLBACK\t") }.split('\t')
        val clone = result.output.lineSequence().first { it.startsWith("CLONE_ROLLBACK\t") }.split('\t')

        assertEquals(0, result.exitCode, result.output)
        assertEquals("777", main[4])
        assertEquals("777", clone[4])
    }

    @Test
    fun executableIndexDoesNotCollapseWhitespaceInsideStringSize() {
        val root = Files.createTempDirectory("uclone-workspace-index-spaced-size")
        val packageName = "com.example.app"
        val backup = Files.createDirectories(root.resolve("rollback/$packageName/spaced"))
        Files.write(
            backup.resolve("manifest.json"),
            "{\n  \"stateKind\": \"MAIN\",\n  \"sizeKb\": \"1 2\"\n}\n".toByteArray(),
        )
        val fakeBin = fakeCommand("du", "echo \"777 ${'$'}2\"")

        val result = runShell(workspaceIndexScript(root.toString()), fakeBin)
        val main = result.output.lineSequence().first { it.startsWith("MAIN_ROLLBACK\t") }.split('\t')

        assertEquals(0, result.exitCode, result.output)
        assertEquals("777", main[4])
    }

    @Test
    fun executableIndexFallsBackForNestedOrOverflowingManifestSize() {
        val root = Files.createTempDirectory("uclone-workspace-index-unsafe-size")
        val packageName = "com.example.app"
        val nested = Files.createDirectories(root.resolve("rollback/$packageName/nested"))
        Files.write(
            nested.resolve("manifest.json"),
            "{\"stateKind\":\"MAIN\",\"metadata\":{\"sizeKb\":999}}\n".toByteArray(),
        )
        val overflow = Files.createDirectories(root.resolve("clone_rollback/$packageName/latest"))
        Files.write(
            overflow.resolve("manifest.json"),
            "{\"sizeKb\":9223372036854775808}\n".toByteArray(),
        )
        val fakeBin = fakeCommand("du", "echo \"777 ${'$'}2\"")

        val result = runShell(workspaceIndexScript(root.toString()), fakeBin)
        val main = result.output.lineSequence().first { it.startsWith("MAIN_ROLLBACK\t") }.split('\t')
        val clone = result.output.lineSequence().first { it.startsWith("CLONE_ROLLBACK\t") }.split('\t')

        assertEquals(0, result.exitCode, result.output)
        assertEquals("777", main[4])
        assertEquals("777", clone[4])
    }

    @Test
    fun executableIndexFallsBackForDuplicateTopLevelManifestSize() {
        val root = Files.createTempDirectory("uclone-workspace-index-duplicate-size")
        val packageName = "com.example.app"
        val backup = Files.createDirectories(root.resolve("rollback/$packageName/duplicate"))
        Files.write(
            backup.resolve("manifest.json"),
            "{\"stateKind\":\"MAIN\",\"sizeKb\":12,\"sizeKb\":13}\n".toByteArray(),
        )
        val fakeBin = fakeCommand("du", "echo \"777 ${'$'}2\"")

        val result = runShell(workspaceIndexScript(root.toString()), fakeBin)
        val main = result.output.lineSequence().first { it.startsWith("MAIN_ROLLBACK\t") }.split('\t')

        assertEquals(0, result.exitCode, result.output)
        assertEquals("777", main[4])
    }

    @Test
    fun executableIndexFallsBackForTruncatedOrContaminatedManifest() {
        val root = Files.createTempDirectory("uclone-workspace-index-malformed-size")
        val packageName = "com.example.app"
        val manifests = mapOf(
            "suffix" to "{\"stateKind\":\"MAIN\",\"sizeKb\":12} garbage\n",
            "prefix" to "garbage {\"stateKind\":\"MAIN\",\"sizeKb\":12}\n",
            "trailing-comma" to "{\"stateKind\":\"MAIN\",\"sizeKb\":12,}\n",
            "missing-value" to "{\"stateKind\":\"MAIN\",\"broken\":,\"sizeKb\":12}\n",
            "leading-zero" to "{\"stateKind\":\"MAIN\",\"sizeKb\":012}\n",
            "multiline" to "{\n\"stateKind\":\"MAIN\",\"sizeKb\":12\n}\n",
            "invalid-escape" to "{\"stateKind\":\"MAIN\",\"reason\":\"bad\\q\",\"sizeKb\":12}\n",
            "extra-empty-line" to "{\"stateKind\":\"MAIN\",\"sizeKb\":12}\n\n",
            "nul-byte" to "{\"state\u0000Kind\":\"MAIN\",\"sizeKb\":12}\n",
        )
        manifests.forEach { (id, manifest) ->
            val backup = Files.createDirectories(root.resolve("rollback/$packageName/$id"))
            Files.write(backup.resolve("manifest.json"), manifest.toByteArray())
        }
        val fakeBin = fakeCommand("du", "echo \"777 ${'$'}2\"")

        val result = runShell(workspaceIndexScript(root.toString()), fakeBin)
        val sizes = result.output.lineSequence()
            .filter { it.startsWith("MAIN_ROLLBACK\t") }
            .map { it.split('\t')[4] }
            .toList()

        assertEquals(0, result.exitCode, result.output)
        assertEquals(manifests.size, sizes.size, result.output)
        assertTrue(sizes.all { it == "777" }, result.output)
    }

    private fun createCompleteMainReturn(backup: Path) {
        val state = Files.createDirectories(backup.resolve(".state"))
        Files.write(backup.resolve("manifest.json"), "{\"stateKind\":\"MAIN\"}\n".toByteArray())
        listOf("ce", "de", "external", "media", "obb").forEach { part ->
            Files.write(state.resolve(part), "absent\n".toByteArray())
        }
    }

    private fun fakeCommand(name: String, body: String): Path {
        val directory = Files.createTempDirectory("uclone-fake-bin")
        val command = directory.resolve(name)
        Files.write(command, "#!/bin/sh\n$body\n".toByteArray())
        command.toFile().setExecutable(true)
        return directory
    }

    private fun runShell(script: String, pathPrefix: Path? = null): ShellRun {
        val builder = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true)
        if (pathPrefix != null) {
            builder.environment()["PATH"] = "$pathPrefix:${System.getenv("PATH")}"
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return ShellRun(process.waitFor(), output)
    }

    private data class ShellRun(val exitCode: Int, val output: String)
}

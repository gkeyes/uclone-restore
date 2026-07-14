package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.UCloneSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateBackupShellTest {
    @Test
    fun fixedMainSelectionOnlyUsesAValidatedMainReturnPoint() {
        val script = StateBackupShell.functions()

        assertContains(script, "uclone_valid_state_backup")
        assertContains(script, "MAIN_RETURN_SELECTED:path=${'$'}UCLONE_SSB_PERSISTENT_DIR mode=fixed")
        assertContains(script, "[ \"${'$'}UCLONE_SSB_STATE_KIND\" = \"MAIN\" ] || return 1")
        assertContains(script, "stateKind")
        assertFalse(script.contains("UCLONE_SSB_PERSISTENT_ID=\"persistent_clone\""))
    }

    @Test
    fun switchingKeepsFreshTransactionUndoAndNeverPromotesCloneState() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule("com.example.app"),
            UCloneSettings(),
            "com.uclone.restore",
        )

        assertContains(script, """\"backupKind\":\"transaction_undo\"""")
        assertContains(script, "ROLLBACK_READY=1")
        assertContains(script, "TRANSACTION_COMMITTED=1")
        assertContains(script, "MAIN_RETURN_READY:path=${'$'}UCLONE_STATE_BACKUP_PATH")
        assertContains(script, "MAIN_RETURN_COMMITTED:path=${'$'}UCLONE_STATE_BACKUP_PATH")
        assertContains(script, "[ \"${'$'}CURRENT_TARGET_STATE\" = \"MAIN\" ] && [ \"${'$'}NEXT_MAIN_STATE\" = \"CLONE\" ]")
        assertContains(script, "CLONE_TRANSACTION_UNDO_RETAINED:path=${'$'}ORIGINAL_TRANSACTION_DIR")
        assertContains(script, "SWITCH_MARKER_STAGED=UNKNOWN")
        assertTrue(script.indexOf("ROLLBACK_READY=1") < script.indexOf("uclone_stage_begin RESTORE_DATA"))
        assertTrue(script.lastIndexOf("uclone_stage_begin RESTORE_DATA") < script.lastIndexOf("TRANSACTION_COMMITTED=1"))
        assertTrue(script.lastIndexOf("uclone_promote_transaction_state_backup") < script.lastIndexOf("TRANSACTION_COMMITTED=1"))
        assertTrue(script.lastIndexOf("DATA_STATE_COMMITTED=CLONE") < script.lastIndexOf("TRANSACTION_COMMITTED=1"))
        assertContains(script, "ERR_STATE_BACKUP_PROMOTION_FAILED")
        assertContains(script, "ERR_MAIN_RETURN_AUTO_INIT_FORBIDDEN")
        assertContains(script, "MAIN_RETURN_PROMOTION_REVERTED")
        assertFalse(script.contains("ROLLBACK_ID=\"${'$'}UCLONE_STATE_BACKUP_ID\""))
    }

    @Test
    fun forcedCloneRefreshStopsBeforeMainRestoreWhenPushFails() {
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(),
            appPackage = "com.uclone.restore",
        )

        assertContains(script, "ERR_FORCE_CLONE_REFRESH_PUSH_FAILED")
        assertContains(script, "exit \"${'$'}UCLONE_FORCE_PUSH_EXIT\"")
        assertContains(script, "FORCE_MAIN_RESTORE_BEGIN")
        assertTrue(script.indexOf("ERR_FORCE_CLONE_REFRESH_PUSH_FAILED") < script.indexOf("FORCE_MAIN_RESTORE_BEGIN"))
        assertTrue(script.indexOf("FORCE_CLONE_REFRESH_PRECHECK_OK") < script.indexOf("PUSH_TEMP="))
    }

    @Test
    fun stateBackupValidatorAcceptsCompleteEmptyBackupAndRejectsMissingState() {
        val root = Files.createTempDirectory("uclone-state-validator")
        createStateBackup(root, "complete", "MAIN", PARTS)
        createStateBackup(root, "incomplete", "MAIN", PARTS - "obb")

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120000
                ${StateBackupShell.functions()}
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/complete" MAIN; then echo COMPLETE=VALID; else echo COMPLETE=INVALID; fi
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/incomplete" MAIN; then echo INCOMPLETE=VALID; else echo INCOMPLETE=INVALID; fi
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "COMPLETE=VALID")
        assertContains(result.output, "INCOMPLETE=INVALID")
    }

    @Test
    fun transactionBackupPromotionRequiresCommitAndLeavesMarkerPublicationToTheTransaction() {
        val root = Files.createTempDirectory("uclone-state-promotion")
        createStateBackup(root, "transaction_1", "MAIN", PARTS)
        val marker = root.resolve("switches/com.example.app/active")
        Files.createDirectories(marker.parent)
        Files.write(marker, "transaction_1\n".toByteArray())

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120001
                ${StateBackupShell.functions()}
                UCLONE_READY_TO_COMMIT=0
                if uclone_promote_transaction_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" transaction_1 MAIN 0; then
                  echo BEFORE_COMMIT=UNEXPECTED
                else
                  echo BEFORE_COMMIT=BLOCKED
                fi
                [ -d "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" ] && echo TRANSACTION_BEFORE_COMMIT=PRESENT
                UCLONE_READY_TO_COMMIT=1
                uclone_promote_transaction_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" transaction_1 MAIN 0 || exit 9
                [ ! -e "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" ] && echo TRANSACTION_AFTER_COMMIT=MOVED
                [ -d "${'$'}ROOT/rollback/${'$'}PKG/persistent_main" ] && echo PERSISTENT_AFTER_COMMIT=PRESENT
                printf 'MARKER='; sed -n '1p' "${'$'}ROOT/switches/${'$'}PKG/active"
                grep -F '"backupKind":"persistent_state"' "${'$'}ROOT/rollback/${'$'}PKG/persistent_main/manifest.json" >/dev/null && echo MANIFEST=PERSISTENT
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "BEFORE_COMMIT=BLOCKED")
        assertContains(result.output, "TRANSACTION_BEFORE_COMMIT=PRESENT")
        assertContains(result.output, "TRANSACTION_AFTER_COMMIT=MOVED")
        assertContains(result.output, "PERSISTENT_AFTER_COMMIT=PRESENT")
        assertContains(result.output, "MARKER=transaction_1")
        assertContains(result.output, "MANIFEST=PERSISTENT")
    }

    @Test
    fun mainStateClassificationFailsClosedForEveryNonRegularOrInvalidMarker() {
        val root = Files.createTempDirectory("uclone-state-classification")
        createStateBackup(root, "persistent_main", "MAIN", PARTS)
        createStateBackup(root, "persistent_clone", null, PARTS)

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120002
                ${StateBackupShell.functions()}
                MARKER_DIR="${'$'}ROOT/switches/${'$'}PKG"
                MARKER="${'$'}MARKER_DIR/active"
                mkdir -p "${'$'}MARKER_DIR"
                rm -rf "${'$'}MARKER"
                echo "ABSENT=${'$'}(uclone_current_main_state)"
                ln -s missing "${'$'}MARKER"
                echo "DANGLING=${'$'}(uclone_current_main_state)"
                rm -f "${'$'}MARKER"
                mkdir "${'$'}MARKER"
                echo "DIRECTORY=${'$'}(uclone_current_main_state)"
                rm -rf "${'$'}MARKER"
                printf '%s\n' 'bad/id' > "${'$'}MARKER"
                echo "INVALID=${'$'}(uclone_current_main_state)"
                printf '%s\n' 'persistent_main' > "${'$'}MARKER"
                echo "VALID=${'$'}(uclone_current_main_state)"
                printf '%s\n' 'persistent_clone' > "${'$'}MARKER"
                echo "LEGACY_CLONE=${'$'}(uclone_current_main_state)"
                printf '%s\n' "${'$'}UCLONE_MAIN_STATE_MARKER" > "${'$'}MARKER"
                echo "EXPLICIT_MAIN=${'$'}(uclone_current_main_state)"
                if uclone_confirmed_main_state; then echo MAIN_PROOF=CONFIRMED; else echo MAIN_PROOF=MISSING; fi
                mv "${'$'}MARKER" "${'$'}MARKER.real"
                ln -s "${'$'}MARKER.real" "${'$'}MARKER"
                echo "SYMLINK_MAIN=${'$'}(uclone_current_main_state)"
                if uclone_confirmed_main_state; then echo SYMLINK_PROOF=UNEXPECTED; else echo SYMLINK_PROOF=BLOCKED; fi
                rm -f "${'$'}MARKER"
                if uclone_confirmed_main_state; then echo LEGACY_PROOF=UNEXPECTED; else echo LEGACY_PROOF=MISSING; fi
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "ABSENT=MAIN")
        assertContains(result.output, "DANGLING=UNKNOWN")
        assertContains(result.output, "DIRECTORY=UNKNOWN")
        assertContains(result.output, "INVALID=UNKNOWN")
        assertContains(result.output, "VALID=CLONE")
        assertContains(result.output, "LEGACY_CLONE=UNKNOWN")
        assertContains(result.output, "EXPLICIT_MAIN=MAIN")
        assertContains(result.output, "MAIN_PROOF=CONFIRMED")
        assertContains(result.output, "SYMLINK_MAIN=UNKNOWN")
        assertContains(result.output, "SYMLINK_PROOF=BLOCKED")
        assertContains(result.output, "LEGACY_PROOF=MISSING")
    }

    @Test
    fun fixedMainSelectorRejectsCloneAndManualUpdateValidatesBeforeReplacement() {
        val root = Files.createTempDirectory("uclone-main-selector")
        createStateBackup(root, "persistent_main", "MAIN", PARTS)
        val selection = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120003
                ${StateBackupShell.functions()}
                if uclone_select_transaction_state_backup missing tx MAIN; then
                  echo MAIN_SELECTION=${'$'}UCLONE_STATE_BACKUP_ID
                fi
                if uclone_select_transaction_state_backup missing tx CLONE; then
                  echo CLONE_SELECTION=UNEXPECTED
                else
                  echo CLONE_SELECTION=BLOCKED
                fi
            """.trimIndent(),
        )
        assertEquals(0, selection.exitCode, selection.output)
        assertContains(selection.output, "MAIN_SELECTION=persistent_main")
        assertContains(selection.output, "CLONE_SELECTION=BLOCKED")

        val updateScript = ShellScripts.updateMainReturnPoint(
            packageName = "com.example.app",
            settings = UCloneSettings(),
            appPackage = "com.uclone.restore",
        )
        assertContains(updateScript, "ERR_MAIN_RETURN_UPDATE_STATE:expected=CONFIRMED_MAIN")
        assertContains(updateScript, "uclone_confirmed_main_state")
        assertContains(updateScript, "ERR_UNSAFE_WORKSPACE_ROOT")
        assertContains(updateScript, "ERR_WORKSPACE_SYMLINK")
        assertContains(updateScript, "ERR_UNTRUSTED_WORKSPACE")
        assertContains(updateScript, "uclone_valid_state_backup \"${'$'}TMP\" MAIN")
        assertContains(updateScript, "mv \"${'$'}FINAL\" \"${'$'}PREVIOUS\"")
        assertContains(updateScript, "mv \"${'$'}TMP\" \"${'$'}FINAL\"")
        assertTrue(
            updateScript.indexOf("uclone_valid_state_backup \"${'$'}TMP\" MAIN") <
                updateScript.indexOf("mv \"${'$'}FINAL\" \"${'$'}PREVIOUS\""),
        )
    }

    @Test
    fun firstSwitchOnlySelectsACompleteTransactionAsTheInitialMainReturnPoint() {
        val root = Files.createTempDirectory("uclone-initial-main-selector")
        createStateBackup(root, "transaction_complete", "MAIN", PARTS)
        createStateBackup(root, "transaction_incomplete", "MAIN", PARTS - "obb")

        val selection = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120004
                ${StateBackupShell.functions()}
                if uclone_select_transaction_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_complete" transaction_complete MAIN; then
                  echo COMPLETE_SELECTION=${'$'}UCLONE_STATE_BACKUP_ID
                else
                  echo COMPLETE_SELECTION=REJECTED
                fi
                if uclone_select_transaction_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_incomplete" transaction_incomplete MAIN; then
                  echo INCOMPLETE_SELECTION=UNEXPECTED
                else
                  echo INCOMPLETE_SELECTION=REJECTED
                fi
            """.trimIndent(),
        )

        assertEquals(0, selection.exitCode, selection.output)
        assertContains(selection.output, "COMPLETE_SELECTION=transaction_complete")
        assertContains(selection.output, "INCOMPLETE_SELECTION=REJECTED")
    }

    @Test
    fun promotedReturnPointCanBeRevertedBeforeStatePublication() {
        val root = Files.createTempDirectory("uclone-promotion-revert")
        createStateBackup(root, "persistent_main", "MAIN", PARTS)
        createStateBackup(root, "transaction_1", "MAIN", PARTS)
        Files.write(
            root.resolve("rollback/com.example.app/transaction_1/manifest.json"),
            "{\"stateKind\":\"MAIN\",\"backupKind\":\"transaction_undo\"}\n".toByteArray(),
        )

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120005
                RUN_ID=20260713-120005_42
                ${StateBackupShell.functions()}
                UCLONE_READY_TO_COMMIT=1
                uclone_promote_transaction_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" transaction_1 MAIN 0 || exit 9
                uclone_revert_promoted_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" || exit 10
                [ -d "${'$'}ROOT/rollback/${'$'}PKG/persistent_main" ] && echo FIXED=RESTORED
                [ -d "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" ] && echo TRANSACTION=RESTORED
                grep -F '"backupKind":"transaction_undo"' "${'$'}ROOT/rollback/${'$'}PKG/transaction_1/manifest.json" >/dev/null && echo MANIFEST=RESTORED
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "FIXED=RESTORED")
        assertContains(result.output, "TRANSACTION=RESTORED")
        assertContains(result.output, "MANIFEST=RESTORED")
    }

    private fun createStateBackup(
        root: Path,
        id: String,
        stateKind: String?,
        parts: Set<String>,
    ) {
        val backup = root.resolve("rollback/com.example.app/$id")
        val stateDirectory = backup.resolve(".state")
        Files.createDirectories(stateDirectory)
        val manifest = stateKind?.let { "{\"stateKind\":\"$it\"}\n" } ?: "{}\n"
        Files.write(backup.resolve("manifest.json"), manifest.toByteArray())
        parts.forEach { part -> Files.write(stateDirectory.resolve(part), "absent\n".toByteArray()) }
    }

    private fun runShell(script: String): ShellRun {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return ShellRun(process.waitFor(), output)
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private data class ShellRun(val exitCode: Int, val output: String)

    private companion object {
        val PARTS = setOf("ce", "de", "external", "media", "obb")
    }
}

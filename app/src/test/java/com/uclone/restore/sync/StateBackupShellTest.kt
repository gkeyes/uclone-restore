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
    fun reuseOnlySelectsAValidatedPersistentStateBackup() {
        val script = StateBackupShell.functions(reuseExisting = true)

        assertContains(script, "UCLONE_REUSE_EXISTING_STATE_BACKUPS=1")
        assertContains(script, "uclone_valid_state_backup")
        assertContains(script, "state=${'$'}UCLONE_SSB_STATE_KIND path=${'$'}UCLONE_SSB_PERSISTENT_DIR mode=reuse")
        assertContains(script, "stateKind")
    }

    @Test
    fun switchingKeepsFreshTransactionUndoUntilCommitEvenWhenReuseIsEnabled() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule("com.example.app"),
            UCloneSettings(reuseExistingPassiveBackups = true),
            "com.uclone.restore",
        )

        assertContains(script, "backupKind\":\"transaction_undo")
        assertContains(script, "ROLLBACK_READY=1")
        assertContains(script, "TRANSACTION_COMMITTED=1")
        assertContains(script, "STATE_BACKUP_COMMITTED:state=${'$'}CURRENT_TARGET_STATE")
        assertContains(script, "SWITCH_MARKER_STAGED=UNKNOWN")
        assertTrue(script.indexOf("ROLLBACK_READY=1") < script.indexOf("uclone_stage_begin RESTORE_DATA"))
        assertTrue(script.lastIndexOf("uclone_stage_begin RESTORE_DATA") < script.lastIndexOf("TRANSACTION_COMMITTED=1"))
        assertTrue(script.lastIndexOf("TRANSACTION_COMMITTED=1") < script.lastIndexOf("uclone_promote_transaction_state_backup"))
        assertTrue(script.lastIndexOf("TRANSACTION_COMMITTED=1") < script.lastIndexOf("DATA_STATE_COMMITTED=CLONE"))
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
                ${StateBackupShell.functions(reuseExisting = true)}
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
        Files.writeString(marker, "transaction_1\n")

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120001
                ${StateBackupShell.functions(reuseExisting = false)}
                TRANSACTION_COMMITTED=0
                if uclone_promote_transaction_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" transaction_1 MAIN 0; then
                  echo BEFORE_COMMIT=UNEXPECTED
                else
                  echo BEFORE_COMMIT=BLOCKED
                fi
                [ -d "${'$'}ROOT/rollback/${'$'}PKG/transaction_1" ] && echo TRANSACTION_BEFORE_COMMIT=PRESENT
                TRANSACTION_COMMITTED=1
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

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120002
                ${StateBackupShell.functions(reuseExisting = true)}
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
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "ABSENT=MAIN")
        assertContains(result.output, "DANGLING=UNKNOWN")
        assertContains(result.output, "DIRECTORY=UNKNOWN")
        assertContains(result.output, "INVALID=UNKNOWN")
        assertContains(result.output, "VALID=CLONE")
    }

    private fun createStateBackup(
        root: Path,
        id: String,
        stateKind: String,
        parts: Set<String>,
    ) {
        val backup = root.resolve("rollback/com.example.app/$id")
        val stateDirectory = backup.resolve(".state")
        Files.createDirectories(stateDirectory)
        Files.writeString(backup.resolve("manifest.json"), "{\"stateKind\":\"$stateKind\"}\n")
        parts.forEach { part -> Files.writeString(stateDirectory.resolve(part), "absent\n") }
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

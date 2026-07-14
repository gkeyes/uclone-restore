package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CloneSessionPolicy
import com.uclone.restore.model.MainReturnPointPolicy
import com.uclone.restore.model.SwitchSafetyMode
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
    fun refreshPolicySelectsTheFreshTransactionOnlyForConfirmedMain() {
        val root = Files.createTempDirectory("uclone-main-refresh-selector")
        createStateBackup(root, "persistent_main", "MAIN", PARTS)
        createStateBackup(root, "transaction_confirmed", "MAIN", PARTS)
        createStateBackup(root, "transaction_inferred", "MAIN", PARTS)

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260714-120000
                ${StateBackupShell.functions()}
                uclone_select_transaction_state_backup \
                  "${'$'}ROOT/rollback/${'$'}PKG/transaction_confirmed" \
                  transaction_confirmed MAIN REFRESH_ON_MAIN_EXIT 1 || exit 9
                echo CONFIRMED=${'$'}UCLONE_STATE_BACKUP_ID:${'$'}UCLONE_STATE_BACKUP_REUSED
                uclone_select_transaction_state_backup \
                  "${'$'}ROOT/rollback/${'$'}PKG/transaction_inferred" \
                  transaction_inferred MAIN REFRESH_ON_MAIN_EXIT 0 || exit 10
                echo INFERRED=${'$'}UCLONE_STATE_BACKUP_ID:${'$'}UCLONE_STATE_BACKUP_REUSED
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "CONFIRMED=transaction_confirmed:0")
        assertContains(result.output, "MAIN_RETURN_SELECTED:path=${root}/rollback/com.example.app/transaction_confirmed mode=refresh")
        assertContains(result.output, "WARN_MAIN_RETURN_REFRESH_SKIPPED:reason=main_state_not_confirmed")
        assertContains(result.output, "INFERRED=persistent_main:1")
    }

    @Test
    fun forwardSwitchUsesTheConfiguredMainReturnPolicyWithoutAddingACopyPass() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule("com.example.app"),
            UCloneSettings(mainReturnPointPolicy = MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT),
            "com.uclone.restore",
        )

        assertContains(script, "MAIN_RETURN_POLICY='REFRESH_ON_MAIN_EXIT'")
        assertContains(script, "CURRENT_MAIN_CONFIRMED=1")
        assertContains(script, "WARN_MAIN_RETURN_REFRESH_SKIPPED:reason=main_state_not_confirmed")
        assertContains(script, "UCLONE_COPY_PASS_CONTRACT:expected=2")
        assertTrue(script.indexOf("ERR_MAIN_RETURN_INVALID:${'$'}PERSISTENT_MAIN_DIR") < script.indexOf("uclone_stage_begin TARGET_STOP"))
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
    fun safeReturnUsesCheckpointAndThreeCopyPasses() {
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(),
            appPackage = "com.uclone.restore",
        )

        assertContains(script, "UCLONE_SWITCH_MODE=SAFE")
        assertContains(script, "uclone_copy_pass_begin clone_checkpoint")
        assertContains(script, "uclone_copy_pass_begin checkpoint_to_user10")
        assertContains(script, "uclone_copy_pass_begin 'fixed_main_to_user0'")
        assertContains(script, "sync_workspace_part_to_clone")
        assertContains(script, "SWITCH_PUSHED_STATE:${'$'}ACPS_NAME state=empty")
        assertContains(script, "RESTORED_STATE:${'$'}PART_NAME state=absent")
        assertContains(script, "UCLONE_COPY_PASS_CONTRACT:expected=3")
        assertContains(script, "ROLLBACK_READY=1")
        assertContains(script, "SWITCH_CHECKPOINT_CLEANED")
        assertFalse(script.contains("PUSH_TEMP="))
        assertFalse(script.contains("CLONE_ROLLBACK_PREPARED="))
        assertTrue(script.indexOf("checkpoint_to_user10") < script.indexOf("fixed_main_to_user0"))
    }

    @Test
    fun dangerousReturnUsesTwoPassesAndFailsClosedWithoutLocalRollback() {
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(switchSafetyMode = SwitchSafetyMode.DANGEROUS_FAST),
            appPackage = "com.uclone.restore",
        )

        assertContains(script, "UCLONE_SWITCH_MODE=DANGEROUS_FAST")
        assertContains(script, "uclone_copy_pass_begin live_user0_to_user10")
        assertContains(script, "uclone_copy_pass_begin 'fixed_main_to_user0'")
        assertContains(script, "sync_live_part_to_clone")
        assertContains(script, "SWITCH_PUSHED_STATE:${'$'}ACPS_NAME state=absent")
        assertContains(script, "UCLONE_COPY_PASS_CONTRACT:expected=2")
        assertContains(script, "ROLLBACK_READY=0")
        assertContains(script, "DANGEROUS_NO_LOCAL_ROLLBACK=1")
        assertContains(script, "RECOVERY_REQUIRED:mode=${'$'}{UCLONE_RETURN_PLAN:-DANGEROUS_FAST}")
        assertContains(script, "RECOVERY_REQUIRED:mode=SYNC_FAST target=user10 reason=partial_sync")
        assertContains(script, "dangerous_prepare_on_exit")
        assertFalse(script.contains("uclone_copy_pass_begin clone_checkpoint"))
    }

    @Test
    fun discardSafeUsesAUser0CheckpointAndNeverAccessesUser10() {
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(cloneSessionPolicy = CloneSessionPolicy.DISCARD_ON_MAIN_RETURN),
            appPackage = "com.uclone.restore",
        )

        assertContains(script, "UCLONE_RETURN_PLAN=DISCARD_SAFE")
        assertContains(script, "uclone_copy_pass_begin 'clone_discard_checkpoint'")
        assertContains(script, "uclone_copy_pass_begin 'fixed_main_to_user0'")
        assertContains(script, "UCLONE_COPY_PASS_CONTRACT:expected=2")
        assertContains(script, "ROLLBACK_READY=1")
        assertContains(script, "SWITCH_CHECKPOINT_CLEANED")
        assertTrue(script.indexOf("clone_discard_checkpoint") < script.indexOf("fixed_main_to_user0"))
        assertNoCloneUserAccess(script)
    }

    @Test
    fun discardFastOnlyRestoresFixedMainAndFailsClosedWithoutRollback() {
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(
                cloneSessionPolicy = CloneSessionPolicy.DISCARD_ON_MAIN_RETURN,
                switchSafetyMode = SwitchSafetyMode.DANGEROUS_FAST,
            ),
            appPackage = "com.uclone.restore",
        )

        assertContains(script, "UCLONE_RETURN_PLAN=DISCARD_FAST")
        assertContains(script, "uclone_copy_pass_begin 'fixed_main_to_user0'")
        assertContains(script, "UCLONE_COPY_PASS_CONTRACT:expected=1")
        assertContains(script, "ROLLBACK_READY=0")
        assertContains(script, "DANGEROUS_NO_LOCAL_ROLLBACK=1")
        assertContains(script, "RECOVERY_REQUIRED:mode=${'$'}{UCLONE_RETURN_PLAN:-DANGEROUS_FAST}")
        assertFalse(script.contains("clone_discard_checkpoint"))
        assertNoCloneUserAccess(script)
    }

    @Test
    fun discardReturnPrecheckExecutesWithoutAnyCloneUserCommand() {
        val root = Files.createTempDirectory("uclone-discard-return-precheck")
        createStateBackup(root, "persistent_main", "MAIN", PARTS)

        listOf(true to "DISCARD_SAFE", false to "DISCARD_FAST").forEach { (safe, plan) ->
            val preparation = OptimizedSwitchPreparationShell.discardReturn(
                AppRule("com.example.app"),
                UCloneSettings(),
                "persistent_main",
                safe,
            )
            val result = runShell(
                """
                    set -u
                    ROOT=${shellQuote(root.toString())}
                    PKG=com.example.app
                    TS=20260714-120010
                    ${StateBackupShell.functions()}
                    uclone_current_main_state() { echo CLONE; }
                    uclone_read_main_return_id() { echo persistent_main; }
                    cmd() { echo UNEXPECTED_CMD >&2; return 99; }
                    am() { echo UNEXPECTED_AM >&2; return 99; }
                    $preparation
                """.trimIndent(),
            )

            assertEquals(0, result.exitCode, result.output)
            assertContains(
                result.output,
                "SWITCH_RETURN_PRECHECK:plan=$plan mainUser=0 mainReturn=persistent_main cloneUserAccess=none",
            )
            assertFalse(result.output.contains("UNEXPECTED_"), result.output)
        }
    }

    @Test
    fun compositeReturnOnlyRestoresSelectedPartsAndUsesTheAppPermissionRule() {
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule(
                packageName = "com.example.app",
                includeCe = true,
                includeDe = false,
                includeExternal = false,
                includeMedia = false,
                includeObb = false,
                includePermissions = false,
            ),
            settings = UCloneSettings(includePermissions = true),
            appPackage = "com.uclone.restore",
        )

        assertContains(script, "restore_part \"${'$'}ACTIVE/ce\"")
        assertFalse(script.contains("restore_part \"${'$'}ACTIVE/de\""))
        assertFalse(script.contains("restore_part \"${'$'}ACTIVE/external\""))
        assertFalse(script.contains("restore_part \"${'$'}ACTIVE/media\""))
        assertFalse(script.contains("restore_part \"${'$'}ACTIVE/obb\""))
        assertFalse(script.contains("uclone_restore_permission_state \"${'$'}ACTIVE/permissions\""))
        assertFalse(script.contains("uclone_capture_permission_state \"${'$'}ROLLBACK/permissions\" \"${'$'}MAIN_USER\""))
    }

    @Test
    fun selectedCePreservesEmptyAndAbsentStatesInsteadOfRequiringData() {
        val forward = ShellScripts.switchFromCloneLatest(
            packageName = "com.example.app",
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(),
            appPackage = "com.uclone.restore",
        )
        val safeReturn = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule("com.example.app"),
            settings = UCloneSettings(),
            appPackage = "com.uclone.restore",
        )

        assertContains(forward, "data|empty|absent)")
        assertFalse(forward.contains("ERR_SWITCH_CE_MISSING"))
        assertContains(safeReturn, "sync_workspace_part_to_clone \"ce\"")
        assertFalse(safeReturn.contains("ERR_REQUIRED_SWITCH_PART_STATE:ce"))
    }

    @Test
    fun ceOnlyReturnMarksUnselectedPartsAndNeverRestoresOrRollsThemBack() {
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            packageName = "com.example.app",
            rollbackId = "persistent_main",
            rule = AppRule(
                packageName = "com.example.app",
                includeCe = true,
                includeDe = false,
                includeExternal = false,
                includeMedia = false,
                includeObb = false,
                includePermissions = false,
            ),
            settings = UCloneSettings(),
            appPackage = "com.uclone.restore",
        )

        listOf("de", "external", "media", "obb").forEach { part ->
            assertContains(script, "mark_switch_part_unselected $part")
            assertFalse(script.contains("restore_part \"${'$'}ACTIVE/$part\""), part)
            assertFalse(script.contains("restore_rollback_part \"${'$'}ROLLBACK/$part\""), part)
        }
        assertContains(script, "restore_part \"${'$'}ACTIVE/ce\"")
        assertContains(script, "restore_rollback_part \"${'$'}ROLLBACK/ce\"")
    }

    @Test
    fun stateBackupValidatorAcceptsUnselectedWithoutPayloadAndRejectsItsPayload() {
        val root = Files.createTempDirectory("uclone-unselected-validator")
        createStateBackup(root, "valid", "CLONE", PARTS)
        createStateBackup(root, "invalid", "CLONE", PARTS)
        createStateBackup(root, "file-payload", "CLONE", PARTS)
        createStateBackup(root, "link-payload", "CLONE", PARTS)
        val valid = root.resolve("rollback/com.example.app/valid")
        val invalid = root.resolve("rollback/com.example.app/invalid")
        val filePayload = root.resolve("rollback/com.example.app/file-payload")
        val linkPayload = root.resolve("rollback/com.example.app/link-payload")
        Files.write(valid.resolve(".state/de"), "unselected\n".toByteArray())
        Files.write(invalid.resolve(".state/de"), "unselected\n".toByteArray())
        Files.write(filePayload.resolve(".state/de"), "unselected\n".toByteArray())
        Files.write(linkPayload.resolve(".state/de"), "unselected\n".toByteArray())
        Files.createDirectories(invalid.resolve("de"))
        Files.write(invalid.resolve("de/unexpected"), "payload\n".toByteArray())
        writeText(filePayload.resolve("de"), "payload")
        Files.createSymbolicLink(linkPayload.resolve("de"), valid.resolve(".state/de"))

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120007
                ${StateBackupShell.functions()}
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/valid" CLONE; then echo UNSELECTED=VALID; else echo UNSELECTED=INVALID; fi
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/invalid" CLONE; then echo PAYLOAD=UNEXPECTED; else echo PAYLOAD=REJECTED; fi
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/file-payload" CLONE; then echo FILE=UNEXPECTED; else echo FILE=REJECTED; fi
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/link-payload" CLONE; then echo LINK=UNEXPECTED; else echo LINK=REJECTED; fi
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "UNSELECTED=VALID")
        assertContains(result.output, "PAYLOAD=REJECTED")
        assertContains(result.output, "FILE=REJECTED")
        assertContains(result.output, "LINK=REJECTED")
        assertFalse(result.output.contains("PAYLOAD=UNEXPECTED"))
    }

    @Test
    fun stateBackupValidatorKeepsLegacyEmptyDirectoriesButRejectsUnsafeEmptyStatePayloads() {
        val root = Files.createTempDirectory("uclone-empty-state-validator")
        createStateBackup(root, "legacy-empty-dir", "MAIN", PARTS)
        createStateBackup(root, "file-payload", "MAIN", PARTS)
        createStateBackup(root, "link-payload", "MAIN", PARTS)
        createStateBackup(root, "nonempty-dir", "MAIN", PARTS)
        val legacy = root.resolve("rollback/com.example.app/legacy-empty-dir")
        val file = root.resolve("rollback/com.example.app/file-payload")
        val link = root.resolve("rollback/com.example.app/link-payload")
        val nonempty = root.resolve("rollback/com.example.app/nonempty-dir")
        listOf(legacy, file, link, nonempty).forEach {
            Files.write(it.resolve(".state/de"), "empty\n".toByteArray())
        }
        Files.createDirectories(legacy.resolve("de"))
        writeText(file.resolve("de"), "payload")
        Files.createSymbolicLink(link.resolve("de"), legacy.resolve(".state/de"))
        Files.createDirectories(nonempty.resolve("de"))
        writeText(nonempty.resolve("de/unexpected"), "payload")

        val result = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120008
                ${StateBackupShell.functions()}
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/legacy-empty-dir" MAIN; then echo LEGACY=VALID; else echo LEGACY=INVALID; fi
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/file-payload" MAIN; then echo FILE=UNEXPECTED; else echo FILE=REJECTED; fi
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/link-payload" MAIN; then echo LINK=UNEXPECTED; else echo LINK=REJECTED; fi
                if uclone_valid_state_backup "${'$'}ROOT/rollback/${'$'}PKG/nonempty-dir" MAIN; then echo NONEMPTY=UNEXPECTED; else echo NONEMPTY=REJECTED; fi
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "LEGACY=VALID")
        assertContains(result.output, "FILE=REJECTED")
        assertContains(result.output, "LINK=REJECTED")
        assertContains(result.output, "NONEMPTY=REJECTED")
    }

    @Test
    fun forwardSwitchTempCleanupNeverDeletesACollisionItDidNotCreate() {
        val root = Files.createTempDirectory("uclone-forward-collision")
        val temp = root.resolve("tmp/switch_com.example.app_test_run")
        Files.createDirectories(temp)
        writeText(temp.resolve("keep.txt"), "existing")
        val function = shellFunction(
            script = ShellScripts.switchFromCloneLatest(
                "com.example.app",
                AppRule("com.example.app"),
                UCloneSettings(),
                "com.uclone.restore",
            ),
            name = "cleanup_switch_temp",
        )

        val result = runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                RUN_ID=test_run
                SWITCH_TEMP=${shellQuote(temp.toString())}
                SWITCH_TEMP_CREATED=0
                $function
                cleanup_switch_temp
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertEquals("existing", readText(temp.resolve("keep.txt")))
    }

    @Test
    fun safePreparationRemovesIncompleteCheckpointBeforeUser10Mutation() {
        val root = Files.createTempDirectory("uclone-safe-checkpoint-cleanup")
        val checkpoint = root.resolve("rollback/com.example.app/switch_checkpoint_test")
        Files.createDirectories(checkpoint)
        Files.write(checkpoint.resolve("partial"), "partial\n".toByteArray())
        val function = shellFunction(
            ShellScripts.pushMainToCloneThenRestoreMain(
                "com.example.app",
                "persistent_main",
                AppRule("com.example.app"),
                UCloneSettings(),
                "com.uclone.restore",
            ),
            "safe_prepare_on_exit",
        )

        val result = runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                ROLLBACK=${shellQuote(checkpoint.toString())}
                SAFE_CHECKPOINT_CREATED=1
                SAFE_CHECKPOINT_COMPLETE=0
                CLONE_TARGET_MUTATED=0
                $function
                trap safe_prepare_on_exit EXIT
                false
            """.trimIndent(),
        )

        assertEquals(1, result.exitCode, result.output)
        assertFalse(Files.exists(checkpoint), result.output)
    }

    @Test
    fun safePreparationRemovesCheckpointWhenStateDirectoryCreationFails() {
        val root = Files.createTempDirectory("uclone-safe-state-dir-failure")
        val checkpoint = root.resolve("rollback/com.example.app/switch_checkpoint_test")
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            "com.example.app",
            "persistent_main",
            AppRule("com.example.app"),
            UCloneSettings(switchSafetyMode = SwitchSafetyMode.SAFE),
            "com.uclone.restore",
        )
        val exitHandler = shellFunction(script, "safe_prepare_on_exit")
        val createCheckpoint = shellFunction(script, "create_safe_checkpoint_dir")

        val result = runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                ROLLBACK=${shellQuote(checkpoint.toString())}
                SAFE_CHECKPOINT_CREATED=0
                SAFE_CHECKPOINT_COMPLETE=0
                SAFE_PREPARATION_COMPLETE=0
                CLONE_TARGET_MUTATED=0
                mkdir() {
                  case "${'$'}*" in
                    *"${'$'}ROLLBACK/.state"*) return 1 ;;
                    *) command mkdir "${'$'}@" ;;
                  esac
                }
                $exitHandler
                $createCheckpoint
                trap safe_prepare_on_exit EXIT
                create_safe_checkpoint_dir
            """.trimIndent(),
        )

        assertEquals(54, result.exitCode, result.output)
        assertFalse(Files.exists(checkpoint), result.output)
    }

    @Test
    fun safeCleanupNeverDeletesACheckpointItDidNotCreate() {
        val root = Files.createTempDirectory("uclone-safe-collision")
        val checkpoint = root.resolve("rollback/com.example.app/switch_checkpoint_existing")
        Files.createDirectories(checkpoint)
        writeText(checkpoint.resolve("keep.txt"), "existing")
        val function = shellFunction(
            script = ShellScripts.pushMainToCloneThenRestoreMain(
                "com.example.app",
                "persistent_main",
                AppRule("com.example.app"),
                UCloneSettings(switchSafetyMode = SwitchSafetyMode.SAFE),
                "com.uclone.restore",
            ),
            name = "safe_prepare_on_exit",
        )

        val result = runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                ROLLBACK=${shellQuote(checkpoint.toString())}
                SAFE_CHECKPOINT_CREATED=0
                SAFE_CHECKPOINT_COMPLETE=0
                CLONE_TARGET_MUTATED=0
                $function
                trap safe_prepare_on_exit EXIT
                false
            """.trimIndent(),
        )

        assertEquals(1, result.exitCode, result.output)
        assertEquals("existing", readText(checkpoint.resolve("keep.txt")))
    }

    @Test
    fun freshRollbackCleanupNeverDeletesACollisionItDidNotCreate() {
        val root = Files.createTempDirectory("uclone-fresh-collision")
        val rollback = root.resolve("rollback/com.example.app/transaction_existing")
        Files.createDirectories(rollback)
        writeText(rollback.resolve("keep.txt"), "existing")
        val function = shellFunction(
            script = ShellScripts.switchFromCloneLatest(
                "com.example.app",
                AppRule("com.example.app"),
                UCloneSettings(),
                "com.uclone.restore",
            ),
            name = "cleanup_restore_before_transaction",
        )

        val result = runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                ROLLBACK=${shellQuote(rollback.toString())}
                ROLLBACK_CREATED=0
                ROLLBACK_FINALIZED=0
                cleanup_restore_prepared() { :; }
                cleanup_on_exit() { :; }
                $function
                cleanup_restore_before_transaction
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertEquals("existing", readText(rollback.resolve("keep.txt")))
    }

    @Test
    fun freshRollbackPreparationRemovesDirectoryWhenStateDirectoryCreationFails() {
        val root = Files.createTempDirectory("uclone-fresh-state-dir-failure")
        val rollback = root.resolve("rollback/com.example.app/transaction_test")
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule("com.example.app"),
            UCloneSettings(),
            "com.uclone.restore",
        )
        val exitHandler = shellFunction(script, "cleanup_restore_before_transaction")
        val createRollback = shellFunction(script, "create_fresh_rollback_dir")

        val result = runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                ROLLBACK=${shellQuote(rollback.toString())}
                PREPARED_ROOT=${shellQuote(root.resolve("prepared").toString())}
                ROLLBACK_CREATED=0
                ROLLBACK_FINALIZED=0
                cleanup_restore_prepared() { :; }
                mkdir() {
                  case "${'$'}*" in
                    *"${'$'}ROLLBACK/.state"*) return 1 ;;
                    *) command mkdir "${'$'}@" ;;
                  esac
                }
                $exitHandler
                $createRollback
                trap cleanup_restore_before_transaction EXIT
                create_fresh_rollback_dir
            """.trimIndent(),
        )

        assertEquals(54, result.exitCode, result.output)
        assertFalse(Files.exists(rollback), result.output)
    }

    @Test
    fun dangerousTempCleanupNeverDeletesACollisionItDidNotCreate() {
        val root = Files.createTempDirectory("uclone-danger-collision")
        val temp = root.resolve("tmp/dangerous_switch_com.example.app_test_run")
        Files.createDirectories(temp)
        writeText(temp.resolve("keep.txt"), "existing")
        val function = shellFunction(
            script = returnScript(SwitchSafetyMode.DANGEROUS_FAST),
            name = "cleanup_switch_temp",
        )

        val result = runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                RUN_ID=test_run
                DANGER_META=${shellQuote(temp.toString())}
                DANGER_META_CREATED=0
                $function
                cleanup_switch_temp
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertEquals("existing", readText(temp.resolve("keep.txt")))
    }

    @Test
    fun partialUser10MutationIsFatalInBothReturnModes() {
        val safe = generatedExitHandlerRun(SwitchSafetyMode.SAFE, "safe_prepare_on_exit")
        val dangerous = generatedExitHandlerRun(SwitchSafetyMode.DANGEROUS_FAST, "dangerous_prepare_on_exit")

        assertEquals(91, safe.exitCode, safe.output)
        assertContains(safe.output, "RECOVERY_REQUIRED:mode=SYNC_SAFE target=user10 reason=partial_sync")
        assertContains(safe.output, "CLONE_LIFECYCLE_CLEANUP")
        assertFalse(safe.output.contains("AUTO_ROLLBACK_FAILED"), safe.output)
        assertEquals(91, dangerous.exitCode, dangerous.output)
        assertContains(dangerous.output, "RECOVERY_REQUIRED:mode=SYNC_FAST target=user10 reason=partial_sync")
        assertContains(dangerous.output, "CLONE_LIFECYCLE_CLEANUP")
        assertFalse(dangerous.output.contains("AUTO_ROLLBACK_FAILED"), dangerous.output)
    }

    @Test
    fun preparationFailureTrapIsInstalledAfterCloneLifecycleCleanupTrap() {
        SwitchSafetyMode.entries.forEach { mode ->
            val script = returnScript(mode)
            val cleanupTrap = script.indexOf("trap cleanup_on_exit EXIT")
            val modeTrap = when (mode) {
                SwitchSafetyMode.SAFE -> script.indexOf("trap safe_prepare_on_exit EXIT")
                SwitchSafetyMode.DANGEROUS_FAST -> script.indexOf("trap dangerous_prepare_on_exit EXIT")
            }

            assertTrue(cleanupTrap >= 0, mode.name)
            assertTrue(modeTrap > cleanupTrap, mode.name)
        }
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
    fun ordinarySwitchRefusesToReplaceAnExistingInvalidFixedMainReturnPoint() {
        val root = Files.createTempDirectory("uclone-invalid-fixed-main")
        createStateBackup(root, "transaction_complete", "MAIN", PARTS)
        createStateBackup(root, "persistent_main", "MAIN", PARTS - "obb")

        val selection = runShell(
            """
                set -u
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=20260713-120006
                ${StateBackupShell.functions()}
                if uclone_select_transaction_state_backup "${'$'}ROOT/rollback/${'$'}PKG/transaction_complete" transaction_complete MAIN; then
                  echo INVALID_FIXED=REPLACED
                else
                  echo INVALID_FIXED=BLOCKED
                fi
                [ -d "${'$'}ROOT/rollback/${'$'}PKG/persistent_main" ] && echo INVALID_FIXED=PRESERVED
            """.trimIndent(),
        )

        assertEquals(0, selection.exitCode, selection.output)
        assertContains(selection.output, "ERR_MAIN_RETURN_INVALID:")
        assertContains(selection.output, "INVALID_FIXED=BLOCKED")
        assertContains(selection.output, "INVALID_FIXED=PRESERVED")
        assertFalse(selection.output.contains("INVALID_FIXED=REPLACED"))
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

    private fun writeText(path: Path, value: String) {
        Files.write(path, value.toByteArray())
    }

    private fun readText(path: Path): String = String(Files.readAllBytes(path))

    private fun generatedExitHandlerRun(mode: SwitchSafetyMode, functionName: String): ShellRun {
        val root = Files.createTempDirectory("uclone-${mode.name.lowercase()}-failure")
        val checkpoint = root.resolve("rollback/com.example.app/switch_checkpoint_test")
        Files.createDirectories(checkpoint)
        val script = ShellScripts.pushMainToCloneThenRestoreMain(
            "com.example.app",
            "persistent_main",
            AppRule("com.example.app"),
            UCloneSettings(switchSafetyMode = mode),
            "com.uclone.restore",
        )
        val function = shellFunction(script, functionName)
        val lifecycleCleanup = shellFunction(script, "cleanup_on_exit")
        return runShell(
            """
                ROOT=${shellQuote(root.toString())}
                PKG=com.example.app
                TS=test
                ROLLBACK=${shellQuote(checkpoint.toString())}
                DANGER_META=${shellQuote(root.resolve("tmp/dangerous_switch_com.example.app_test").toString())}
                SAFE_CHECKPOINT_CREATED=1
                SAFE_CHECKPOINT_COMPLETE=1
                SAFE_PREPARATION_COMPLETE=0
                DANGEROUS_PREPARATION_COMPLETE=0
                CLONE_TARGET_MUTATED=1
                cleanup_switch_temp() { echo TEMP_CLEANUP; }
                cleanup_clone_user() { echo CLONE_LIFECYCLE_CLEANUP; }
                $lifecycleCleanup
                $function
                trap cleanup_on_exit EXIT
                trap $functionName EXIT
                false
            """.trimIndent(),
        )
    }

    private fun returnScript(mode: SwitchSafetyMode): String =
        ShellScripts.pushMainToCloneThenRestoreMain(
            "com.example.app",
            "persistent_main",
            AppRule("com.example.app"),
            UCloneSettings(switchSafetyMode = mode),
            "com.uclone.restore",
        )

    private fun shellFunction(script: String, name: String): String =
        Regex("(?ms)^[ \\t]*${Regex.escape(name)}\\(\\) \\{\\n.*?^[ \\t]*\\}")
            .find(script)
            ?.value
            ?: error("Missing generated shell function: $name")

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

    private fun assertNoCloneUserAccess(script: String) {
        assertFalse(script.contains("ENSURE_CLONE_CE_BEGIN"), "must not start or unlock user10")
        assertFalse(script.contains("CLONE_USER="), "must not resolve the clone user")
        assertFalse(script.contains("sync_workspace_part_to_clone"), "must not copy a checkpoint to user10")
        assertFalse(script.contains("sync_live_part_to_clone"), "must not copy live user0 data to user10")
        assertFalse(script.contains("cleanup_clone_user"), "must not stop user10")
        assertFalse(script.contains("checkpoint_to_user10"), "must not emit a user10 copy pass")
        assertFalse(script.contains("live_user0_to_user10"), "must not emit a user10 copy pass")
    }

    private data class ShellRun(val exitCode: Int, val output: String)

    private companion object {
        val PARTS = setOf("ce", "de", "external", "media", "obb")
    }
}

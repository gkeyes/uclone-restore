package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.PermissionRestoreMode
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.root.shellQuote
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellScriptsTest {
    @Test
    fun storagePreflightUsesAwkForDeviceSizeArithmetic() {
        val script = ShellScripts.storagePreflightScript()

        assertContains(script, "uclone_decimal_add()")
        assertContains(script, "awk -v LEFT=")
        assertFalse(script.contains("8000000000000"))
        assertFalse(script.contains("REQUIRED_KB\" -le"))
    }

    @Test
    fun storagePreflightAcceptsReportedMultiGigabyteEstimate() {
        val directory = Files.createTempDirectory("uclone-space-test")
        directory.resolve("df").toFile().apply {
            writeText(
                """
                    #!/bin/sh
                    printf '%s\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on' 'uclone 999999999 1 900000000 1% /'
                """.trimIndent() + "\n",
            )
            check(setExecutable(true))
        }
        val script = """
            ROOT=/
            ${ShellScripts.storagePreflightScript()}
            uclone_require_space 3696214 push_source_and_clone_rollback
        """.trimIndent()
        val process = ProcessBuilder("/bin/sh", "-c", script)
            .redirectErrorStream(true)
            .apply {
                environment()["PATH"] = "${directory.toAbsolutePath()}:${System.getenv("PATH")}"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()

        assertEquals(0, process.waitFor(), output)
        assertContains(output, "SPACE_PREFLIGHT:push_source_and_clone_rollback requiredKb=3696214")
    }

    @Test
    fun permissionRestoreReportsAppOpsResetAndPersistenceFailures() {
        val rule = AppRule(packageName = "com.example.app")
        val scripts = listOf(
            ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage),
            ShellScripts.restore("com.example.app", settings, appPackage),
        )

        scripts.forEach { script ->
            assertContains(script, "WARN_APPOPS_RESET_FAILED")
            assertContains(script, "WARN_APPOPS_WRITE_SETTINGS_FAILED")
        }
    }

    @Test
    fun mergePermissionCaptureFailureSkipsPermissionsWithoutAbortingDataCopy() {
        val rule = AppRule(packageName = "com.example.app")
        val mergeSettings = settings.copy(permissionRestoreMode = PermissionRestoreMode.MERGE)
        val exactSettings = settings.copy(permissionRestoreMode = PermissionRestoreMode.EXACT)

        val mergeScripts = listOf(
            ShellScripts.capture("com.example.app", rule, mergeSettings, appPackage),
            ShellScripts.pushMainToClone("com.example.app", rule, mergeSettings, appPackage),
            ShellScripts.switchFromCloneLatest("com.example.app", rule, mergeSettings, appPackage),
        )
        mergeScripts.forEach { script ->
            assertContains(script, "WARN_SOURCE_PERMISSION_CAPTURE_SKIPPED:")
            assertFalse(script.contains("ERR_SOURCE_PERMISSION_CAPTURE:"))
            assertContains(script, "PERMISSION_CAPTURE_STATE=skipped")
        }
        assertContains(
            mergeScripts.first(),
            "\\\"permissionCaptureState\\\":\\\"${'$'}PERMISSION_CAPTURE_STATE\\\"",
        )
        assertContains(mergeScripts[1], "\\\"permissionCaptureState\\\":\\\"valid\\\"")

        val exactPush = ShellScripts.pushMainToClone("com.example.app", rule, exactSettings, appPackage)
        assertContains(exactPush, "ERR_SOURCE_PERMISSION_CAPTURE:")
    }

    @Test
    fun mutationScriptsDoNotSilentlyIgnoreForceStopFailures() {
        val rule = AppRule(packageName = "com.example.app")
        val scripts = listOf(
            ShellScripts.capture("com.example.app", rule, settings, appPackage),
            ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage),
            ShellScripts.switchFromCloneLatest("com.example.app", rule, settings, appPackage),
            ShellScripts.restore("com.example.app", settings, appPackage),
        )

        scripts.forEach { script ->
            assertContains(script, "uclone_gate_acquire")
            assertTrue(
                "ERR_SOURCE_GATE_ACQUIRE:" in script || "ERR_TARGET_GATE_ACQUIRE:" in script,
                "Gate acquisition failures must remain observable",
            )
        }
    }

    @Test
    fun transactionJournalPrecedesTargetDirectoryCreation() {
        val rule = AppRule(packageName = "com.example.app")
        val scripts = listOf(
            ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage),
            ShellScripts.restore("com.example.app", settings, appPackage),
        )

        scripts.forEach { script ->
            val functionStart = requireNotNull(
                Regex("(?m)^\\s*restore_part\\(\\) \\{\\s*$").find(script),
            ).range.last + 1
            val targetModeIndex = script.indexOf("TARGET_MODE=", functionStart)
            val journalIndex = script.indexOf(
                "uclone_transaction_target_mutating \"${'$'}PART_NAME\" || exit 77",
                targetModeIndex,
            )
            val mutationFlagIndex = script.indexOf("TARGET_MUTATED=1", journalIndex)
            val mkdirIndex = script.indexOf("mkdir -p \"${'$'}TARGET\" || exit 56", targetModeIndex)

            assertTrue(
                targetModeIndex in functionStart until journalIndex &&
                    journalIndex < mutationFlagIndex &&
                    mutationFlagIndex < mkdirIndex,
                "The durable TARGET_MUTATING journal must precede target directory creation",
            )
        }
    }

    @Test
    fun mutationScriptsEmitStructuredPerformanceMetrics() {
        val rule = AppRule(packageName = "com.example.app")
        val capture = ShellScripts.capture("com.example.app", rule, settings, appPackage)
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage)

        listOf(capture, restore, push).forEach { script ->
            assertContains(script, "UCLONE_METRIC:stage=")
            assertContains(script, "UCLONE_METRIC:scanned_files=")
            assertContains(script, "copied_bytes=")
            assertFalse(script.contains("UCLONE_COPIED_BYTES=${'$'}(("))
            assertFalse(script.contains("UCLONE_TARGET_DOWNTIME_MS=${'$'}(("))
        }
        assertContains(restore, "target_downtime_ms=")
        assertContains(push, "target_downtime_ms=")
    }

    @Test
    fun writableWorkspaceIdsIncludeRequestSuffixToAvoidSameSecondCollisions() {
        val rule = AppRule(packageName = "com.example.app")
        val scripts = listOf(
            ShellScripts.capture("com.example.app", rule, settings, appPackage),
            ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage),
            ShellScripts.restore("com.example.app", settings, appPackage),
            ShellScripts.auditRestoreConsistency("com.example.app", settings, appPackage),
        )

        scripts.forEach { script ->
            assertContains(script, "uclone_unique_stamp()")
            assertContains(script, "TS=${'$'}(uclone_unique_stamp)")
            assertContains(script, "UCLONE_REQUEST_ID")
            assertTrue(
                script.indexOf("uclone_unique_stamp()") < script.indexOf("TS=${'$'}(uclone_unique_stamp)"),
                "unique stamp function must be defined before it is called",
            )
            assertContains(script, "ERR_UNIQUE_STAMP")
        }
    }

    @Test
    fun transactionJournalReceivesExactSelectedParts() {
        val rule = AppRule(
            packageName = "com.example.app",
            includeCe = true,
            includeDe = false,
            includeExternal = true,
            includeMedia = true,
            includeObb = false,
            includePermissions = false,
        )
        val capture = ShellScripts.capture("com.example.app", rule, settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage)
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val rollback = ShellScripts.rollback("com.example.app", "main-return-point", settings, appPackage)
        val cloneRollback = ShellScripts.restoreCloneRollback("com.example.app", settings, appPackage)

        assertContains(capture, "uclone_transaction_init CAPTURE_SNAPSHOT \"${'$'}CONFIG_SRC_USER\" 0 'ce,external,media'")
        assertContains(push, "uclone_transaction_init PUSH_MAIN_TO_CLONE \"${'$'}SRC_USER\" \"${'$'}DST_USER\" 'ce,external,media'")
        assertContains(restore, "uclone_transaction_init ACTIVE 10 \"${'$'}DST_USER\" 'ce,de,external,permissions'")
        assertContains(rollback, "uclone_transaction_init ROLLBACK 0 \"${'$'}DST_USER\" 'ce,de,external,permissions'")
        assertContains(cloneRollback, "uclone_transaction_init CLONE_ROLLBACK 10 \"${'$'}DST_USER\" 'ce,de,external,permissions'")
    }

    @Test
    fun mutationJournalPersistsEveryCriticalStageInOrder() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)
        val stages = listOf(
            "uclone_transaction_stage TARGET_GATED || exit 77",
            "uclone_transaction_rollback_ready \"${'$'}ROLLBACK\" || exit 77",
            "uclone_transaction_stage TARGET_WRITTEN || exit 77",
            "uclone_transaction_stage METADATA_RESTORED || exit 77",
            "uclone_transaction_stage VERIFIED || exit 77",
            "uclone_transaction_commit_data || exit 77",
            "uclone_transaction_complete || exit 77",
            "uclone_transaction_cleanup_complete || exit 77",
        )

        stages.forEach { assertContains(script, it) }
        stages.zipWithNext().forEach { (before, after) ->
            assertTrue(script.indexOf(before) < script.indexOf(after), "$before must precede $after")
        }
        assertContains(script, "uclone_transaction_target_mutating permissions")
        assertContains(script, "PERMISSION_RESTORE_APPLIED=1")
    }

    @Test
    fun permissionOnlyCaptureAndRestoreRemainActionable() {
        val permissionOnlyRule = AppRule(
            packageName = "com.example.app",
            includeCe = false,
            includeDe = false,
            includeExternal = false,
            includeMedia = false,
            includeObb = false,
            includePermissions = true,
        )
        val permissionOnlySettings = settings.copy(
            includeCe = false,
            includeDe = false,
            includeExternal = false,
            includeMedia = false,
            includeObb = false,
            includePermissions = true,
        )
        val capture = ShellScripts.capture("com.example.app", permissionOnlyRule, settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", permissionOnlyRule, settings, appPackage)
        val restore = ShellScripts.restore("com.example.app", permissionOnlySettings, appPackage)

        listOf(capture, push).forEach { script ->
            assertContains(script, "CAPTURED_PERMISSIONS=1")
            assertContains(script, "CAPTURED_PARTS")
            assertContains(script, "CAPTURED_PERMISSIONS")
        }
        assertContains(restore, "PREPARED_PERMISSIONS=")
        assertContains(restore, "PERMISSION_RESTORE_APPLIED=1")
        assertContains(restore, "uclone_transaction_target_mutating permissions")
    }

    @Test
    fun generatedMutationScriptsAreValidPosixShell() {
        val rule = AppRule(packageName = "com.example.app")
        val permissionOnlyRule = rule.copy(
            includeCe = false,
            includeDe = false,
            includeExternal = false,
            includeMedia = false,
            includeObb = false,
            includePermissions = true,
        )
        val permissionOnlySettings = settings.copy(
            includeCe = false,
            includeDe = false,
            includeExternal = false,
            includeMedia = false,
            includeObb = false,
            includePermissions = true,
        )
        val scripts = listOf(
            ShellScripts.capture("com.example.app", rule, settings, appPackage),
            ShellScripts.restore("com.example.app", settings, appPackage),
            ShellScripts.switchFromCloneLatest("com.example.app", rule, settings, appPackage),
            ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage),
            ShellScripts.rollback("com.example.app", "20260710-010203", settings, appPackage),
            ShellScripts.restoreCloneRollback("com.example.app", settings, appPackage),
            ShellScripts.capture("com.example.app", permissionOnlyRule, settings, appPackage),
            ShellScripts.pushMainToClone("com.example.app", permissionOnlyRule, settings, appPackage),
            ShellScripts.switchFromCloneLatest("com.example.app", permissionOnlyRule, settings, appPackage),
            ShellScripts.restore("com.example.app", permissionOnlySettings, appPackage),
        )

        scripts.forEach { script ->
            val process = ProcessBuilder("/bin/sh", "-n").start()
            process.outputStream.bufferedWriter().use { it.write(script) }
            val stderr = process.errorStream.bufferedReader().readText()

            assertEquals(0, process.waitFor(), stderr)
        }
    }

    @Test
    fun restoreAndPushInstallRollbackGuardBeforeTargetMutation() {
        val rule = AppRule(packageName = "com.example.app")
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage)

        listOf(restore, push).forEach { script ->
            assertContains(script, "ROLLBACK_READY=1")
            assertContains(script, "AUTO_ROLLBACK_SUCCESS")
            assertContains(script, "AUTO_ROLLBACK_FAILED")
            assertContains(script, "TRANSACTION_COMMITTED=1")
            assertTrue(script.indexOf("ROLLBACK_READY=1") < script.lastIndexOf("uclone_stage_begin RESTORE_DATA"))
            assertTrue(script.lastIndexOf("uclone_stage_begin RESTORE_DATA") < script.lastIndexOf("TRANSACTION_COMMITTED=1"))
        }
    }

    @Test
    fun newSnapshotsAndTransactionUndoCarryFastIntegrityMetadata() {
        val rule = AppRule(packageName = "com.example.app")
        val capture = ShellScripts.capture("com.example.app", rule, settings, appPackage)
        val restore = ShellScripts.restore(
            "com.example.app",
            settings.copy(reuseExistingPassiveBackups = true),
            appPackage,
        )
        val push = ShellScripts.pushMainToClone(
            "com.example.app",
            rule,
            settings.copy(reuseExistingPassiveBackups = true),
            appPackage,
        )

        listOf(capture, restore, push).forEach { script ->
            assertContains(script, "uclone_write_part_metadata")
            assertContains(script, "uclone_verify_part_metadata")
            assertContains(script, "echo \"schema=2\"")
            assertContains(script, "\\\"integrityMode\\\":\\\"FAST_METADATA\\\"")
            assertContains(script, "\\\"schemaVersion\\\":5")
            assertContains(script, "sourceSigningCertificateSha256")
            assertContains(script, "sourceVersionName")
            assertContains(script, "sourceUid")
            assertContains(script, "sourceAppId")
        }
        assertContains(restore, "ERR_SNAPSHOT_INTEGRITY:")
        assertContains(restore, "ERR_SNAPSHOT_SIGNATURE_MISMATCH:")
        assertContains(restore, "ERR_PREPARED_INTEGRITY:")
        assertContains(push, "ERR_PUSH_INTEGRITY:")
        assertContains(restore, "uclone_require_canonical_backup_file \"${'$'}ACTIVE/manifest.json\"")
    }

    @Test
    fun historicalRestoreWithoutManifestRequiresExplicitLegacyConfirmation() {
        val strict = ShellScripts.restore("com.example.app", settings, appPackage)
        val allowed = ShellScripts.restore(
            "com.example.app",
            settings,
            appPackage,
            RestoreCompatibilityOptions(allowLegacyIdentity = true),
        )

        assertContains(strict, "if [ \"${'$'}SOURCE_KIND\" = \"switch_temp\" ]; then")
        assertContains(strict, "elif [ ! -f \"${'$'}ACTIVE/manifest.json\" ]; then")
        assertContains(strict, "ERR_LEGACY_PACKAGE_IDENTITY_CONFIRMATION_REQUIRED:${'$'}ACTIVE")
        assertContains(strict, "UCLONE_ALLOW_LEGACY_IDENTITY=0")
        assertContains(allowed, "UCLONE_ALLOW_LEGACY_IDENTITY=1")
    }

    @Test
    fun schema4RestoreRejectsWrongSourceOrTargetUserBeforeTargetGate() {
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val sourceCheck = restore.indexOf("ERR_SNAPSHOT_SOURCE_USER_MISMATCH:")
        val targetCheck = restore.indexOf("ERR_SNAPSHOT_TARGET_USER_MISMATCH:")
        val targetGate = restore.indexOf("uclone_gate_acquire target")

        assertContains(restore, "ACTIVE_SOURCE_USER=${'$'}(sed -n")
        assertContains(restore, "ACTIVE_TARGET_USER=${'$'}(sed -n")
        assertContains(restore, "[ \"${'$'}ACTIVE_SOURCE_USER\" = \"10\" ]")
        assertContains(restore, "[ \"${'$'}ACTIVE_TARGET_USER\" = \"${'$'}DST_USER\" ]")
        assertTrue(sourceCheck in 0 until targetGate)
        assertTrue(targetCheck in 0 until targetGate)
    }

    @Test
    fun restoreStateInferenceRequiresTheCurrentSigningCertificate() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)

        assertContains(script, "CURRENT_MARKER_SIGNING_CERTIFICATE=")
        assertContains(
            script,
            "[ \"${'$'}CURRENT_MARKER_SIGNING_CERTIFICATE\" = \"${'$'}UCLONE_SIGNING_CERTIFICATE_SHA256\" ]",
        )
        assertContains(script, "uclone_require_canonical_backup_file \"${'$'}CURRENT_MARKER_MANIFEST\"")
    }

    @Test
    fun restoreUsesUserScopedCacheGidAndDistinctObbGroup() {
        val rule = AppRule(packageName = "com.example.app", includeObb = true)
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage)

        listOf(restore, push).forEach { script ->
            assertContains(script, "OWNER_USER_ID=${'$'}((OWNER_UID / 100000))")
            assertContains(script, "CACHE_GID=${'$'}((OWNER_USER_ID * 100000 + 20000 + APP_ID - 10000))")
            assertContains(script, "case \"${'$'}SEC_KIND:${'$'}OWNER_UID\" in")
            assertContains(script, "media:*|obb:*) ;;")
            assertContains(script, "obb) echo \"${'$'}")
            assertContains(script, ":1079")
            assertContains(script, "TARGET_MODE")
            assertContains(script, "chmod \"${'$'}SEC_MODE\" \"${'$'}SEC_TARGET\"")
        }
    }

    @Test
    fun rollbackRecordsEveryOriginalPartStateBeforeTransactionGuard() {
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val guard = restore.indexOf("ROLLBACK_READY=1")

        listOf("ce", "de", "external", "media", "obb").forEach { part ->
            val backupCall = restore.indexOf("\"${'$'}ROLLBACK/$part\" \"$part\"")
            assertTrue(backupCall in 0 until guard, part)
        }
        assertContains(restore, "${'$'}ROLLBACK/.state/${'$'}PART_NAME")
        assertTrue(restore.indexOf("ROLLBACK_FINALIZED=1") < guard)
        assertContains(restore, "ROLLBACK_SAFE_PREFIX=")
    }

    @Test
    fun restorePreparesAndValidatesSourceBeforeStoppingTarget() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)

        val preflight = script.indexOf("uclone_require_space \"${'$'}RESTORE_REQUIRED_KB\"")
        val prepared = script.indexOf("PREPARED_PARTS=${'$'}((PREPARED_PARTS + 1))")
        val targetStop = script.indexOf("uclone_stage_begin TARGET_STOP")
        val rollback = script.indexOf("uclone_stage_begin ROLLBACK_BACKUP")

        assertTrue(preflight in 0 until targetStop)
        assertTrue(prepared in 0 until targetStop)
        assertTrue(targetStop < rollback)
        assertContains(script, "PREPARED=\"${'$'}PREPARED_ROOT/${'$'}PART_NAME\"")
        assertFalse(script.contains("ROOT/tmp/restore_${'$'}{PKG}_"))
    }

    @Test
    fun pushPreparesSourceBeforeStoppingCloneTargetAndAvoidsSecondExtraction() {
        val script = ShellScripts.pushMainToClone(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true),
            appPackage,
        )

        val sourcePrepare = script.indexOf("uclone_stage_begin SOURCE_PREPARE")
        val sourceCopied = script.indexOf("uclone_record_temp_path \"${'$'}PUSH_TEMP\"")
        val targetStop = script.indexOf("uclone_stage_begin TARGET_STOP")

        assertTrue(sourcePrepare in 0 until targetStop)
        assertTrue(sourceCopied in 0 until targetStop)
        assertContains(script, "force_stop_target_package")
        assertFalse(script.contains("push_restore_${'$'}{PKG}_"))
        assertContains(script, "uclone_extract_target_tree \"${'$'}SNAP\" \"${'$'}TARGET\"")
    }

    @Test
    fun captureRestoreAndPushRejectInsufficientSpaceBeforeMutation() {
        val rule = AppRule(packageName = "com.example.app")
        val capture = ShellScripts.capture("com.example.app", rule, settings, appPackage)
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage)

        listOf(capture, restore, push).forEach { script ->
            assertContains(script, "ERR_INSUFFICIENT_SPACE:")
        }
        assertTrue(restore.indexOf("ERR_INSUFFICIENT_SPACE:") < restore.indexOf("uclone_stage_begin TARGET_STOP"))
        assertTrue(push.indexOf("ERR_INSUFFICIENT_SPACE:") < push.indexOf("uclone_stage_begin TARGET_STOP"))
    }

    @Test
    fun switchMarkerUsesAtomicReplaceAndCanBeRestoredAfterFailure() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings,
            appPackage,
        )

        assertContains(script, "MARKER_TMP=\"${'$'}MARKER_PATH.tmp_${'$'}TS\"")
        assertContains(script, "chmod 600 \"${'$'}MARKER_TMP\"")
        assertContains(script, "mv -f \"${'$'}MARKER_TMP\" \"${'$'}MARKER_PATH\"")
        assertContains(script, "restore_previous_switch_marker")
    }

    @Test
    fun capturePreservesPayloadModes() {
        val script = ShellScripts.capture(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings,
            appPackage,
        )

        assertFalse(script.contains("chmod -R 700"))
        assertContains(script, "chmod 700 \"${'$'}BASE\" \"${'$'}BASE/active\" \"${'$'}BASE/history\"")
        assertContains(script, "ERR_SNAPSHOT_WORKSPACE_MODE")
    }

    @Test
    fun workspaceCopiesIgnoreArchivedApplicationOwner() {
        val rule = AppRule(packageName = "com.example.app")
        val capture = ShellScripts.capture("com.example.app", rule, settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage)
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val switch = ShellScripts.switchFromCloneLatest("com.example.app", rule, settings, appPackage)

        listOf(capture, push, restore, switch).forEach { script ->
            assertContains(script, "uclone_extract_workspace_tree")
            assertContains(script, "tar -xopf -")
            assertContains(script, "tar -cpf - .")
            assertFalse(script.contains("--null"))
        }
    }

    @Test
    fun sourceCaptureStateChecksUseTheDirectCloneStateClient() {
        val rule = AppRule(packageName = "com.example.app")
        val scripts = listOf(
            ShellScripts.capture("com.example.app", rule, settings, appPackage),
            ShellScripts.switchFromCloneLatest("com.example.app", rule, settings, appPackage),
        )

        scripts.forEach { script ->
            assertEquals(1, Regex("get-started-user-state").findAll(script).count())
            assertContains(script, "STATE=${'$'}(clone_state)")
            assertContains(script, "ERR_CLONE_STATE_QUERY:${'$'}STATE")
        }
    }

    private val settings = UCloneSettings(rootDir = "/data/adb/uclone")
    private val appPackage = "com.uclone.restore"

    @Test
    fun rollback_rejectsUnsafeRollbackId() {
        listOf("", ".", "..", "../escape", "bad/id", "bad id").forEach { rollbackId ->
            assertFailsWith<IllegalArgumentException> {
                ShellScripts.rollback("com.example.app", rollbackId, settings, appPackage)
            }
        }
    }

    @Test
    fun rollback_validatesRestoreSourceInsideRollbackDirectory() {
        val script = ShellScripts.rollback("com.example.app", "20260709-010203", settings, appPackage)

        assertContains(script, "SOURCE_ROLLBACK_ID='20260709-010203'")
        assertContains(script, "EXPECTED_ACTIVE=\"${'$'}ROOT/rollback/${'$'}PKG/${'$'}SOURCE_ROLLBACK_ID\"")
        assertContains(script, "ERR_BAD_RESTORE_SOURCE")
    }

    @Test
    fun restoreSwitchMainState_keepsExistingRestoreFlowWhenCloneUpdateIsDisabled() {
        val script = ShellScripts.restoreSwitchMainState(
            packageName = "com.example.app",
            rollbackId = "20260709-010203",
            rule = AppRule(packageName = "com.example.app"),
            settings = settings,
            appPackage = appPackage,
        )
        val existingRestore = ShellScripts.rollback(
            packageName = "com.example.app",
            rollbackId = "20260709-010203",
            settings = settings,
            appPackage = appPackage,
            rollbackReason = "还原主系统态前生成",
        )

        assertEquals(existingRestore, script)
        assertContains(script, "SOURCE_ROLLBACK_ID='20260709-010203'")
        assertFalse(script.contains("COMPOSITE_STEP=FORCE_UPDATE_CLONE_DATA"))
        assertFalse(script.contains("FORCE_UPDATE_CLONE_DATA_AND_MAIN_RESTORE_DONE"))
    }

    @Test
    fun restoreSwitchMainState_updatesCloneBeforeRestoringMainWhenEnabled() {
        val script = ShellScripts.restoreSwitchMainState(
            packageName = "com.example.app",
            rollbackId = "20260709-010203",
            rule = AppRule(packageName = "com.example.app"),
            settings = settings.copy(forceUpdateCloneDataBeforeMainRestore = true),
            appPackage = appPackage,
        )

        val updateStep = script.indexOf("COMPOSITE_STEP=FORCE_UPDATE_CLONE_DATA")
        val updateDone = script.indexOf("FORCE_UPDATE_CLONE_DATA_DONE=1")
        val restoreStep = script.indexOf("COMPOSITE_STEP=RESTORE_SWITCH_MAIN_STATE")
        val allDone = script.indexOf("FORCE_UPDATE_CLONE_DATA_AND_MAIN_RESTORE_DONE=1")

        assertTrue(updateStep >= 0)
        assertTrue(updateStep < updateDone)
        assertTrue(updateDone < restoreStep)
        assertTrue(restoreStep < allDone)
        assertContains(script, "FORCE_UPDATE_EXIT=${'$'}?")
        assertContains(script, "ERR_FORCE_UPDATE_CLONE_DATA:exit=${'$'}FORCE_UPDATE_EXIT")
        assertContains(script, "exit \"${'$'}FORCE_UPDATE_EXIT\"")
        assertContains(script, "SOURCE_ROLLBACK_ID='20260709-010203'")
    }

    @Test
    fun rollback_endsSwitchSessionOnlyAfterRestoreVerification() {
        val script = ShellScripts.rollback("com.example.app", "20260709-010203", settings, appPackage)

        assertContains(script, "SWITCH_MARKER_BEFORE_EXISTS=0")
        assertContains(script, "restore_previous_switch_marker")
        assertContains(script, "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER")
        assertContains(script, "\\\"reason\\\":\\\"恢复主系统备份前生成\\\"")
        val verifyIndex = script.indexOf("[ \"${'$'}RESTORED_PARTS\" -gt 0 ]")
        val markerClearIndex = script.lastIndexOf("SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER")
        assertTrue(verifyIndex >= 0)
        assertTrue(markerClearIndex >= 0)
        assertTrue(
            verifyIndex < markerClearIndex,
        )
    }

    @Test
    fun restoreOwnershipDoesNotFollowSymlinks() {
        val script = ShellScripts.rollback("com.example.app", "20260709-010203", settings, appPackage)

        assertContains(script, "chown -hR")
        assertFalse(script.contains("chown -R"))
    }

    @Test
    fun restoreFailsWhenCacheOwnershipCannotBeRepaired() {
        val restore = ShellScripts.restore("com.example.app", settings, appPackage)
        val push = ShellScripts.pushMainToClone("com.example.app", AppRule(packageName = "com.example.app"), settings, appPackage)

        listOf(restore, push).forEach { script ->
            assertContains(script, "ERR_CACHE_GID_RESTORE:")
            assertFalse(script.contains("cache\" >/dev/null 2>&1 || true"))
            assertFalse(script.contains("code_cache\" >/dev/null 2>&1 || true"))
        }
    }

    @Test
    fun deleteRestoreBackup_rejectsActiveReturnPointBeforeDeletion() {
        val script = ShellScripts.deleteRestoreBackup("com.example.app", "20260709-010203", settings, appPackage)

        assertContains(script, "ROLLBACK_PARENT=\"${'$'}ROOT/rollback/${'$'}PKG\"")
        assertContains(script, "TARGET=\"${'$'}ROOT/rollback/${'$'}PKG/${'$'}ROLLBACK_ID\"")
        assertContains(script, "ERR_ACTIVE_RETURN_POINT_DELETE:")
        assertContains(script, "uclone_remove_tree \"${'$'}TARGET\"")
        assertContains(script, "DELETED_RESTORE_BACKUP=${'$'}TARGET")
        assertContains(script, "[ \"${'$'}MARKER_ROLLBACK_ID\" = \"${'$'}ROLLBACK_ID\" ]")
        assertTrue(script.indexOf("ERR_ACTIVE_RETURN_POINT_DELETE:") < script.indexOf("uclone_remove_tree \"${'$'}TARGET\""))
        assertFalse(script.contains("SWITCH_MARKER_CLEARED="))
        assertFalse(script.contains("find \"${'$'}ROLLBACK_PARENT\" -mindepth 1 -maxdepth 1"))
        assertFalse(script.contains("rm -rf \"${'$'}ROLLBACK_PARENT\""))
        assertFalse(script.contains("rm -rf \"${'$'}ROOT/snapshots"))
        assertFalse(script.contains("rm -rf \"${'$'}ROOT/logs"))
    }

    @Test
    fun resetSwitchState_persistsUnknownStateWithoutDeletingReturnPoint() {
        val script = ShellScripts.resetSwitchState("com.example.app", settings, appPackage)

        assertContains(script, "SWITCH_MARKER=\"${'$'}ROOT/switches/${'$'}PKG/active\"")
        assertContains(script, "UNKNOWN_SWITCH_MARKER='$UNKNOWN_SWITCH_MARKER'")
        assertContains(script, "MARKER_TMP=\"${'$'}SWITCH_MARKER.tmp_${'$'}${'$'}\"")
        assertContains(script, "mv -f \"${'$'}MARKER_TMP\" \"${'$'}SWITCH_MARKER\"")
        assertContains(script, "SWITCH_STATE_RESET=unknown")
        assertFalse(script.contains("rm -f \"${'$'}SWITCH_MARKER\""))
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("/data/user/"))
        assertFalse(script.contains("/data/user_de/"))
        assertFalse(script.contains("${'$'}ROOT/rollback"))
        assertFalse(script.contains("${'$'}ROOT/snapshots"))
    }

    @Test
    fun resetSwitchState_rejectsPackagePathTraversal() {
        assertFailsWith<IllegalArgumentException> {
            ShellScripts.resetSwitchState("../../data", settings, appPackage)
        }
    }

    @Test
    fun resetWorkspace_onlyDeletesKnownUCloneChildren() {
        val script = ShellScripts.resetWorkspace(settings)

        assertContains(script, "ERR_WORKSPACE_NOT_CANONICAL")
        assertContains(script, "ERR_UNTRUSTED_WORKSPACE_IDENTITY")
        assertContains(script, "RESET_TARGETS=\"snapshots rollback clone_rollback switches logs tmp audit transactions config/workspace_owner_root_v1 locks/orphaned\"")
        assertContains(script, "\"${'$'}ROOT\"/snapshots|\"${'$'}ROOT\"/rollback|\"${'$'}ROOT\"/clone_rollback")
        assertContains(script, "ERR_UNSAFE_RESET_TARGET")
        assertContains(script, "uclone_remove_file")
        assertContains(script, "uclone_remove_tree \"${'$'}TARGET\"")
        assertContains(script, "[ -e \"${'$'}TARGET\" ] || [ -L \"${'$'}TARGET\" ] || continue")
        assertContains(script, "ERR_WORKSPACE_IDENTITY_LOST")
        assertContains(script, "RESET_WORKSPACE_DONE")
        assertFalse(script.contains("rm -rf \"${'$'}ROOT\""))
        assertFalse(script.contains("\"${'$'}ROOT\"/config)"))
        assertFalse(script.contains("\"${'$'}ROOT\"/locks/active_task"))
        assertFalse(script.contains("/data/user/"))
        assertFalse(script.contains("/data/user_de/"))
        assertFalse(script.contains("/data/media/"))
    }

    @Test
    fun restorePruneNeverClearsUnknownOrBrokenStateIntoFalseMainState() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)

        assertContains(script, "SWITCH_ID_AFTER_PRUNE=")
        assertContains(script, "[ ! -d \"${'$'}ROLLBACK_PARENT/${'$'}SWITCH_ID_AFTER_PRUNE\" ]")
        assertContains(script, "KEPT_ROLLBACK_ACTIVE_RETURN_POINT=")
        assertContains(script, "KEPT_SWITCH_MARKER_UNKNOWN=")
        assertContains(script, "KEPT_SWITCH_MARKER_BROKEN=")
        assertFalse(script.contains("SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER_FOR_PRUNE"))
    }

    @Test
    fun restoreBacksUpExternalMediaAndObbBeforeRestoring() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)

        assertContains(script, "backup_dir \"/data/media/${'$'}DST_USER/Android/data/${'$'}PKG\" \"${'$'}ROLLBACK/external\"")
        assertContains(script, "backup_dir \"/data/media/${'$'}DST_USER/Android/media/${'$'}PKG\" \"${'$'}ROLLBACK/media\"")
        assertContains(script, "backup_dir \"/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG\" \"${'$'}ROLLBACK/obb\"")
        assertContains(script, "target_owner_for()")
        assertContains(script, "media) echo \"${'$'}UID_VALUE:1078\"")
        assertContains(script, "TARGET_OWNER=${'$'}(target_owner_for \"${'$'}TARGET\" \"${'$'}OWNER_UID\" \"${'$'}OWNER_KIND\")")
        assertContains(script, "restore_part \"${'$'}ACTIVE/external\" \"/data/media/${'$'}DST_USER/Android/data/${'$'}PKG\" \"\" \"media\" \"external\"")
        assertContains(script, "restore_part \"${'$'}ACTIVE/media\" \"/data/media/${'$'}DST_USER/Android/media/${'$'}PKG\" \"\" \"media\" \"media\"")
        assertContains(script, "restore_part \"${'$'}ACTIVE/obb\" \"/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG\" \"\" \"obb\" \"obb\"")
    }

    @Test
    fun switchFromCloneLatest_usesTemporaryCloneSourceWithoutActivatingSnapshot() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings,
            appPackage,
        )

        assertContains(script, "SOURCE_KIND='switch_temp'")
        assertContains(script, "ACTIVE=\"${'$'}ROOT/tmp/switch_${'$'}{PKG}_${'$'}TS\"")
        assertContains(script, "SWITCH_SOURCE_READY=${'$'}SWITCH_TEMP")
        assertContains(script, "SWITCH_MARKER=${'$'}SWITCH_DIR/active ROLLBACK_ID=${'$'}ROLLBACK_ID")
        assertContains(script, "SOURCE_STATE_KIND='clone'")
        assertContains(script, "\\\"stateKind\\\":\\\"${'$'}CURRENT_STATE_KIND\\\"")
        assertFalse(script.contains("SNAPSHOT_ACTIVE="))
        assertFalse(script.contains("mv \"${'$'}TMP\" \"${'$'}BASE/active\""))
    }

    @Test
    fun restoringBackupSynchronizesCurrentStateFromBackupManifest() {
        val script = ShellScripts.rollback(
            "com.example.app",
            "20260710-010203",
            settings,
            appPackage,
        )

        assertContains(script, "SOURCE_STATE_KIND=${'$'}(sed -n")
        assertContains(script, "RESTORE_SOURCE_STATE=${'$'}SOURCE_STATE_KIND CURRENT_STATE=${'$'}CURRENT_STATE_KIND")
        assertContains(script, "DATA_STATE_UPDATED=main")
        assertContains(script, "DATA_STATE_UPDATED=clone")
        assertContains(script, "MAIN_ROLLBACK_ID=\"${'$'}ROLLBACK_ID\"")
    }

    @Test
    fun restoreTreatsUnknownOrBrokenCurrentMarkerAsUnknownState() {
        val script = ShellScripts.rollback(
            "com.example.app",
            "20260710-010203",
            settings,
            appPackage,
        )

        assertContains(script, "UNKNOWN_SWITCH_MARKER='$UNKNOWN_SWITCH_MARKER'")
        assertContains(script, "CURRENT_STATE_KIND=\"unknown\"")
        assertContains(script, "CURRENT_MARKER_MANIFEST=\"${'$'}ROOT/rollback/${'$'}PKG/${'$'}CURRENT_MAIN_ROLLBACK_ID/manifest.json\"")
        assertContains(script, "uclone_require_canonical_backup_file \"${'$'}CURRENT_MARKER_MANIFEST\"")
        assertContains(script, "[ \"${'$'}CURRENT_MARKER_PACKAGE\" = \"${'$'}PKG\" ]")
        assertContains(script, "CURRENT_MARKER_SOURCE_USER=${'$'}(sed -n")
        assertContains(script, "CURRENT_MARKER_TARGET_USER=${'$'}(sed -n")
        assertContains(script, "[ \"${'$'}CURRENT_MARKER_SOURCE_USER\" = \"0\" ]")
        assertContains(script, "[ \"${'$'}CURRENT_MARKER_TARGET_USER\" = \"0\" ]")
        assertContains(script, "PASSIVE_BACKUP_STATE=${'$'}CURRENT_STATE_KIND")
    }

    @Test
    fun restoringUnknownBackupSucceedsAndPersistsUnknownMarker() {
        val script = ShellScripts.rollback(
            "com.example.app",
            "20260710-010203",
            settings,
            appPackage,
        )

        assertFalse(script.contains("ERR_BACKUP_STATE_UNKNOWN:"))
        assertContains(script, "write_switch_marker_atomic \"${'$'}SWITCH_MARKER\" \"${'$'}UNKNOWN_SWITCH_MARKER\"")
        assertContains(script, "WARN_DATA_STATE_REMAINS_UNKNOWN:")
    }

    @Test
    fun restoringCloneFromUnknownCurrentStateDoesNotFabricateCloneState() {
        val script = ShellScripts.rollback(
            "com.example.app",
            "20260710-010203",
            settings,
            appPackage,
        )

        assertContains(script, "if [ \"${'$'}CURRENT_STATE_KIND\" = \"unknown\" ]")
        assertContains(script, "write_switch_marker_atomic \"${'$'}SWITCH_MARKER\" \"${'$'}UNKNOWN_SWITCH_MARKER\"")
        assertFalse(script.contains("ERR_MAIN_STATE_BACKUP_MISSING"))
    }

    @Test
    fun passiveRollbackPruningKeepsBackupFromOtherDataState() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)

        assertContains(script, "OLD_STATE_KIND")
        assertContains(script, "KEPT_ROLLBACK_OTHER_STATE=")
        assertContains(script, "KEPT_ROLLBACK_LEGACY=")
        assertContains(script, "[ \"${'$'}SWITCH_ID_AFTER_PRUNE\" = \"${'$'}UNKNOWN_SWITCH_MARKER\" ]")
        assertContains(script, "KEPT_SWITCH_MARKER_UNKNOWN=")
    }

    @Test
    fun passiveBackupReuseRequiresMatchingStateAndCompleteStateFiles() {
        val reuseSettings = settings.copy(reuseExistingPassiveBackups = true)
        val restore = ShellScripts.restore("com.example.app", reuseSettings, appPackage)
        val push = ShellScripts.pushMainToClone(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            reuseSettings,
            appPackage,
        )

        assertContains(restore, "uclone_backup_matches_identity \"${'$'}REUSE_CANDIDATE\" \"${'$'}PKG\" \"${'$'}DST_USER\" \"${'$'}DST_USER\" \"${'$'}CURRENT_STATE_KIND\" rollback")
        assertContains(restore, "PASSIVE_BACKUP_REUSED=")
        assertContains(restore, "TRANSACTION_UNDO=\"${'$'}ROOT/tmp/undo_${'$'}{PKG}_${'$'}TS\"")
        assertContains(restore, "ROLLBACK=\"${'$'}TRANSACTION_UNDO\"")
        assertContains(restore, "TRANSACTION_UNDO_CREATED=")
        assertContains(restore, "\\\"transactionRequestId\\\":\\\"${'$'}UCLONE_REQUEST_ID\\\"")
        assertContains(push, "uclone_backup_matches_identity \"${'$'}ROLLBACK_LATEST\" \"${'$'}PKG\" \"${'$'}DST_USER\" \"${'$'}DST_USER\" clone clone_rollback")
        assertContains(push, "CLONE_ROLLBACK_REUSED=")
        assertContains(push, "TRANSACTION_UNDO=\"${'$'}ROOT/tmp/undo_clone_${'$'}{PKG}_${'$'}TS\"")
        assertContains(push, "ROLLBACK=\"${'$'}TRANSACTION_UNDO\"")
        assertContains(push, "TRANSACTION_UNDO_CREATED=")
        assertContains(push, "\\\"transactionRequestId\\\":\\\"${'$'}UCLONE_REQUEST_ID\\\"")
        val relocatedRollback = push.indexOf("uclone_transaction_rollback_relocated \"${'$'}ROLLBACK_LATEST\"")
        val persistentRollbackAssignment = push.indexOf("ROLLBACK=\"${'$'}ROLLBACK_LATEST\"")
        assertTrue(relocatedRollback >= 0)
        assertTrue(persistentRollbackAssignment in 0 until relocatedRollback)
    }

    @Test
    fun backupReuseValidatorRejectsMissingPayloadAndAcceptsCompleteBackup() {
        val root = Files.createTempDirectory("uclone-reuse-validator").toFile()
        val backup = root.resolve("backup").apply { mkdirs() }
        backup.resolve("manifest.json").writeText("{\"schemaVersion\":2,\"stateKind\":\"main\"}\n")
        val state = backup.resolve(".state").apply { mkdirs() }
        val metadata = backup.resolve(".meta").apply { mkdirs() }
        listOf("ce", "de", "external", "media", "obb").forEach { state.resolve(it).writeText("absent\n") }
        listOf("ce", "de", "external", "media", "obb").forEach { metadata.resolve(it).writeText("present\n") }
        state.resolve("ce").writeText("data\n")

        fun validate(): Int = ProcessBuilder(
            "/bin/sh",
            "-c",
            "uclone_verify_part_metadata() { [ -f \"${'$'}1/.meta/${'$'}2\" ]; }\n${ShellScripts.backupReuseValidationScript()}\nuclone_backup_is_valid ${shellQuote(backup.absolutePath)}",
        ).start().waitFor()

        assertEquals(1, validate())
        backup.resolve("ce").apply { mkdirs(); resolve("token.db").writeText("data") }
        assertEquals(0, validate())
        backup.resolve("manifest.json").writeText("{\"schemaVersion\":4,\"stateKind\":\"main\",\"permissionCaptureState\":\"excluded\"}\n")
        assertEquals(0, validate())
        backup.resolve("manifest.json").writeText("{\"schemaVersion\":5,\"stateKind\":\"main\",\"permissionCaptureState\":\"excluded\"}\n")
        assertEquals(0, validate())
        root.deleteRecursively()
    }

    @Test
    fun backupReuseValidatorRequiresPermissionCaptureWhenManifestMarksItValid() {
        val root = Files.createTempDirectory("uclone-reuse-permission-validator").toFile()
        val backup = root.resolve("backup").apply { mkdirs() }
        val state = backup.resolve(".state").apply { mkdirs() }
        val metadata = backup.resolve(".meta").apply { mkdirs() }
        listOf("ce", "de", "external", "media", "obb").forEach { part ->
            state.resolve(part).writeText("absent\n")
            metadata.resolve(part).writeText("present\n")
        }
        backup.resolve("manifest.json").writeText("{\"schemaVersion\":5,\"permissionCaptureState\":\"valid\"}\n")

        fun validate(): Int = ProcessBuilder(
            "/bin/sh",
            "-c",
            "uclone_verify_part_metadata() { [ -f \"${'$'}1/.meta/${'$'}2\" ]; }\n" +
                "uclone_permission_capture_valid() { [ -d \"${'$'}1\" ] && [ -f \"${'$'}1/capture.state\" ] && [ -f \"${'$'}1/runtime_grants.txt\" ] && [ -f \"${'$'}1/appops.txt\" ]; }\n" +
                ShellScripts.backupReuseValidationScript() +
                "\nuclone_backup_is_valid ${shellQuote(backup.absolutePath)}",
        ).start().waitFor()

        assertEquals(1, validate())
        backup.resolve("permissions").apply {
            mkdirs()
            resolve("capture.state").writeText("schema=1\n")
            resolve("runtime_grants.txt").writeText("")
            resolve("appops.txt").writeText("")
        }
        assertEquals(0, validate())
        root.deleteRecursively()
    }

    @Test
    fun persistentBackupReuseRejectsWrongUserIdentity() {
        val root = Files.createTempDirectory("uclone-reuse-identity").toFile()
        val backup = root.resolve("backup").apply { mkdirs() }
        val state = backup.resolve(".state").apply { mkdirs() }
        val metadata = backup.resolve(".meta").apply { mkdirs() }
        listOf("ce", "de", "external", "media", "obb").forEach { part ->
            state.resolve(part).writeText("absent\n")
            metadata.resolve(part).writeText("present\n")
        }
        backup.resolve("manifest.json").writeText(
            "{\"schemaVersion\":5,\"packageName\":\"com.example.app\",\"stateKind\":\"main\",\"sourceUser\":\"0\",\"targetUser\":\"0\",\"backupKind\":\"rollback\",\"sourceSigningCertificateSha256\":\"${"a".repeat(64)}\",\"permissionCaptureState\":\"excluded\"}\n",
        )

        fun validatesFor(sourceUser: Int, targetUser: Int): Int = ProcessBuilder(
            "/bin/sh",
            "-c",
            "UCLONE_SIGNING_CERTIFICATE_SHA256=${"a".repeat(64)}\n" +
                "uclone_verify_part_metadata() { [ -f \"${'$'}1/.meta/${'$'}2\" ]; }\n" +
                ShellScripts.backupReuseValidationScript() +
                "\nuclone_backup_matches_identity ${shellQuote(backup.absolutePath)} com.example.app $sourceUser $targetUser main rollback",
        ).start().waitFor()

        assertEquals(0, validatesFor(sourceUser = 0, targetUser = 0))
        assertEquals(1, validatesFor(sourceUser = 10, targetUser = 10))
        root.deleteRecursively()
    }

    @Test
    fun cloneRollbackDeletionUsesCloneRollbackPathOnly() {
        val script = ShellScripts.deleteCloneRollback("com.example.app", "latest", settings, appPackage)

        assertContains(script, "TARGET=\"${'$'}ROOT/clone_rollback/${'$'}PKG/${'$'}ROLLBACK_ID\"")
        assertContains(script, "DELETED_CLONE_ROLLBACK=")
        assertFalse(script.contains("${'$'}ROOT/rollback/${'$'}PKG"))
    }

    @Test
    fun switchFromCloneLatest_doesNotUseHereDocuments() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings,
            appPackage,
        )

        assertFalse(script.contains("<<EOF"))
    }

    @Test
    fun pushMainToClone_usesSeparateLatestCloneRollbackAndDoesNotSetSwitchMarker() {
        val script = ShellScripts.pushMainToClone(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true),
            appPackage,
        )

        assertContains(script, "SRC_USER=0")
        assertContains(script, "DST_USER=10")
        assertContains(script, "ROLLBACK_PARENT=\"${'$'}ROOT/clone_rollback/${'$'}PKG\"")
        assertContains(script, "ROLLBACK_LATEST=\"${'$'}ROLLBACK_PARENT/latest\"")
        assertContains(script, "ROLLBACK_PREVIOUS=\"${'$'}ROLLBACK_PARENT/latest.previous\"")
        assertContains(script, "ROLLBACK=\"${'$'}ROLLBACK_TMP\"")
        assertContains(script, "\\\"backupKind\\\":\\\"clone_rollback\\\"")
        assertContains(script, "\\\"retention\\\":\\\"latest_only\\\"")
        assertContains(script, "\\\"stateKind\\\":\\\"clone\\\"")
        assertContains(script, "capture_part \"${'$'}PUSH_TEMP\" ce \"${'$'}PUSH_TEMP/ce\" \"/data/user/${'$'}SRC_USER/${'$'}PKG\"")
        assertContains(script, "restore_part \"${'$'}PUSH_TEMP/ce\" \"/data/user/${'$'}DST_USER/${'$'}PKG\" \"app\"")
        assertContains(script, "PUSH_MAIN_TO_CLONE_DONE")
        assertContains(script, "CLONE_ROLLBACK_PREPARED=${'$'}ROLLBACK")
        assertContains(script, "mv \"${'$'}ROLLBACK_LATEST\" \"${'$'}ROLLBACK_PREVIOUS\"")
        assertContains(script, "mv \"${'$'}ROLLBACK_TMP\" \"${'$'}ROLLBACK_LATEST\"")
        assertTrue(script.indexOf("CLONE_ROLLBACK_PREPARED=") < script.indexOf("uclone_stage_begin RESTORE_DATA"))
        assertTrue(script.indexOf("uclone_stage_begin RESTORE_DATA") < script.lastIndexOf("mv \"${'$'}ROLLBACK_TMP\" \"${'$'}ROLLBACK_LATEST\""))
        assertContains(script, "TRANSACTION_UNDO=\"${'$'}ROOT/tmp/undo_clone_${'$'}{PKG}_${'$'}TS\"")
        assertFalse(script.contains("SWITCH_MARKER="))
        assertFalse(script.contains("\"${'$'}ROOT/rollback/${'$'}PKG\""))
    }

    @Test
    fun sourcePartCaptureFailuresAreNeverIgnored() {
        val scripts = listOf(
            ShellScripts.capture(
                "com.example.app",
                AppRule(packageName = "com.example.app"),
                settings,
                appPackage,
            ),
            ShellScripts.pushMainToClone(
                "com.example.app",
                AppRule(packageName = "com.example.app"),
                settings,
                appPackage,
            ),
            ShellScripts.switchFromCloneLatest(
                "com.example.app",
                AppRule(packageName = "com.example.app"),
                settings,
                appPackage,
            ),
        )

        scripts.forEach { script ->
            val selectedCalls = script.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("capture_part ") }
                .toList()
            assertEquals(5, selectedCalls.size)
            selectedCalls.forEach { call -> assertTrue(call.endsWith("|| exit 17"), call) }
            assertContains(script, "ERR_PART_METADATA_WRITE:${'$'}STATE_NAME")
        }
    }

    @Test
    fun cloneRollbackMetadataFailureIdentifiesThePart() {
        val scripts = listOf(
            ShellScripts.pushMainToClone(
                "com.example.app",
                AppRule(packageName = "com.example.app"),
                settings,
                appPackage,
            ),
            ShellScripts.restore("com.example.app", settings, appPackage),
        )

        scripts.forEach { script ->
            assertContains(script, "ERR_ROLLBACK_PART_METADATA:${'$'}PART_NAME")
            assertContains(script, "ERR_ROLLBACK_SOURCE_SCAN:${'$'}PART_NAME")
            assertContains(script, "ERR_ROLLBACK_COPY_SCAN:${'$'}PART_NAME")
        }
    }

    @Test
    fun pushMainToCloneUsesMergePermissionRestoreByDefault() {
        val script = ShellScripts.pushMainToClone(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true),
            appPackage,
        )

        assertContains(script, "uclone_restore_permission_state \"${'$'}1\" \"${'$'}DST_USER\" \"MERGE\"")
        assertContains(script, "if [ \"${'$'}RESTORE_MODE\" = \"EXACT\" ]; then")
        assertContains(script, "WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE:")
        assertContains(script, "WARN_SOURCE_PERMISSION_CAPTURE_SKIPPED:${'$'}SRC_USER")
        assertContains(script, "ERR_TRANSACTION_PERMISSION_CAPTURE:${'$'}DST_USER")
        assertTrue(script.indexOf("if [ \"${'$'}RESTORE_MODE\" = \"EXACT\" ]; then") < script.indexOf("cmd package revoke --user"))
    }

    @Test
    fun exactPermissionRestoreMustBeExplicitlyConfigured() {
        val script = ShellScripts.pushMainToClone(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(
                cloneUnlockCredential = "123456",
                autoUnlockClone = true,
                permissionRestoreMode = PermissionRestoreMode.EXACT,
            ),
            appPackage,
        )

        assertContains(script, "uclone_restore_permission_state \"${'$'}1\" \"${'$'}DST_USER\" \"EXACT\"")
        assertContains(script, "ERR_PERMISSION_EXACT_UNVERIFIED_RUNTIME_BLOCK")
        assertContains(script, "ERR_PERMISSION_EXACT_RESTORE:")
        assertFalse(script.contains("WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE:"))
    }

    @Test
    fun restoreCloneRollbackTargetsCloneUserAndDoesNotPruneLatest() {
        val script = ShellScripts.restoreCloneRollback(
            "com.example.app",
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true),
            appPackage,
        )

        assertContains(script, "DST_USER=10")
        assertContains(script, "SOURCE_KIND='clone_rollback'")
        assertContains(script, "ACTIVE='/data/adb/uclone/clone_rollback/com.example.app/latest'")
        assertContains(script, "EXPECTED_ACTIVE=\"${'$'}ROOT/clone_rollback/${'$'}PKG/latest\"")
        assertContains(script, "ROLLBACK=\"${'$'}ROOT/clone_rollback/${'$'}PKG/restore_${'$'}TS\"")
        assertContains(script, "restore_part \"${'$'}ACTIVE/ce\" \"/data/user/${'$'}DST_USER/${'$'}PKG\"")
        assertFalse(script.contains("prune_old_rollbacks\n"))
    }

    @Test
    fun pushMainToClone_requiresAutoUnlockWhenCeIncluded() {
        val script = ShellScripts.pushMainToClone(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = false),
            appPackage,
        )

        assertContains(script, "ENSURE_CLONE_CE_BEGIN")
        assertContains(script, "CLONE_AUTO_UNLOCK=0")
        assertContains(script, "ERR_CLONE_AUTO_UNLOCK_DISABLED:${'$'}STATE_BEFORE_UNLOCK")
    }

    @Test
    fun switchFromCloneLatest_requiresUnlockedCloneUserAndCeDataByDefault() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true),
            appPackage,
        )

        assertContains(script, "ENSURE_CLONE_CE_BEGIN")
        assertContains(script, "CLONE_AUTO_UNLOCK=1")
        assertContains(script, "start-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("start-user -w"))
        assertContains(script, "/system/bin/cmd lock_settings verify --old")
        assertContains(script, "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE")
        assertContains(script, "ERR_SWITCH_CE_MISSING:${'$'}TRY_USER")
        assertContains(script, "SWITCH_REQUIRE_CE=1")
    }

    @Test
    fun switchFromCloneLatest_doesNotAutoUnlockWhenSettingDisabled() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = false),
            appPackage,
        )

        assertContains(script, "ENSURE_CLONE_CE_BEGIN")
        assertContains(script, "CLONE_AUTO_UNLOCK=0")
        assertContains(script, "ERR_CLONE_AUTO_UNLOCK_DISABLED:${'$'}STATE_BEFORE_UNLOCK")
    }

    @Test
    fun switchFromCloneLatest_doesNotRequireCeWhenRuleExcludesCe() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule(packageName = "com.example.app", includeCe = false),
            settings,
            appPackage,
        )

        assertContains(script, "SWITCH_REQUIRE_CE=0")
        assertContains(script, "ENSURE_CE_SKIPPED=not_required")
    }

    @Test
    fun capture_requiresUnlockedCloneUserAndCeDataByDefault() {
        val script = ShellScripts.capture(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true),
            appPackage,
        )

        assertContains(script, "CAPTURE_REQUIRE_CE=1")
        assertContains(script, "ENSURE_CLONE_CE_BEGIN")
        assertContains(script, "CLONE_AUTO_UNLOCK=1")
        assertContains(script, "start-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("start-user -w"))
        assertContains(script, "/system/bin/cmd lock_settings verify --old")
        assertContains(script, "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE")
        assertContains(script, "ERR_PACKAGE_NOT_LISTED:${'$'}TRY_USER")
        assertContains(script, "ERR_CAPTURE_CE_MISSING:${'$'}TRY_USER")
    }

    @Test
    fun capture_doesNotRequireCeWhenRuleExcludesCe() {
        val script = ShellScripts.capture(
            "com.example.app",
            AppRule(packageName = "com.example.app", includeCe = false),
            settings,
            appPackage,
        )

        assertContains(script, "CAPTURE_REQUIRE_CE=0")
        assertContains(script, "ENSURE_CE_SKIPPED=not_required")
    }

    @Test
    fun probeCloneCe_onlyChecksStateWithoutStartingUnlockingSwitchingOrDeleting() {
        val script = ShellScripts.probeCloneCe(settings)

        assertContains(script, "get-started-user-state")
        assertContains(script, "CLONE_STATE_QUERY_UNAVAILABLE:unexpected_output")
        assertContains(script, "CLONE_STATE_CR=${'$'}(printf '\\r')")
        assertContains(script, "BOOTING|RUNNING_UNLOCKING|RUNNING_UNLOCKED")
        assertContains(script, "USER10_CE_READY=1")
        assertFalse(script.contains("am start-user"))
        assertFalse(script.contains("am unlock-user"))
        assertFalse(script.contains("switch-user"))
        assertFalse(script.contains("rm -rf"))
    }

    @Test
    fun environmentStateProbeUsesTheDirectStateClient() {
        val script = ShellScripts.boundedUserStateProbe(10)

        assertContains(script, "CLONE_USER=10")
        assertContains(script, "CLONE_STATE_QUERY_UNAVAILABLE:unexpected_output")
        assertContains(script, "clone_state \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("CLONE_STATE_QUERY_TIMEOUT"))
        assertFalse(script.contains("CLONE_STATE_TEMP_DIR"))
        assertFalse(script.contains("tr -d '\\r'"))
        assertFalse(script.contains("*RUNNING*"))
        assertFalse(script.contains("*\"not started\"*"))
        assertFalse(script.contains("start-user"))
        assertFalse(script.contains("stop-user"))
    }

    @Test
    fun unlockCloneWithCredential_startsVerifiesAndWaitsWithoutUiAutomation() {
        val script = ShellScripts.unlockCloneWithCredential(settings.copy(cloneUnlockCredential = "123456"))

        assertContains(script, "ENSURE_CLONE_CE_BEGIN")
        assertContains(script, "CLONE_AUTO_UNLOCK=1")
        assertContains(script, "START_USER_BEGIN")
        assertContains(script, "start-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("start-user -w"))
        assertContains(script, "START_USER_PID=${'$'}!")
        assertContains(script, "START_USER_CLIENT_TERMINATED=")
        assertContains(script, "start_user_client_running()")
        assertContains(script, "START_CLIENT_STATE=")
        assertContains(script, "/system/bin/cmd lock_settings verify --old")
        assertContains(script, "IFS= read -r CLONE_UNLOCK_CREDENTIAL")
        assertFalse(script.contains("123456"))
        assertContains(script, "VERIFY_RESULT=")
        assertContains(script, "wait_for_clone_state \"WAIT_AFTER_VERIFY\" 120")
        assertContains(script, "STOP_CLONE_AFTER_TASK=0")
        assertContains(script, "reason=persistent_lifecycle_action")
        assertFalse(script.contains("am stop-user"))
        assertContains(script, "USER10_CE_READY=1")
        assertFalse(script.contains("am unlock-user"))
        assertFalse(script.contains("am switch-user"))
        assertFalse(script.contains("input text"))
        assertFalse(script.contains("KEYCODE_"))
        assertFalse(script.contains("PIN_PAD_TAPS"))
        assertFalse(script.contains("VERIFY_OUTPUT=${'$'}VERIFY_OUTPUT"))
        assertFalse(script.contains("UNLOCK_OUTPUT="))
    }

    @Test
    fun dataTaskDoesNotClaimOrStopAConcurrentManualTransition() {
        listOf("BOOTING", "RUNNING_UNLOCKING").forEach { transition ->
            val result = runDataTaskEnsureScript(
                """
                    case "${'$'}1" in
                      get-started-user-state)
                        count=0
                        [ ! -f "${'$'}UCLONE_TEST_QUERY_COUNT_FILE" ] || count=${'$'}(cat "${'$'}UCLONE_TEST_QUERY_COUNT_FILE")
                        count=${'$'}((count + 1))
                        printf '%s\n' "${'$'}count" > "${'$'}UCLONE_TEST_QUERY_COUNT_FILE"
                        case "${'$'}count" in
                          1) echo "User is not started: 10" ;;
                          2) echo $transition ;;
                          *) echo RUNNING_UNLOCKED ;;
                        esac
                        ;;
                      start-user) echo SHOULD_NOT_START ;;
                      stop-user) echo SHOULD_NOT_STOP ;;
                    esac
                """.trimIndent(),
            )

            assertEquals(0, result.exitCode, result.output)
            assertContains(result.output, "START_CLONE_OWNERSHIP=preexisting_transition")
            assertContains(result.output, "STOP_CLONE_AFTER_TASK=0 startedByTask=0")
            assertFalse(result.output.contains("START_USER_BEGIN"))
            assertFalse(result.output.contains("SHOULD_NOT_START"))
            assertFalse(result.output.contains("SHOULD_NOT_STOP"))
        }
    }

    @Test
    fun dataTaskUsesStableTransitionResultWithoutQueryingAgain() {
        val result = runDataTaskEnsureScript(
            """
                case "${'$'}1" in
                  get-started-user-state)
                    count=0
                    [ ! -f "${'$'}UCLONE_TEST_QUERY_COUNT_FILE" ] || count=${'$'}(cat "${'$'}UCLONE_TEST_QUERY_COUNT_FILE")
                    count=${'$'}((count + 1))
                    printf '%s\n' "${'$'}count" > "${'$'}UCLONE_TEST_QUERY_COUNT_FILE"
                    case "${'$'}count" in
                      1|2) echo BOOTING ;;
                      3) echo "User is not started: 10" ;;
                      *) echo RUNNING_LOCKED ;;
                    esac
                    ;;
                  start-user) echo SHOULD_NOT_START ;;
                  stop-user) echo SHOULD_NOT_STOP ;;
                esac
            """.trimIndent(),
        )

        assertEquals(80, result.exitCode, result.output)
        assertContains(result.output, "WAIT_AFTER_START_TRANSITION_0=User is not started: 10")
        assertContains(result.output, "STATE_AFTER_START_TRANSITION=User is not started: 10")
        assertContains(result.output, "ERR_CLONE_START_TRANSITION_LOST:User is not started: 10")
        assertFalse(result.output.contains("VERIFY_BEGIN"))
        assertFalse(result.output.contains("SHOULD_NOT_START"))
        assertFalse(result.output.contains("SHOULD_NOT_STOP"))
    }

    @Test
    fun dataTasksOnlyRequestNonBlockingStopWhenTheyStartedCloneUser() {
        val script = ShellScripts.capture(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true, stopCloneAfterTask = true),
            appPackage,
        )

        assertContains(script, "[ \"${'$'}CLONE_STARTED_BY_TASK\" != \"1\" ]")
        assertContains(script, "STOP_USER_PID=${'$'}!")
        assertContains(script, "STOP_USER_CLIENT_TERMINATED=")
        assertContains(script, "STOP_USER_CLIENT_DETACHED=")
        assertContains(script, "\"${'$'}STOP_AM_COMMAND\" stop-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("stop-user -w"))
        assertContains(script, "WAIT_AFTER_STOP_${'$'}STOP_WAIT_INDEX")
        assertContains(script, "STOP_POLL_INTERVAL='0.25'")
        assertContains(script, "STOP_USER_EXIT=0")
        assertContains(script, "WARN_STOP_CLONE_REQUEST_FAILED")
        assertTrue(script.indexOf("STOP_CLONE_CONFIRMED=1") < script.indexOf("CLONE_STOPPED_AFTER_TASK=1"))
    }

    @Test
    fun explicitStopRequestsStopWithoutWaitingInsideActivityManager() {
        val script = ShellScripts.stopCloneUser(settings)

        assertContains(script, "\"${'$'}STOP_AM_COMMAND\" stop-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("stop-user -w"))
        assertContains(script, "STOP_USER_PID=${'$'}!")
        assertContains(script, "STOP_USER_CLIENT_TERMINATED=")
        assertContains(script, "STOP_USER_CLIENT_DETACHED=")
        assertContains(script, "STOP_CLONE_CONFIRMED=1")
        assertContains(script, "STOP_USER_EXIT=0")
        assertContains(script, "ERR_STOP_CLONE_REQUEST_FAILED")
        assertContains(script, "ERR_STOP_CLONE_PENDING")
        assertContains(script, "exit 86")
        assertContains(script, "exit 87")
        assertTrue(script.indexOf("STOP_WAIT_INDEX=0") < script.indexOf("ERR_STOP_CLONE_REQUEST_FAILED"))
    }

    @Test
    fun explicitStopFailsImmediatelyWhenActivityManagerRejectsRequest() {
        val result = runExplicitStopScript(
            """
                case "${'$'}1" in
                  get-started-user-state) echo RUNNING_UNLOCKED; exit 0 ;;
                  stop-user) echo permission-denied; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(86, result.exitCode, result.output)
        assertContains(result.output, "STOP_USER_EXIT=9")
        assertContains(result.output, "ERR_STOP_CLONE_REQUEST_FAILED:9")
    }

    @Test
    fun explicitStopFailsWhenUserRemainsRunningAfterAcceptedRequest() {
        val result = runExplicitStopScript(
            """
                case "${'$'}1" in
                  get-started-user-state) echo RUNNING_UNLOCKED; exit 0 ;;
                  stop-user) exit 0 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(87, result.exitCode, result.output)
        assertContains(result.output, "ERR_STOP_CLONE_PENDING:RUNNING_UNLOCKED")
    }

    @Test
    fun explicitStopStopsWaitingForHungActivityManagerAfterStateIsStopped() {
        val result = runExplicitStopScript(
            """
                case "${'$'}1" in
                  get-started-user-state)
                    if [ -f "${'$'}UCLONE_TEST_STATE_FILE" ]; then echo "User is not started: 10"; else echo RUNNING_UNLOCKED; fi
                    exit 0
                    ;;
                  stop-user) : > "${'$'}UCLONE_TEST_STATE_FILE"; exec sleep 5 ;;
                esac
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "STOP_CLONE_CONFIRMED=1")
        assertContains(result.output, "STOP_USER_CLIENT_TERMINATED=1")
        assertTrue(result.executionDurationMs < 2_000, "stop helper waited ${result.executionDurationMs} ms for a hung ActivityManager client")
    }

    @Test
    fun explicitStopDetachesUnkillableActivityManagerClientInsteadOfWaitingForever() {
        val result = runExplicitStopScript(
            amBody = """
                case "${'$'}1" in
                  get-started-user-state) echo RUNNING_UNLOCKED; exit 0 ;;
                  stop-user)
                    echo "${'$'}${'$'}" > "${'$'}UCLONE_TEST_STOP_PID_FILE"
                    exec sleep 5
                    ;;
                esac
            """.trimIndent(),
            shellPrelude = """
                kill() {
                  case "${'$'}1" in
                    -0|-9) signal="${'$'}1"; target="${'$'}2" ;;
                    *) signal=""; target="${'$'}1" ;;
                  esac
                  if [ -f "${'$'}UCLONE_TEST_STOP_PID_FILE" ] && [ "${'$'}target" = "${'$'}(cat "${'$'}UCLONE_TEST_STOP_PID_FILE")" ]; then
                    return 0
                  fi
                  if [ -n "${'$'}signal" ]; then command kill "${'$'}signal" "${'$'}target"; else command kill "${'$'}target"; fi
                }
            """.trimIndent(),
        )

        assertEquals(87, result.exitCode, result.output)
        assertContains(result.output, "STOP_USER_CLIENT_DETACHED=1")
        assertContains(result.output, "ERR_STOP_CLONE_PENDING:RUNNING_UNLOCKED")
        assertTrue(result.executionDurationMs < 2_000, "stop helper waited ${result.executionDurationMs} ms for an unkillable ActivityManager client")
    }

    @Test
    fun explicitStartReturnsAsSoonAsCloneUserIsRunningLocked() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state)
                    if [ -f "${'$'}UCLONE_TEST_STATE_FILE" ]; then echo RUNNING_LOCKED; else echo "User is not started: 10"; fi
                    exit 0
                    ;;
                  start-user) : > "${'$'}UCLONE_TEST_STATE_FILE"; echo started; exit 0 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "START_USER_EXIT=0")
        assertContains(result.output, "START_CLONE_CONFIRMED=RUNNING_LOCKED")
    }

    @Test
    fun explicitStartUsesObservedStateWhenActivityManagerReportsFailure() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state)
                    if [ -f "${'$'}UCLONE_TEST_STATE_FILE" ]; then echo RUNNING_LOCKED; else echo "User is not started: 10"; fi
                    exit 0
                    ;;
                  start-user) : > "${'$'}UCLONE_TEST_STATE_FILE"; echo "Error: could not start user"; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "START_USER_EXIT=9")
        assertContains(result.output, "START_CLONE_CONFIRMED=RUNNING_LOCKED")
    }

    @Test
    fun explicitStartStopsWaitingForHungActivityManagerAfterStateIsRunning() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state)
                    if [ -f "${'$'}UCLONE_TEST_STATE_FILE" ]; then echo RUNNING_LOCKED; else echo "User is not started: 10"; fi
                    exit 0
                    ;;
                  start-user)
                    : > "${'$'}UCLONE_TEST_STATE_FILE"
                    echo "${'$'}${'$'}" > "${'$'}UCLONE_TEST_START_PID_FILE"
                    exec sleep 5
                    ;;
                esac
            """.trimIndent(),
        )
        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "START_CLONE_CONFIRMED=RUNNING_LOCKED")
        assertContains(result.output, "START_USER_CLIENT_TERMINATED=1")
        assertTrue(result.executionDurationMs < 2_000, "start helper waited ${result.executionDurationMs} ms for a hung ActivityManager client")
    }

    @Test
    fun explicitStartDoesNotWaitForAnUnkillableActivityManagerClientAfterStateIsRunning() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state)
                    if [ -f "${'$'}UCLONE_TEST_STATE_FILE" ]; then echo RUNNING_LOCKED; else echo "User is not started: 10"; fi
                    exit 0
                    ;;
                  start-user)
                    : > "${'$'}UCLONE_TEST_STATE_FILE"
                    echo "${'$'}${'$'}" > "${'$'}UCLONE_TEST_START_PID_FILE"
                    exec sleep 5
                    ;;
                esac
            """.trimIndent(),
            shellPrelude = """
                kill() {
                  case "${'$'}1" in
                    -0|-9) signal="${'$'}1"; target="${'$'}2" ;;
                    *) signal=""; target="${'$'}1" ;;
                  esac
                  if [ -f "${'$'}UCLONE_TEST_START_PID_FILE" ] && [ "${'$'}target" = "${'$'}(cat "${'$'}UCLONE_TEST_START_PID_FILE")" ]; then
                    return 0
                  fi
                  if [ -n "${'$'}signal" ]; then command kill "${'$'}signal" "${'$'}target"; else command kill "${'$'}target"; fi
                }
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "START_CLONE_CONFIRMED=RUNNING_LOCKED")
        assertContains(result.output, "START_USER_CLIENT_DETACHED=1")
        assertContains(result.output, "START_USER_CLIENT_TERMINATED=0")
        assertTrue(result.executionDurationMs < 2_000, "start helper waited ${result.executionDurationMs} ms after state was ready")
    }

    @Test
    fun explicitStartFailsBeforeStartingWhenStateProbeReturnsNoResult() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state) exit 2 ;;
                  start-user) echo SHOULD_NOT_START; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(88, result.exitCode, result.output)
        assertContains(result.output, "ERR_CLONE_STATE_QUERY:CLONE_STATE_QUERY_EMPTY:exit=2")
        assertFalse(result.output.contains("SHOULD_NOT_START"))
        assertTrue(result.executionDurationMs < 2_000, "state probe did not fail promptly after an empty result")
    }

    @Test
    fun explicitStartRejectsUnexpectedStateProbeOutputBeforeStarting() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state) echo "Activity manager rejected state request"; exit 2 ;;
                  start-user) echo SHOULD_NOT_START; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(88, result.exitCode, result.output)
        assertContains(result.output, "ERR_CLONE_STATE_QUERY:CLONE_STATE_QUERY_UNAVAILABLE:unexpected_output:exit=2:bytes=")
        assertFalse(result.output.contains("SHOULD_NOT_START"))
        assertTrue(result.executionDurationMs < 2_000, "state probe did not fail promptly after an unexpected result")
    }

    @Test
    fun explicitStartRejectsNonCanonicalRunningStateBeforeStarting() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state) echo RUNNING_BOGUS; exit 0 ;;
                  start-user) echo SHOULD_NOT_START; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(88, result.exitCode, result.output)
        assertContains(result.output, "ERR_CLONE_STATE_QUERY:CLONE_STATE_QUERY_UNAVAILABLE:unexpected_output:exit=0:bytes=")
        assertFalse(result.output.contains("SHOULD_NOT_START"))
    }

    @Test
    fun explicitStartAcceptsOnlyOneTrailingCarriageReturn() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state) printf 'RUNNING_UNLOCKED\r\n'; exit 0 ;;
                  start-user) echo SHOULD_NOT_START; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "START_CLONE_ALREADY_STARTED=RUNNING_UNLOCKED")
        assertFalse(result.output.contains("SHOULD_NOT_START"))
    }

    @Test
    fun explicitStartRejectsAnInternalCarriageReturn() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state) printf 'RUNNING_\rUNLOCKED\n'; exit 0 ;;
                  start-user) echo SHOULD_NOT_START; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(88, result.exitCode, result.output)
        assertContains(result.output, "ERR_CLONE_STATE_QUERY:CLONE_STATE_QUERY_UNAVAILABLE:unexpected_output:exit=0:bytes=")
        assertFalse(result.output.contains("SHOULD_NOT_START"))
    }

    @Test
    fun explicitStartAcceptsKnownTransitionStatesWithoutSubmittingAnotherStart() {
        listOf("BOOTING", "RUNNING_UNLOCKING").forEach { state ->
            val result = runExplicitStartScript(
                """
                    case "${'$'}1" in
                      get-started-user-state) echo $state; exit 0 ;;
                      start-user) echo SHOULD_NOT_START; exit 9 ;;
                    esac
                """.trimIndent(),
            )

            assertEquals(0, result.exitCode, result.output)
            assertContains(result.output, "START_CLONE_ALREADY_STARTING=$state")
            assertFalse(result.output.contains("SHOULD_NOT_START"))
        }
    }

    @Test
    fun explicitStartFailsWhenCloneUserNeverLeavesStoppedState() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state) echo "User is not started: 10"; exit 0 ;;
                  start-user) echo "Error: could not start user"; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(88, result.exitCode, result.output)
        assertContains(result.output, "ERR_START_CLONE_FAILED:requestExit=9")
    }

    @Test
    fun explicitStartDoesNotClaimOrRepeatAConcurrentManualStart() {
        val result = runExplicitStartScript(
            """
                case "${'$'}1" in
                  get-started-user-state)
                    if [ -f "${'$'}UCLONE_TEST_STATE_FILE" ]; then
                      echo RUNNING_LOCKED
                    else
                      : > "${'$'}UCLONE_TEST_STATE_FILE"
                      echo "User is not started: 10"
                    fi
                    exit 0
                    ;;
                  start-user) echo SHOULD_NOT_START; exit 9 ;;
                esac
            """.trimIndent(),
        )

        assertEquals(0, result.exitCode, result.output)
        assertContains(result.output, "START_CLONE_OWNERSHIP=preexisting")
        assertFalse(result.output.contains("START_USER_BEGIN"))
        assertFalse(result.output.contains("SHOULD_NOT_START"))
    }

    @Test
    fun explicitStartDoesNotClaimOrRepeatAConcurrentManualTransition() {
        listOf("BOOTING", "RUNNING_UNLOCKING").forEach { state ->
            val result = runExplicitStartScript(
                """
                    case "${'$'}1" in
                      get-started-user-state)
                        if [ -f "${'$'}UCLONE_TEST_STATE_FILE" ]; then
                          echo $state
                        else
                          : > "${'$'}UCLONE_TEST_STATE_FILE"
                          echo "User is not started: 10"
                        fi
                        exit 0
                        ;;
                      start-user) echo SHOULD_NOT_START; exit 9 ;;
                    esac
                """.trimIndent(),
            )

            assertEquals(0, result.exitCode, result.output)
            assertContains(result.output, "START_CLONE_OWNERSHIP=preexisting_transition")
            assertFalse(result.output.contains("START_USER_BEGIN"))
            assertFalse(result.output.contains("SHOULD_NOT_START"))
        }
    }

    @Test
    fun auditRestoreConsistency_collectsReadOnlyEvidence() {
        val script = ShellScripts.auditRestoreConsistency("com.example.app", settings, appPackage)

        assertContains(script, "AUDIT_DIR=${'$'}OUT")
        assertContains(script, "file_tree_ce.txt")
        assertContains(script, "file_tree_de.txt")
        assertContains(script, "appops_pkg.txt")
        assertContains(script, "appops_uid.txt")
        assertContains(script, "summary.md")
        assertContains(script, "capture_user_states")
        assertContains(script, "restorecon: not run in this read-only audit")
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("restorecon -"))
        assertFalse(script.contains("am switch-user"))
    }

    private fun runExplicitStopScript(
        amBody: String,
        shellPrelude: String = "",
    ): StopScriptResult {
        val directory = Files.createTempDirectory("uclone-stop-test")
        val stateFile = directory.resolve("state").toFile()
        val stopPidFile = directory.resolve("stop-pid").toFile()
        val fakeAm = directory.resolve("am").toFile().apply {
            writeText("#!/bin/sh\n$amBody\n")
            check(setExecutable(true))
        }
        val script = ShellScripts.stopCloneUser(
            settings = settings,
            amCommand = fakeAm.absolutePath,
            sleepCommand = "/bin/sleep",
            stopPollLimit = 2,
            stopPollIntervalSeconds = 0.01,
        )
        val process = ProcessBuilder("/bin/bash", "-c", "$shellPrelude\n$script")
            .redirectErrorStream(true)
            .apply {
                environment()["UCLONE_TEST_STATE_FILE"] = stateFile.absolutePath
                environment()["UCLONE_TEST_STOP_PID_FILE"] = stopPidFile.absolutePath
                environment()["TMPDIR"] = directory.toAbsolutePath().toString()
            }
            .start()
        val startedAt = System.nanoTime()
        val output = process.inputStream.bufferedReader().readText()
        val result = StopScriptResult(
            exitCode = process.waitFor(),
            output = output,
            executionDurationMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
        stopPidFile.takeIf(File::exists)
            ?.readText()
            ?.trim()
            ?.toLongOrNull()
            ?.let(::terminateTestClient)
        return result
    }

    private fun runExplicitStartScript(
        amBody: String,
        shellPrelude: String = "",
    ): StopScriptResult {
        val directory = Files.createTempDirectory("uclone-start-test")
        val stateFile = directory.resolve("state").toFile()
        val startPidFile = directory.resolve("start-pid").toFile()
        val fakeAm = directory.resolve("am").toFile().apply {
            writeText("#!/bin/sh\n$amBody\n")
            check(setExecutable(true))
        }
        val script = ShellScripts.startCloneUser(
            settings = settings,
            amCommand = fakeAm.absolutePath,
            sleepCommand = "/bin/sleep",
            startPollLimit = 2,
            startPollIntervalSeconds = 0.01,
        )
        val process = ProcessBuilder("/bin/bash", "-c", "$shellPrelude\n$script")
            .redirectErrorStream(true)
            .apply {
                environment()["UCLONE_TEST_STATE_FILE"] = stateFile.absolutePath
                environment()["UCLONE_TEST_START_PID_FILE"] = startPidFile.absolutePath
                environment()["TMPDIR"] = directory.toAbsolutePath().toString()
            }
            .start()
        val startedAt = System.nanoTime()
        val output = process.inputStream.bufferedReader().readText()
        val result = StopScriptResult(
            exitCode = process.waitFor(),
            output = output,
            executionDurationMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
        startPidFile.takeIf(File::exists)
            ?.readText()
            ?.trim()
            ?.toLongOrNull()
            ?.let(::terminateTestClient)
        return result
    }

    private fun runDataTaskEnsureScript(amBody: String): StopScriptResult {
        val directory = Files.createTempDirectory("uclone-data-task-lifecycle-test")
        val queryCountFile = directory.resolve("query-count").toFile()
        val fakeAm = directory.resolve("am").toFile().apply {
            writeText("#!/bin/sh\n$amBody\n")
            check(setExecutable(true))
        }
        val captureScript = ShellScripts.capture(
            packageName = "com.example.app",
            rule = AppRule(packageName = "com.example.app"),
            settings = settings.copy(
                autoUnlockClone = true,
                stopCloneAfterTask = true,
                cloneUnlockCredential = "123456",
            ),
            appPackage = appPackage,
        )
        val ensureStart = captureScript.indexOf("CAPTURE_REQUIRE_CE=1\n")
        require(ensureStart >= 0)
        val ensureBodyStart = ensureStart + "CAPTURE_REQUIRE_CE=1\n".length
        val ensureEnd = captureScript.indexOf("capture_cleanup_on_exit()", ensureBodyStart)
        require(ensureEnd > ensureBodyStart)
        val script = captureScript.substring(ensureBodyStart, ensureEnd)
            .replace("/system/bin/am", fakeAm.absolutePath)
            .replace("START_SLEEP_COMMAND='sleep'", "START_SLEEP_COMMAND='/bin/sleep'")
            .replace("START_POLL_INTERVAL='0.25'", "START_POLL_INTERVAL='0.01'")
            .replace("STOP_SLEEP_COMMAND='sleep'", "STOP_SLEEP_COMMAND='/bin/sleep'")
            .replace("STOP_POLL_INTERVAL='0.25'", "STOP_POLL_INTERVAL='0.01'")
            .replace("sleep 0.25", "/bin/sleep 0.01")
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .redirectErrorStream(true)
            .apply {
                environment()["ROOT"] = directory.toAbsolutePath().toString()
                environment()["UCLONE_REQUEST_ID"] = "lifecycle-test"
                environment()["UCLONE_TEST_QUERY_COUNT_FILE"] = queryCountFile.absolutePath
            }
            .start()
        process.outputStream.close()
        val startedAt = System.nanoTime()
        val output = process.inputStream.bufferedReader().readText()
        return StopScriptResult(
            exitCode = process.waitFor(),
            output = output,
            executionDurationMs = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private fun terminateTestClient(pid: Long) {
        runCatching {
            ProcessBuilder("/bin/kill", "-9", pid.toString())
                .start()
                .waitFor()
        }
    }

    private data class StopScriptResult(
        val exitCode: Int,
        val output: String,
        val executionDurationMs: Long,
    )
}

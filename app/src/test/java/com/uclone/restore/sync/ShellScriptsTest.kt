package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.UCloneSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellScriptsTest {
    @Test
    fun workspaceCopiesDoNotRestoreArchivedAppOwners() {
        val script = ShellScripts.capture("com.example.app", AppRule("com.example.app"), UCloneSettings(), "com.uclone.restore")

        assertContains(script, "tar -xopf -")
        assertFalse(script.contains("tar -xpf -) || exit 13"))
    }
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
    fun permissionRestoreUsesBestEffortMergeWithoutRevokeOrReset() {
        val rule = AppRule(packageName = "com.example.app")
        val scripts = listOf(
            ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage),
            ShellScripts.restore("com.example.app", settings, appPackage),
        )

        scripts.forEach { script ->
            assertContains(script, "RESTORED_PERMISSIONS:mode=MERGE")
            assertContains(script, "WARN_APPOPS_WRITE_SETTINGS_FAILED")
            assertFalse(script.contains("package revoke"))
            assertFalse(script.contains("appops reset"))
        }
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

        scripts.forEach { script -> assertContains(script, "ERR_FORCE_STOP_FAILED:") }
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
    fun generatedMutationScriptsAreValidPosixShell() {
        val rule = AppRule(packageName = "com.example.app")
        val scripts = listOf(
            ShellScripts.capture("com.example.app", rule, settings, appPackage),
            ShellScripts.restore("com.example.app", settings, appPackage),
            ShellScripts.switchFromCloneLatest("com.example.app", rule, settings, appPackage),
            ShellScripts.pushMainToClone("com.example.app", rule, settings, appPackage),
            ShellScripts.pushMainToCloneThenRestoreMain(
                "com.example.app",
                "persistent_main",
                rule,
                settings,
                appPackage,
            ),
            ShellScripts.rollback("com.example.app", "20260710-010203", settings, appPackage),
            ShellScripts.restoreCloneRollback("com.example.app", settings, appPackage),
            CrossUserInstallScripts.build(
                "com.example.app",
                settings.cloneUserId,
                CrossUserInstallMode.INSTALL_ONLY,
                rule,
                settings,
                appPackage,
            ),
            CrossUserInstallScripts.build(
                "com.example.app",
                settings.cloneUserId,
                CrossUserInstallMode.INSTALL_WITH_PERMISSIONS,
                rule,
                settings,
                appPackage,
            ),
            CrossUserInstallScripts.build(
                "com.example.app",
                settings.cloneUserId,
                CrossUserInstallMode.INSTALL_AND_SYNC,
                rule,
                settings,
                appPackage,
            ),
        )

        scripts.forEach { script ->
            val scriptFile = Files.createTempFile("uclone-generated-script", ".sh")
            try {
                Files.write(scriptFile, script.toByteArray())
                val process = ProcessBuilder("/bin/sh", "-n", scriptFile.toString()).start()
                val stderr = process.errorStream.bufferedReader().readText()

                assertEquals(0, process.waitFor(), stderr)
            } finally {
                Files.deleteIfExists(scriptFile)
            }
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
        assertContains(script, "(cd \"${'$'}SNAP\" && tar -cpf - .) | (cd \"${'$'}TARGET\" && tar -xpf -)")
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
    fun restoreOwnershipDoesNotFollowSymlinks() {
        val script = ShellScripts.rollback("com.example.app", "20260709-010203", settings, appPackage)

        assertContains(script, "chown -hR")
        assertFalse(script.contains("chown -R"))
    }

    @Test
    fun deleteRestoreBackup_onlyDeletesRequestedPassiveRollback() {
        val script = ShellScripts.deleteRestoreBackup("com.example.app", "20260709-010203", settings, appPackage)

        assertContains(script, "ROLLBACK_PARENT=\"${'$'}ROOT/rollback/${'$'}PKG\"")
        assertContains(script, "TARGET=\"${'$'}ROOT/rollback/${'$'}PKG/${'$'}ROLLBACK_ID\"")
        assertContains(script, "rm -rf \"${'$'}TARGET\"")
        assertContains(script, "DELETED_RESTORE_BACKUP=${'$'}TARGET")
        assertContains(script, "[ \"${'$'}MARKER_ROLLBACK_ID\" = \"${'$'}ROLLBACK_ID\" ]")
        assertFalse(script.contains("find \"${'$'}ROLLBACK_PARENT\" -mindepth 1 -maxdepth 1"))
        assertFalse(script.contains("rm -rf \"${'$'}ROLLBACK_PARENT\""))
        assertFalse(script.contains("rm -rf \"${'$'}ROOT/snapshots"))
        assertFalse(script.contains("rm -rf \"${'$'}ROOT/logs"))
    }

    @Test
    fun resetWorkspace_onlyDeletesKnownUCloneChildren() {
        val script = ShellScripts.resetWorkspace(settings)

        assertContains(script, "ERR_RESET_ROOT_NOT_UCLONE")
        assertContains(script, "ERR_RESET_ROOT_NOT_CANONICAL")
        assertContains(script, "ROOT_REAL=${'$'}(readlink -f")
        assertContains(script, "RESET_TARGETS=\"snapshots rollback clone_rollback switches logs tmp audit config\"")
        assertContains(script, "\"${'$'}ROOT\"/snapshots|\"${'$'}ROOT\"/rollback|\"${'$'}ROOT\"/clone_rollback")
        assertContains(script, "ERR_UNSAFE_RESET_TARGET")
        assertContains(script, "rm -rf \"${'$'}TARGET\"")
        assertContains(script, "UNKNOWN_PACKAGES=")
        assertContains(script, "RESET_STATE_UNKNOWN:${'$'}ACTIVE_PACKAGE")
        assertContains(script, "RESET_WORKSPACE_DONE")
        assertFalse(script.contains("rm -rf \"${'$'}ROOT\""))
        assertFalse(script.contains("/data/user/"))
        assertFalse(script.contains("/data/user_de/"))
        assertFalse(script.contains("/data/media/"))
    }

    @Test
    fun restorePruneMarksStaleSwitchStateUnknown() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)

        assertContains(script, "SWITCH_ID_AFTER_PRUNE=")
        assertContains(script, "[ ! -f \"${'$'}ROLLBACK_PARENT/${'$'}SWITCH_ID_AFTER_PRUNE/manifest.json\" ]")
        assertContains(script, "write_switch_marker_atomic \"${'$'}SWITCH_MARKER_FOR_PRUNE\" \"${'$'}UCLONE_UNKNOWN_STATE_MARKER\"")
        assertContains(script, "SWITCH_MARKER_UNKNOWN=${'$'}SWITCH_MARKER_FOR_PRUNE")
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
        assertContains(script, "restore_part \"${'$'}ACTIVE/obb\" \"/data/media/${'$'}DST_USER/Android/obb/${'$'}PKG\" \"\" \"media\" \"obb\"")
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
        assertContains(script, "stage_switch_marker_unknown || exit 70")
        assertContains(script, "DATA_STATE_COMMITTED=CLONE mainReturnPoint=${'$'}NEXT_MAIN_RETURN_ID")
        assertTrue(script.lastIndexOf("TRANSACTION_COMMITTED=1") < script.lastIndexOf("DATA_STATE_COMMITTED=CLONE"))
        assertFalse(script.contains("SNAPSHOT_ACTIVE="))
        assertFalse(script.contains("mv \"${'$'}TMP\" \"${'$'}BASE/active\""))
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
        assertContains(script, "copy_first_nonempty \"${'$'}PUSH_TEMP/ce\" \"/data/user/${'$'}SRC_USER/${'$'}PKG\"")
        assertContains(script, "restore_part \"${'$'}PUSH_TEMP/ce\" \"/data/user/${'$'}DST_USER/${'$'}PKG\" \"app\"")
        assertContains(script, "PUSH_MAIN_TO_CLONE_DONE")
        assertContains(script, "CLONE_ROLLBACK_PREPARED=${'$'}ROLLBACK_TMP")
        assertContains(script, "CLONE_ROLLBACK_PRESERVED=${'$'}ROLLBACK_TMP")
        assertContains(script, "TRANSACTION_ROLLBACK_PRESERVE")
        assertContains(script, "mv \"${'$'}ROLLBACK_LATEST\" \"${'$'}ROLLBACK_PREVIOUS\"")
        assertContains(script, "mv \"${'$'}ROLLBACK_TMP\" \"${'$'}ROLLBACK_LATEST\"")
        assertTrue(script.indexOf("CLONE_ROLLBACK_PREPARED=") < script.indexOf("uclone_stage_begin RESTORE_DATA"))
        assertTrue(script.indexOf("uclone_stage_begin RESTORE_DATA") < script.lastIndexOf("mv \"${'$'}ROLLBACK_TMP\" \"${'$'}ROLLBACK_LATEST\""))
        assertFalse(script.contains("rm -rf \"${'$'}ROLLBACK\""))
        assertFalse(script.contains("SWITCH_MARKER="))
        assertFalse(script.contains("\"${'$'}ROOT/rollback/${'$'}PKG\""))
    }

    @Test
    fun pushMainToCloneUsesNonDestructiveMergePermissionMigration() {
        val script = ShellScripts.pushMainToClone(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true),
            appPackage,
        )

        assertContains(script, "uclone_capture_permission_state")
        assertContains(script, "uclone_restore_permission_state")
        assertContains(script, "RESTORED_PERMISSIONS:mode=MERGE")
        assertFalse(script.contains("package revoke"))
        assertFalse(script.contains("appops reset"))
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

        assertContains(script, "am get-started-user-state")
        assertContains(script, "USER10_CE_READY=1")
        assertFalse(script.contains("am start-user"))
        assertFalse(script.contains("am unlock-user"))
        assertFalse(script.contains("switch-user"))
        assertFalse(script.contains("rm "))
        assertFalse(script.contains("rm -"))
    }

    @Test
    fun unlockCloneWithCredential_startsVerifiesAndWaitsWithoutUiAutomation() {
        val script = ShellScripts.unlockCloneWithCredential(settings.copy(cloneUnlockCredential = "123456"))

        assertContains(script, "ENSURE_CLONE_CE_BEGIN")
        assertContains(script, "CLONE_AUTO_UNLOCK=1")
        assertContains(script, "START_USER_BEGIN")
        assertContains(script, "start-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("start-user -w"))
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
    fun dataTasksOnlyRequestNonBlockingStopWhenTheyStartedCloneUser() {
        val script = ShellScripts.capture(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings.copy(cloneUnlockCredential = "123456", autoUnlockClone = true, stopCloneAfterTask = true),
            appPackage,
        )

        assertContains(script, "[ \"${'$'}CLONE_STARTED_BY_TASK\" != \"1\" ]")
        assertContains(script, "/system/bin/am stop-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("stop-user -w"))
        assertContains(script, "WAIT_AFTER_STOP_${'$'}STOP_WAIT_INDEX")
        assertContains(script, "sleep 0.25")
        assertContains(script, "STOP_USER_EXIT=0")
        assertContains(script, "WARN_STOP_CLONE_REQUEST_FAILED")
        assertTrue(script.indexOf("STOP_CLONE_CONFIRMED=1") < script.indexOf("CLONE_STOPPED_AFTER_TASK=1"))
    }

    @Test
    fun explicitStopRequestsStopWithoutWaitingInsideActivityManager() {
        val script = ShellScripts.stopCloneUser(settings)

        assertContains(script, "\"${'$'}AM_COMMAND\" stop-user \"${'$'}CLONE_USER\"")
        assertFalse(script.contains("stop-user -w"))
        assertContains(script, "STOP_CLONE_CONFIRMED=1")
        assertContains(script, "STOP_USER_EXIT=0")
        assertContains(script, "ERR_STOP_CLONE_REQUEST_FAILED")
        assertContains(script, "ERR_STOP_CLONE_PENDING")
        assertContains(script, "exit 86")
        assertContains(script, "exit 87")
        assertTrue(script.indexOf("ERR_STOP_CLONE_REQUEST_FAILED") < script.indexOf("STOP_WAIT_INDEX=0"))
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
    fun auditRestoreConsistency_collectsReadOnlyEvidence() {
        val script = ShellScripts.auditRestoreConsistency("com.example.app", settings, appPackage)

        assertContains(script, "AUDIT_DIR=${'$'}OUT")
        assertContains(script, "file_tree_ce.txt")
        assertContains(script, "file_tree_de.txt")
        assertContains(script, "appops_pkg.txt")
        assertContains(script, "appops_uid.txt")
        assertContains(script, "summary.md")
        assertContains(script, "restorecon: not run in this read-only audit")
        assertFalse(script.contains("rm "))
        assertFalse(script.contains("rm -"))
        assertFalse(script.contains("restorecon -"))
        assertFalse(script.contains("am switch-user"))
    }

    private fun runExplicitStopScript(amBody: String): StopScriptResult {
        val directory = Files.createTempDirectory("uclone-stop-test")
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
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return StopScriptResult(process.waitFor(), output)
    }

    private fun runExplicitStartScript(amBody: String): StopScriptResult {
        val directory = Files.createTempDirectory("uclone-start-test")
        val stateFile = directory.resolve("state").toFile()
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
        val process = ProcessBuilder("/bin/bash", "-c", script)
            .redirectErrorStream(true)
            .apply { environment()["UCLONE_TEST_STATE_FILE"] = stateFile.absolutePath }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return StopScriptResult(process.waitFor(), output)
    }

    private data class StopScriptResult(val exitCode: Int, val output: String)
}

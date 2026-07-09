package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.UCloneSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ShellScriptsTest {
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
    fun deleteRestoreBackup_isScopedToPassiveRollbackDirectory() {
        val script = ShellScripts.deleteRestoreBackup("com.example.app", "20260709-010203", settings, appPackage)

        assertContains(script, "ROLLBACK_PARENT=\"${'$'}ROOT/rollback/${'$'}PKG\"")
        assertContains(script, "EXPECTED_CHILD_PREFIX=\"${'$'}ROOT/rollback/${'$'}PKG/\"")
        assertContains(script, "DELETED_RESTORE_BACKUPS=${'$'}ROLLBACK_PARENT")
        assertFalse(script.contains("rm -rf \"${'$'}ROOT/snapshots"))
        assertFalse(script.contains("rm -rf \"${'$'}ROOT/logs"))
    }

    @Test
    fun restorePruneClearsStaleSwitchMarker() {
        val script = ShellScripts.restore("com.example.app", settings, appPackage)

        assertContains(script, "SWITCH_ID_AFTER_PRUNE=")
        assertContains(script, "[ \"${'$'}SWITCH_ID_AFTER_PRUNE\" != \"${'$'}ROLLBACK_ID\" ]")
        assertContains(script, "SWITCH_MARKER_CLEARED=${'$'}SWITCH_MARKER_FOR_PRUNE")
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
    fun switchFromCloneLatest_requiresUnlockedCloneUserAndCeDataByDefault() {
        val script = ShellScripts.switchFromCloneLatest(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings,
            appPackage,
        )

        assertContains(script, "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE")
        assertContains(script, "ERR_SWITCH_CE_MISSING:${'$'}TRY_USER")
        assertContains(script, "SWITCH_REQUIRE_CE=1")
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
    }

    @Test
    fun capture_requiresUnlockedCloneUserAndCeDataByDefault() {
        val script = ShellScripts.capture(
            "com.example.app",
            AppRule(packageName = "com.example.app"),
            settings,
            appPackage,
        )

        assertContains(script, "CAPTURE_REQUIRE_CE=1")
        assertContains(script, "ERR_USER_NOT_UNLOCKED:${'$'}TRY_USER:${'$'}STATE")
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
    fun unlockCloneWithCredential_triesLockSettingsThenUiInputWithRedactedLogs() {
        val script = ShellScripts.unlockCloneWithCredential(settings.copy(cloneUnlockCredential = "123456"))

        assertContains(script, "cmd lock_settings verify --old")
        assertContains(script, "LOCK_SETTINGS_VERIFY_RESULT=")
        assertContains(script, "am switch-user")
        assertContains(script, "START_USER_BEGIN")
        assertContains(script, "DIGIT_KEYEVENTS_BEGIN")
        assertContains(script, "KEYCODE_${'$'}DIGIT")
        assertContains(script, "BEFORE_INPUT_FOCUS=")
        assertContains(script, "input text")
        assertContains(script, "INPUT_TEXT_SENT_LENGTH=6")
        assertContains(script, "RETURN_MAIN_BEGIN")
        assertFalse(script.contains("am unlock-user"))
        assertFalse(script.contains("VERIFY_OUTPUT=${'$'}VERIFY_OUTPUT"))
        assertFalse(script.contains("UNLOCK_OUTPUT="))
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
}

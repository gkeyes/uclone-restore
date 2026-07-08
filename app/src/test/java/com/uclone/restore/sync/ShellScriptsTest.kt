package com.uclone.restore.sync

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
}

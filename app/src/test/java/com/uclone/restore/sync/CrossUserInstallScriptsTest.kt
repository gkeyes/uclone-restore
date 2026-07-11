package com.uclone.restore.sync

import com.uclone.restore.model.AppRule
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.UCloneSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class CrossUserInstallScriptsTest {
    private val settings = UCloneSettings(
        mainUserId = 0,
        cloneUserId = 10,
        autoUnlockClone = true,
        cloneUnlockCredential = "123456",
    )
    private val rule = AppRule(packageName = "com.example.app")

    @Test
    fun installOnlyNeverStartsOrUnlocksClone() {
        val script = script(targetUser = 10, mode = CrossUserInstallMode.INSTALL_ONLY)

        assertContains(script, "cmd package install-existing --user")
        assertContains(script, "install_stage INSTALL_PACKAGE")
        assertContains(script, "awk -v expected=\"package:${'$'}PKG\"")
        assertContains(script, "INSTALL_ONLY_DONE targetUser=${'$'}DST_USER")
        assertFalse(script.contains("ENSURE_CLONE_CE_BEGIN"))
        assertFalse(script.contains("/data/user/"))
        assertFalse(script.contains("uninstall"))
    }

    @Test
    fun permissionModeMigratesStateWithoutUnlockingClone() {
        val script = script(targetUser = 10, mode = CrossUserInstallMode.INSTALL_WITH_PERMISSIONS)

        assertContains(script, "cmd package grant --user")
        assertContains(script, "cmd appops set --user")
        assertContains(script, "INSTALL_PERMISSIONS_DONE")
        assertFalse(script.contains("ENSURE_CLONE_CE_BEGIN"))
    }

    @Test
    fun dataModeReusesDirectionalSyncAndKeepsInstallOnFailure() {
        val push = script(targetUser = 10, mode = CrossUserInstallMode.INSTALL_AND_SYNC)
        val restore = script(targetUser = 0, mode = CrossUserInstallMode.INSTALL_AND_SYNC)

        assertContains(push, "PUSH_USERS source=${'$'}SRC_USER target=${'$'}DST_USER")
        assertContains(restore, "COMPOSITE_STEP=CAPTURE_SNAPSHOT_FROM_CLONE")
        assertContains(push, "WARN_INSTALL_SYNC_FAILED:")
        assertContains(push, "INSTALL_PARTIAL_FATAL")
        assertContains(push, "exit 91")
        assertContains(push, "INSTALL_PARTIAL_SUCCESS")
        assertFalse(push.contains("pm uninstall"))
        assertFalse(push.contains("cmd package uninstall"))
    }

    private fun script(targetUser: Int, mode: CrossUserInstallMode): String =
        CrossUserInstallScripts.build(
            packageName = "com.example.app",
            targetUserId = targetUser,
            mode = mode,
            rule = rule,
            settings = settings,
            appPackage = "com.uclone.restore",
            requestId = "request-1",
        )
}

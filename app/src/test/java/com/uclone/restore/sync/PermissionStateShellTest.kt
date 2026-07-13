package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertContains

class PermissionStateShellTest {
    @Test
    fun exactCaptureFailsBeforeWritingAnUnverifiedPermissionSnapshot() {
        val script = PermissionStateShell.functions()

        assertContains(script, "CAPTURE_MODE=\"${'$'}{3:-MERGE}\"")
        assertContains(script, "ERR_PERMISSION_EXACT_UNVERIFIED_RUNTIME_BLOCK:user=${'$'}PERMISSION_USER")
        assertContains(script, "uclone_permission_capture_exact_valid")
    }

    @Test
    fun exactRestoreCapturesCurrentPermissionsInExactMode() {
        val script = PermissionStateShell.functions()

        assertContains(
            script,
            "uclone_capture_permission_state \"${'$'}CURRENT_PERMISSION_DIR\" \"${'$'}PERMISSION_USER\" EXACT",
        )
    }

    @Test
    fun permissionCaptureFilesMustRemainCanonical() {
        val script = PermissionStateShell.functions()

        assertContains(script, "uclone_permission_require_directory()")
        assertContains(script, "uclone_permission_require_file()")
        assertContains(script, "uclone_permission_require_file \"${'$'}PERMISSION_STATE\"")
        assertContains(script, "uclone_permission_require_file \"${'$'}PERMISSION_DIR/runtime_grants.txt\"")
        assertContains(script, "uclone_permission_require_file \"${'$'}PERMISSION_DIR/appops.txt\"")
    }

    @Test
    fun restoreRetainsCapturedCustomRuntimePermissions() {
        val script = PermissionStateShell.functions()

        assertContains(script, "^[[:space:]]*[A-Za-z][A-Za-z0-9_.]*:")
        assertContains(script, "case \"${'$'}PERMISSION\" in *[!A-Za-z0-9_.]*|\"\") continue ;; esac")
        assertContains(script, "uclone_permission_capture_valid_for_user")
    }
}

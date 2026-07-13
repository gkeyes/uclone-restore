package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class WorkspacePathGuardTest {
    @Test
    fun writableGuardRejectsSymlinksAtEveryManagedStructuralDepth() {
        val script = WorkspacePathGuard.require("/data/adb/uclone")

        assertContains(script, "ERR_WORKSPACE_STRUCTURAL_SYMLINK")
        assertContains(script, "ERR_UNSAFE_WORKSPACE_STRUCTURAL_PATH")
        assertContains(script, "snapshots/${'$'}UCLONE_GLOB_STAR/history/${'$'}UCLONE_GLOB_STAR")
        assertContains(script, "rollback/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR")
        assertContains(script, "clone_rollback/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR")
        assertContains(script, "transactions/${'$'}UCLONE_GLOB_STAR/gates/${'$'}UCLONE_GLOB_STAR/gate.state")
        assertContains(script, "ERR_UNTRUSTED_WORKSPACE_OWNER")
        assertContains(script, "ERR_UNTRUSTED_WORKSPACE_MODE")
        assertContains(script, "ERR_WORKSPACE_WHITESPACE")
        assertContains(script, "uclone_assert_single_filesystem")
        assertContains(script, "[0-7][0145][0145]")
        assertContains(script, "uclone_guard_workspace_directory")
    }

    @Test
    fun structuralGuardDoesNotTraversePayloadContents() {
        val script = WorkspacePathGuard.require("/data/adb/uclone")

        assertFalse("snapshots/${'$'}UCLONE_GLOB_STAR/active/${'$'}UCLONE_GLOB_STAR" in script)
        assertFalse("rollback/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR" in script)
        assertFalse("clone_rollback/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR/${'$'}UCLONE_GLOB_STAR" in script)
    }

    @Test
    fun readOnlyGuardAlsoRejectsUnsafeStructuralPaths() {
        val script = WorkspacePathGuard.inspect("/data/adb/uclone")

        assertContains(script, "ERR_WORKSPACE_STRUCTURAL_SYMLINK")
        assertContains(script, "ERR_UNSAFE_WORKSPACE_STRUCTURAL_PATH")
    }
}

package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WorkspaceOwnershipTest {
    @Test
    fun repairIsExplicitBatchedAndNeverDeletesWorkspaceFiles() {
        val script = WorkspaceOwnershipScripts.repair("/data/adb/uclone")

        assertContains(script, "ROOT_REAL=${'$'}(readlink -f")
        assertContains(script, "set -o pipefail")
        assertContains(script, "/data/adb/uclone)")
        assertContains(script, "snapshots rollback clone_rollback tmp")
        assertContains(script, "find \"${'$'}WORKSPACE_TARGET\" -xdev")
        assertContains(script, "xargs -0 -n 500")
        assertContains(script, "chown -h 0:0 \"${'$'}@\"")
        assertContains(script, "WORKSPACE_OWNER_VERIFY remaining=")
        assertContains(script, "WORKSPACE_OWNER_REPAIR_DONE changed=")
        assertFalse(script.contains("rm -"))
        assertFalse(script.contains("restorecon"))
        assertFalse(script.contains("chmod -R"))
        assertContains(script, "ERR_WORKSPACE_SCAN:${'$'}WORKSPACE_TARGET:total")
        assertContains(script, "ERR_WORKSPACE_SCAN:${'$'}WORKSPACE_TARGET:size")
    }

    @Test
    fun customWorkspaceRequiresIdentityMarker() {
        val script = WorkspaceOwnershipScripts.scan("/data/adb/custom")

        assertContains(script, "config/workspace.identity")
        assertContains(script, "com.uclone.restore.workspace.v1")
        assertContains(script, "ERR_UNTRUSTED_WORKSPACE")
    }

    @Test
    fun parserUsesLatestVerificationScan() {
        val report = WorkspaceOwnershipReportParser.parse(
            """
                WORKSPACE_OWNER_SCAN root=/data/adb/uclone total=200 nonRoot=18 sizeKb=4096
                WORKSPACE_OWNER_BATCH changed=18
                WORKSPACE_OWNER_SCAN root=/data/adb/uclone total=200 nonRoot=0 sizeKb=4096
            """.trimIndent(),
        )

        assertNotNull(report)
        assertEquals("/data/adb/uclone", report.rootPath)
        assertEquals(200L, report.totalEntries)
        assertEquals(0L, report.nonRootEntries)
        assertEquals(4096L, report.totalSizeKb)
    }
}

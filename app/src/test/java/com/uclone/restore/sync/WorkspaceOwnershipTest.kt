package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WorkspaceOwnershipTest {
    @Test
    fun repairIsExplicitBatchedAndNeverDeletesWorkspaceFiles() {
        val script = WorkspaceOwnershipScripts.repair("/data/adb/uclone", "/data/adb/uclone")

        assertContains(script, "find \"${'$'}WORKSPACE_TARGET\" -xdev")
        assertContains(script, "xargs -0 -n 500")
        assertContains(script, "chown -h 0:0 \"${'$'}@\"")
        assertContains(script, "WORKSPACE_OWNER_VERIFY remaining=")
        assertContains(script, "WORKSPACE_OWNER_REPAIR_DONE changed=")
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("restorecon"))
        assertFalse(script.contains("chmod -R"))
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
        assertEquals("/data/adb/uclone", report.canonicalRoot)
        assertEquals(0L, report.nonRootEntries)
    }
}

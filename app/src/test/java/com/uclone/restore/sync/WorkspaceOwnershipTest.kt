package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val script = WorkspaceOwnershipScripts.scanTask("/data/adb/custom")

        assertContains(script, "config/workspace.identity")
        assertContains(script, "com.uclone.restore.workspace.v1")
        assertContains(script, "ERR_UNTRUSTED_WORKSPACE")
    }

    @Test
    fun readOnlyScanClaimsTheRootTaskAdmissionLockBeforeScanning() {
        val script = RootTaskScript.wrap(
            logPath = "/data/adb/uclone/logs/workspace_scan.log",
            header = "TASK=SCAN_WORKSPACE_OWNERSHIP\n",
            body = WorkspaceOwnershipScripts.scanTask("/data/adb/uclone"),
            startedAt = 1_000L,
            activeTask = ActiveRootTask(
                rootDir = "/data/adb/uclone",
                requestId = "scan-1",
                taskType = "SCAN_WORKSPACE_OWNERSHIP",
                packageName = "workspace",
                startedAt = 1_000L,
            ),
        )

        val claimIndex = script.indexOf("uclone_claim_active_task")
        val scanIndex = script.lastIndexOf("workspace_owner_scan")
        assertTrue(claimIndex >= 0)
        assertTrue(scanIndex > claimIndex)
        assertContains(script, "locks/active_task")
        assertContains(script, "ERR_ACTIVE_ROOT_TASK")
        assertContains(script, "taskType=SCAN_WORKSPACE_OWNERSHIP")
        assertContains(script, "trap 'uclone_release_active_task' EXIT")
        assertFalse(script.contains("xargs -0 -n 500"))
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
        assertEquals(200L, report.totalEntries)
        assertEquals(0L, report.nonRootEntries)
        assertEquals(4096L, report.totalSizeKb)
    }
}

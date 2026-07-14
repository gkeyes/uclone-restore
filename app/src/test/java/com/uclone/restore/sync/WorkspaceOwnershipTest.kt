package com.uclone.restore.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class WorkspaceOwnershipTest {
    @Test
    fun repairIsExplicitBatchedAndNeverDeletesWorkspaceFiles() {
        val script = WorkspaceOwnershipScripts.repair("/data/adb/uclone", "/data/adb/uclone")

        assertContains(script, "find \"${'$'}WORKSPACE_TARGET\" -xdev")
        assertContains(script, "! -user root")
        assertContains(script, "! -group root")
        assertFalse(script.contains("-uid 0"))
        assertFalse(script.contains("-gid 0"))
        assertContains(script, "xargs -0 -n 500")
        assertContains(script, "chown -h 0:0 \"${'$'}@\"")
        assertContains(script, "WORKSPACE_OWNER_VERIFY remaining=")
        assertContains(script, "WORKSPACE_OWNER_REPAIR_DONE changed=")
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("restorecon"))
        assertFalse(script.contains("chmod"))
        assertFalse(script.contains("workspace_owner_root_v1"))
    }

    @Test
    fun customWorkspaceRequiresIdentityMarker() {
        val script = WorkspaceOwnershipScripts.scan("/data/adb/custom")
        assertContains(script, "config/workspace.identity")
        assertContains(script, "com.uclone.restore.workspace.v1")
        assertContains(script, "ERR_UNTRUSTED_WORKSPACE")
        assertContains(script, "! -user root")
        assertContains(script, "! -group root")
        assertFalse(script.contains("-uid 0"))
        assertFalse(script.contains("-gid 0"))
    }

    @Test
    fun generatedScanCountsNonRootEntriesInARealWorkspace() {
        val rootGroupAvailable = ProcessBuilder("/bin/sh", "-c", "find / -prune -group root >/dev/null 2>&1")
            .start()
            .waitFor() == 0
        assumeTrue("host must provide the Android/Linux root group", rootGroupAvailable)

        val directory = Files.createTempDirectory("uclone-owner-scan-").toRealPath().toFile()
        try {
            directory.resolve("config").mkdirs()
            directory.resolve("config/workspace.identity").writeText("com.uclone.restore.workspace.v1\n")
            directory.resolve("snapshots").mkdirs()
            directory.resolve("snapshots/sample.bin").writeText("payload")

            val process = ProcessBuilder(
                "/bin/bash",
                "-c",
                WorkspaceOwnershipScripts.scan(directory.absolutePath),
            ).start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val report = WorkspaceOwnershipReportParser.parse(output)

            assertEquals(0, exitCode, error)
            assertNotNull(report)
            assertEquals(directory.absolutePath, report.canonicalRoot)
            assertTrue(report.totalEntries >= 2)
            assertTrue(report.nonRootEntries >= 2)
            assertTrue(report.totalSizeKb > 0)
        } finally {
            directory.deleteRecursively()
        }
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

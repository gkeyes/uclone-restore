package com.uclone.restore.ui

import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.WorkspaceOwnershipReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceOwnershipReportPolicyTest {
    @Test
    fun scanReportIsBoundToTheExactSettingsSnapshot() {
        val settings = UCloneSettings(mainUserId = 2, cloneUserId = 12, rootDir = "/data/adb/custom")

        val bound = WorkspaceOwnershipReportPolicy.bind(report(), settings)

        assertEquals("/data/adb/custom", bound.scannedRootDir)
        assertEquals(2, bound.scannedMainUserId)
        assertEquals(12, bound.scannedCloneUserId)
        assertTrue(WorkspaceOwnershipReportPolicy.isFromSettings(bound, settings))
    }

    @Test
    fun rootOrUserChangesClearTheExistingReport() {
        val previous = UCloneSettings()
        val report = WorkspaceOwnershipReportPolicy.bind(report(), previous)

        assertNull(
            WorkspaceOwnershipReportPolicy.retainAfterSettingsChange(
                report,
                previous,
                previous.copy(rootDir = "/data/adb/other"),
            ),
        )
        assertNull(
            WorkspaceOwnershipReportPolicy.retainAfterSettingsChange(
                report,
                previous,
                previous.copy(mainUserId = 1),
            ),
        )
        assertNull(
            WorkspaceOwnershipReportPolicy.retainAfterSettingsChange(
                report,
                previous,
                previous.copy(cloneUserId = 11),
            ),
        )
    }

    @Test
    fun unrelatedSettingsChangesPreserveTheExistingReport() {
        val previous = UCloneSettings()
        val report = WorkspaceOwnershipReportPolicy.bind(report(), previous)

        val retained = WorkspaceOwnershipReportPolicy.retainAfterSettingsChange(
            report,
            previous,
            previous.copy(includeMedia = true),
        )

        assertSame(report, retained)
    }

    @Test
    fun repairRejectsAReportWithDifferentCanonicalRoot() {
        val settings = UCloneSettings(rootDir = "/data/adb/uclone")
        val mismatched = WorkspaceOwnershipReportPolicy.bind(
            report(canonicalRoot = "/data/adb/other"),
            settings,
        )

        assertTrue(WorkspaceOwnershipReportPolicy.isFromSettings(mismatched, settings))
        assertFalse(WorkspaceOwnershipReportPolicy.canRepair(mismatched, settings))
    }

    @Test
    fun repairRejectsAReportFromDifferentUsersEvenWhenRootMatches() {
        val settings = UCloneSettings()
        val stale = WorkspaceOwnershipReportPolicy.bind(report(), settings.copy(cloneUserId = 11))

        assertFalse(WorkspaceOwnershipReportPolicy.isFromSettings(stale, settings))
        assertFalse(WorkspaceOwnershipReportPolicy.canRepair(stale, settings))
    }

    private fun report(canonicalRoot: String = "/data/adb/uclone") = WorkspaceOwnershipReport(
        canonicalRoot = canonicalRoot,
        totalEntries = 20,
        nonRootEntries = 3,
        totalSizeKb = 1024,
    )
}

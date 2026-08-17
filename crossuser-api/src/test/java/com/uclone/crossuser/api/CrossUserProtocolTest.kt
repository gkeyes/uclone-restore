package com.uclone.crossuser.api

import kotlin.test.Test
import kotlin.test.assertEquals

class CrossUserProtocolTest {
    @Test
    fun missingCapabilityDefaultsToUnknown() {
        val report = CrossUserCapabilityReport(
            sdkInt = 36,
            buildFingerprint = "test",
            rootUid = 0,
            rootSelinuxContext = "u:r:su:s0",
            selinuxEnforcing = true,
            adapterId = "fake",
            capabilities = emptyList(),
        )

        assertEquals(CrossUserProtocol.STATUS_UNKNOWN, report.statusOf("missing"))
    }
}

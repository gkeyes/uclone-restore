package com.uclone.crossuser.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossUserProtocolTest {
    @Test
    fun targetCatalogAllowsOnlyExactProbeAndWeChatPairs() {
        assertTrue(
            CrossUserProtocol.isAllowedTarget(
                CrossUserProtocol.PROBE_PACKAGE,
                CrossUserProtocol.PROBE_COMPONENT,
            ),
        )
        assertTrue(
            CrossUserProtocol.isAllowedTarget(
                CrossUserProtocol.WECHAT_PACKAGE,
                CrossUserProtocol.WECHAT_COMPONENT,
            ),
        )
        assertFalse(
            CrossUserProtocol.isAllowedTarget(
                CrossUserProtocol.WECHAT_PACKAGE,
                CrossUserProtocol.PROBE_COMPONENT,
            ),
        )
        assertEquals(null, CrossUserProtocol.componentForPackage("com.example.other"))
    }

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

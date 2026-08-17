package com.uclone.restore.crossuser

import com.uclone.crossuser.api.CrossUserCapabilityEntry
import com.uclone.crossuser.api.CrossUserCapabilityReport
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.runtime.CrossUserRuntimeErrors
import com.uclone.restore.crossuser.ui.previewDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossUserPreviewDecisionTest {
    @Test
    fun visibleUsersFalseIsFailClosed() {
        val decision = previewDecision(report(CrossUserProtocol.STATUS_UNSUPPORTED))

        assertFalse(decision.canOpen)
        assertEquals(CrossUserRuntimeErrors.FEATURE_VISIBLE_USERS_DISABLED, decision.errorCode)
    }

    @Test
    fun supportStillRequiresRootAndEnforcing() {
        assertTrue(previewDecision(report(CrossUserProtocol.STATUS_SUPPORTED)).canOpen)
        assertFalse(previewDecision(report(CrossUserProtocol.STATUS_SUPPORTED, rootUid = 2000)).canOpen)
        assertFalse(previewDecision(report(CrossUserProtocol.STATUS_SUPPORTED, enforcing = false)).canOpen)
    }

    private fun report(
        status: String,
        rootUid: Int = 0,
        enforcing: Boolean = true,
    ) = CrossUserCapabilityReport(
        sdkInt = 37,
        buildFingerprint = "test/fingerprint",
        rootUid = rootUid,
        rootSelinuxContext = "u:r:ksu:s0",
        selinuxEnforcing = enforcing,
        adapterId = "fake",
        capabilities = listOf(
            CrossUserCapabilityEntry(
                name = CrossUserProtocol.CAPABILITY_VISIBLE_BACKGROUND_USERS_RUNTIME,
                status = status,
                reasonCode = "test",
                humanMessage = status,
            ),
        ),
    )
}

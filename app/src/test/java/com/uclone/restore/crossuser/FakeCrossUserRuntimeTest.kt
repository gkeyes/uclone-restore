package com.uclone.restore.crossuser

import com.uclone.restore.crossuser.client.CrossUserProbeRequest
import com.uclone.restore.crossuser.client.FakeCrossUserRuntime
import com.uclone.restore.crossuser.domain.CrossUserCapabilityKey
import com.uclone.restore.crossuser.domain.CrossUserErrorCode
import com.uclone.restore.crossuser.domain.CrossUserSessionConfig
import com.uclone.restore.crossuser.domain.CrossUserSessionState
import com.uclone.restore.crossuser.domain.CrossUserSupportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FakeCrossUserRuntimeTest {
    @Test
    fun fakeProbeNeverClaimsDeviceSupport() {
        val runtime = FakeCrossUserRuntime(clockMillis = { 1L }, randomBytes = { it.fill(1) })

        val report = runtime.probe(
            CrossUserProbeRequest(
                mainUserId = 0,
                targetUserId = 10,
                probePackageName = "com.gkeyes.uclone.crossuserprobe",
            ),
        )

        assertEquals(
            CrossUserSupportStatus.UNKNOWN,
            report.statusOf(CrossUserCapabilityKey.LAUNCH_AS_USER_ON_DISPLAY),
        )
    }

    @Test
    fun fakeSessionStopsAtUnsupportedAndRecordsError() {
        var now = 100L
        val runtime = FakeCrossUserRuntime(
            clockMillis = {
                now += 1
                now
            },
            randomBytes = { it.fill(2) },
        )

        val snapshot = runtime.createSession(
            CrossUserSessionConfig(
                packageName = "com.gkeyes.uclone.crossuserprobe",
                mainUserId = 0,
                targetUserId = 10,
                widthPx = 1080,
                heightPx = 2400,
                densityDpi = 440,
                rotation = 0,
                backendId = "fake",
            ),
        )

        assertEquals(CrossUserSessionState.UNSUPPORTED, snapshot.state)
        assertEquals(CrossUserErrorCode.FAKE_BACKEND_ONLY, snapshot.lastErrorCode)
        assertNotNull(runtime.closeSession(snapshot.handle.sessionId))
    }
}

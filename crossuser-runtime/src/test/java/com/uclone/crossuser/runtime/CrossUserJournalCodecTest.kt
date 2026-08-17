package com.uclone.crossuser.runtime

import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CrossUserJournalCodecTest {
    @Test
    fun stateRoundTripsWithoutSurfaceOrToken() {
        val state = CrossUserSessionState(
            sessionId = "session",
            state = CrossUserProtocol.SESSION_WAITING_FIRST_FRAME,
            displayId = 42,
            taskId = 7,
            mainUserId = 0,
            targetUserId = 10,
            packageName = CrossUserProtocol.PROBE_PACKAGE,
            userWasRunning = false,
            userStartedBySession = true,
            message = "ready",
        )

        assertEquals(state, CrossUserJournalCodec.decode(CrossUserJournalCodec.encode(state)))
    }

    @Test
    fun otherPackageJournalIsRejected() {
        val encoded = CrossUserJournalCodec.encode(
            CrossUserSessionState(
                sessionId = "session",
                state = CrossUserProtocol.SESSION_FAILED,
                displayId = 42,
                taskId = -1,
                mainUserId = 0,
                targetUserId = 10,
                packageName = CrossUserProtocol.PROBE_PACKAGE,
                userWasRunning = true,
                userStartedBySession = false,
            ),
        ).replace(CrossUserProtocol.PROBE_PACKAGE, "com.example.other")

        assertFailsWith<IllegalArgumentException> { CrossUserJournalCodec.decode(encoded) }
    }
}

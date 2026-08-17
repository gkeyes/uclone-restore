package com.uclone.restore.crossuser

import com.uclone.restore.crossuser.domain.CrossUserErrorCode
import com.uclone.restore.crossuser.domain.CrossUserSessionConfig
import com.uclone.restore.crossuser.domain.CrossUserSessionHandle
import com.uclone.restore.crossuser.domain.CrossUserSessionState
import com.uclone.restore.crossuser.session.CrossUserSessionStateMachine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CrossUserSessionStateMachineTest {
    @Test
    fun happyPathReachesActiveWithDisplayAndTaskEvidence() {
        val machine = newMachine()

        machine.transitionTo(CrossUserSessionState.PRECHECKING, "precheck")
        machine.transitionTo(CrossUserSessionState.SURFACE_READY, "surface")
        machine.transitionTo(CrossUserSessionState.DISPLAY_CREATING, "display_create")
        machine.transitionTo(CrossUserSessionState.DISPLAY_READY, "display_ready", displayId = 42)
        machine.transitionTo(CrossUserSessionState.USER_STARTING, "start_user")
        machine.transitionTo(CrossUserSessionState.USER_VISIBLE_UNLOCKED, "visible_unlocked")
        machine.transitionTo(CrossUserSessionState.TARGET_LAUNCHING, "launch")
        machine.transitionTo(CrossUserSessionState.WAITING_FIRST_FRAME, "wait_first_frame", taskId = 7)
        val active = machine.transitionTo(CrossUserSessionState.ACTIVE, "first_frame")

        assertEquals(CrossUserSessionState.ACTIVE, active.state)
        assertEquals(42, active.displayId)
        assertEquals(7, active.taskId)
        assertEquals(9, active.transitions.size)
    }

    @Test
    fun illegalTransitionIsRejected() {
        val machine = newMachine()

        assertFailsWith<IllegalArgumentException> {
            machine.transitionTo(CrossUserSessionState.ACTIVE, "skip_required_gates")
        }
    }

    @Test
    fun failureKeepsStableErrorCodeAndCanCleanup() {
        val machine = newMachine()
        machine.transitionTo(CrossUserSessionState.PRECHECKING, "precheck")
        val failed = machine.transitionTo(
            CrossUserSessionState.UNSUPPORTED,
            "visible_user_missing",
            errorCode = CrossUserErrorCode.VISIBLE_USER_API_MISSING,
        )

        assertEquals(CrossUserErrorCode.VISIBLE_USER_API_MISSING, failed.lastErrorCode)
        val closed = machine.close("cleanup_after_failure")

        assertEquals(CrossUserSessionState.CLOSED, closed.state)
        assertEquals(CrossUserErrorCode.VISIBLE_USER_API_MISSING, closed.lastErrorCode)
    }

    @Test
    fun closeIsIdempotent() {
        val machine = newMachine()
        machine.transitionTo(CrossUserSessionState.PRECHECKING, "precheck")
        val first = machine.close("user_close")
        val second = machine.close("duplicate_close")

        assertEquals(CrossUserSessionState.CLOSED, first.state)
        assertEquals(first, second)
    }

    private fun newMachine(): CrossUserSessionStateMachine {
        var now = 1000L
        return CrossUserSessionStateMachine(
            handle = CrossUserSessionHandle("session-test"),
            config = CrossUserSessionConfig(
                packageName = "com.example.app",
                mainUserId = 0,
                targetUserId = 10,
                widthPx = 1080,
                heightPx = 2400,
                densityDpi = 440,
                rotation = 0,
                backendId = "fake",
            ),
            clockMillis = {
                now += 10
                now
            },
        )
    }
}

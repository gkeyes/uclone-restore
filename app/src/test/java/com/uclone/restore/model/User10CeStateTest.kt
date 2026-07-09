package com.uclone.restore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class User10CeStateTest {
    @Test
    fun fromRaw_detectsRunningUnlocked() {
        val state = User10CeState.fromRaw("RUNNING_UNLOCKED")

        assertEquals(User10CeState.RunningUnlocked, state)
        assertTrue(state.allowsCeCapture)
    }

    @Test
    fun fromRaw_treatsRunningLockedAsStartedLocked() {
        val state = User10CeState.fromRaw("RUNNING_LOCKED")

        assertEquals(User10CeState.StartedLocked, state)
        assertFalse(state.allowsCeCapture)
    }

    @Test
    fun fromRaw_detectsNotStartedMessages() {
        val state = User10CeState.fromRaw("User is not started: 10")

        assertEquals(User10CeState.NotStarted, state)
        assertFalse(state.allowsCeCapture)
    }

    @Test
    fun fromRaw_marksMissingUserUnavailable() {
        val state = User10CeState.fromRaw("RUNNING_UNLOCKED", userPresent = false)

        assertEquals(User10CeState.Unavailable, state)
        assertFalse(state.allowsCeCapture)
    }

    @Test
    fun fromRaw_preservesUnknownRawState() {
        val state = User10CeState.fromRaw("FAILED transaction")

        assertIs<User10CeState.Unknown>(state)
        assertEquals("FAILED transaction", state.raw)
        assertFalse(state.allowsCeCapture)
    }
}

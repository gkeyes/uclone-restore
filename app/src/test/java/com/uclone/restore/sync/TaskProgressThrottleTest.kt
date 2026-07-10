package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskProgressThrottleTest {
    @Test
    fun regularProgressIsThrottledButForcedTransitionsAreImmediate() {
        var now = 1000L
        val throttle = TaskProgressThrottle(intervalMs = 350) { now }

        assertTrue(throttle.shouldEmit())
        now += 100
        assertFalse(throttle.shouldEmit())
        assertTrue(throttle.shouldEmit(force = true))
        now += 349
        assertFalse(throttle.shouldEmit())
        now += 1
        assertTrue(throttle.shouldEmit())
    }

    @Test
    fun liveTailKeepsOnlyRecentBoundedOutput() {
        val tail = LiveLogTail(maxLines = 3, maxChars = 20)
        listOf("one", "two", "three", "four").forEach(tail::append)

        assertEquals("two\nthree\nfour", tail.value())
    }
}

package com.uclone.restore.external

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalActionNotifierTest {
    @Test
    fun runningGate_skipsOnlyIdenticalPublishedContent() {
        val gate = RunningNotificationGate()
        val received = RunningNotificationKey("com.example.app", "switch", "任务已接收")

        assertTrue(gate.shouldPublish(received))
        gate.markPublished(received)
        assertFalse(gate.shouldPublish(received))
        assertTrue(gate.shouldPublish(received.copy(message = "正在复制数据")))
        assertTrue(gate.shouldPublish(received.copy(operation = "restore")))
    }

    @Test
    fun runningGate_resetAllowsCurrentContentAgain() {
        val gate = RunningNotificationGate()
        val key = RunningNotificationKey(null, null, "任务已接收")

        gate.markPublished(key)
        gate.reset()

        assertTrue(gate.shouldPublish(key))
    }
}

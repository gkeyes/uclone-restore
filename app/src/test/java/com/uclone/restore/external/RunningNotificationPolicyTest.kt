package com.uclone.restore.external

import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskStep
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunningNotificationPolicyTest {
    @Test
    fun duplicateNonTerminalMessageIsSuppressed() {
        var now = 1_000L
        val policy = RunningNotificationPolicy(intervalMs = 350) { now }
        val update = RunningNotificationUpdate("准备源数据", "SOURCE_PREPARE", isTerminal = false)
        policy.recordDisplayed(update)

        now += 5_000
        assertFalse(policy.shouldNotify(update))
    }

    @Test
    fun changedMessageWithinSameStageIsRateLimited() {
        var now = 1_000L
        val policy = RunningNotificationPolicy(intervalMs = 350) { now }
        policy.recordDisplayed(RunningNotificationUpdate("扫描 1", "RESTORE_DATA", isTerminal = false))

        now += 349
        assertFalse(policy.shouldNotify(RunningNotificationUpdate("扫描 2", "RESTORE_DATA", isTerminal = false)))
        now += 1
        assertTrue(policy.shouldNotify(RunningNotificationUpdate("扫描 2", "RESTORE_DATA", isTerminal = false)))
    }

    @Test
    fun stageTransitionIsImmediate() {
        var now = 1_000L
        val policy = RunningNotificationPolicy(intervalMs = 350) { now }
        policy.recordDisplayed(RunningNotificationUpdate("处理中", "SOURCE_PREPARE", isTerminal = false))

        now += 1

        assertTrue(policy.shouldNotify(RunningNotificationUpdate("处理中", "RESTORE_DATA", isTerminal = false)))
    }

    @Test
    fun terminalUpdateIsImmediate() {
        var now = 1_000L
        val policy = RunningNotificationPolicy(intervalMs = 350) { now }
        policy.recordDisplayed(RunningNotificationUpdate("写入目标数据", "RESTORE_DATA", isTerminal = false))

        now += 1

        assertTrue(policy.shouldNotify(RunningNotificationUpdate("写入目标数据", "RESTORE_DATA", isTerminal = true)))
    }

    @Test
    fun stepTransitionIsImmediateWhenDisplayTextIsUnchanged() {
        var now = 1_000L
        val policy = RunningNotificationPolicy(intervalMs = 350) { now }
        val first = TaskProgress(
            task = null,
            steps = listOf(
                TaskStep("处理中", StepStatus.RUNNING),
                TaskStep("处理中", StepStatus.PENDING),
            ),
        ).toRunningNotificationUpdate()
        policy.recordDisplayed(first)

        now += 1

        val second = TaskProgress(
            task = null,
            steps = listOf(
                TaskStep("处理中", StepStatus.SUCCESS),
                TaskStep("处理中", StepStatus.RUNNING),
            ),
        ).toRunningNotificationUpdate()

        assertTrue(policy.shouldNotify(second))
    }
}

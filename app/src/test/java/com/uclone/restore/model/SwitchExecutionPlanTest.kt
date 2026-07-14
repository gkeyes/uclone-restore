package com.uclone.restore.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwitchExecutionPlanTest {
    @Test
    fun resolvesAllReturnPolicyCombinations() {
        assertPlan(CloneReturnPlan.SYNC_SAFE, CloneSessionPolicy.SYNC_TO_CLONE_USER, SwitchSafetyMode.SAFE)
        assertPlan(CloneReturnPlan.SYNC_FAST, CloneSessionPolicy.SYNC_TO_CLONE_USER, SwitchSafetyMode.DANGEROUS_FAST)
        assertPlan(CloneReturnPlan.DISCARD_SAFE, CloneSessionPolicy.DISCARD_ON_MAIN_RETURN, SwitchSafetyMode.SAFE)
        assertPlan(CloneReturnPlan.DISCARD_FAST, CloneSessionPolicy.DISCARD_ON_MAIN_RETURN, SwitchSafetyMode.DANGEROUS_FAST)
    }

    @Test
    fun planMetadataMatchesTheCopyContract() {
        assertEquals(3, CloneReturnPlan.SYNC_SAFE.copyPasses)
        assertEquals(2, CloneReturnPlan.SYNC_FAST.copyPasses)
        assertEquals(2, CloneReturnPlan.DISCARD_SAFE.copyPasses)
        assertEquals(1, CloneReturnPlan.DISCARD_FAST.copyPasses)
        assertTrue(CloneReturnPlan.SYNC_SAFE.syncsCloneUser)
        assertFalse(CloneReturnPlan.DISCARD_SAFE.syncsCloneUser)
        assertTrue(CloneReturnPlan.DISCARD_SAFE.createsCheckpoint)
        assertFalse(CloneReturnPlan.DISCARD_FAST.createsCheckpoint)
    }

    private fun assertPlan(
        expected: CloneReturnPlan,
        clonePolicy: CloneSessionPolicy,
        safetyMode: SwitchSafetyMode,
    ) {
        val settings = UCloneSettings(cloneSessionPolicy = clonePolicy, switchSafetyMode = safetyMode)
        assertEquals(expected, settings.cloneReturnPlan())
    }
}

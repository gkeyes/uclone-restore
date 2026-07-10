package com.uclone.restore.external

import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalServiceLifecyclePolicyTest {
    @Test
    fun taskAFinalizationCannotRemoveOrStopTaskBAfterBClaimsForeground() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(1)
        policy.onAccepted(1)
        policy.onStart(2)
        policy.onAccepted(2)

        val staleFinalization = policy.onAcceptedFinished(1)
        val currentFinalization = policy.onAcceptedFinished(2)

        assertEquals(ExternalServiceFinalization.None, staleFinalization)
        assertEquals(ExternalServiceFinalization(removeForeground = true, stopStartId = 2), currentFinalization)
    }

    @Test
    fun taskAFinalizationDoesNotStopTaskBWhileBStartIsPending() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(1)
        policy.onAccepted(1)
        policy.onStart(2)

        val taskAFinalization = policy.onAcceptedFinished(1)
        policy.onAccepted(2)
        val taskBFinalization = policy.onAcceptedFinished(2)

        assertEquals(ExternalServiceFinalization(removeForeground = true), taskAFinalization)
        assertEquals(ExternalServiceFinalization(removeForeground = true, stopStartId = 2), taskBFinalization)
    }

    @Test
    fun rejectedStartWaitsForActiveOwnerThenLetsOwnerStopNewestStartId() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(1)
        policy.onAccepted(1)
        policy.onStart(2)

        val rejection = policy.onRejected(2)
        val taskFinalization = policy.onAcceptedFinished(1)

        assertEquals(ExternalServiceFinalization.None, rejection)
        assertEquals(ExternalServiceFinalization(removeForeground = true, stopStartId = 2), taskFinalization)
    }

    @Test
    fun rejectedPendingStartStopsOnlyAfterPreviousOwnerFinishes() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(1)
        policy.onAccepted(1)
        policy.onStart(2)

        val taskFinalization = policy.onAcceptedFinished(1)
        val rejection = policy.onRejected(2)

        assertEquals(ExternalServiceFinalization(removeForeground = true), taskFinalization)
        assertEquals(ExternalServiceFinalization(stopStartId = 2), rejection)
    }

    @Test
    fun standaloneRejectedStartStopsWithoutRemovingForeground() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(7)

        assertEquals(
            ExternalServiceFinalization(stopStartId = 7),
            policy.onRejected(7),
        )
    }
}

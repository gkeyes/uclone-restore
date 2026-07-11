package com.uclone.restore.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalServiceLifecyclePolicyTest {
    @Test
    fun firstStartClaimsBootstrapForegroundAndOverlappingStartReusesIt() {
        val policy = ExternalServiceLifecyclePolicy()

        assertTrue(policy.onStart(1))
        assertFalse(policy.onStart(2))
    }

    @Test
    fun failedBootstrapReleasesForegroundClaimForTheNextStart() {
        val policy = ExternalServiceLifecyclePolicy()
        assertTrue(policy.onStart(1))

        val finalization = policy.onBootstrapFailed(1)

        assertEquals(
            ExternalServiceFinalization(removeForeground = true, stopStartId = 1),
            finalization,
        )
        assertTrue(policy.onStart(2))
    }

    @Test
    fun failedBootstrapKeepsForegroundClaimForAnOverlappingPendingStart() {
        val policy = ExternalServiceLifecyclePolicy()
        assertTrue(policy.onStart(1))
        assertFalse(policy.onStart(2))

        val failedBootstrap = policy.onBootstrapFailed(1)
        policy.onAccepted(2)
        val pendingTaskFinished = policy.onAcceptedFinished(2)

        assertEquals(ExternalServiceFinalization.None, failedBootstrap)
        assertEquals(
            ExternalServiceFinalization(removeForeground = true, stopStartId = 2),
            pendingTaskFinished,
        )
    }

    @Test
    fun duplicateRejectionIsAnIdempotentNoOp() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(1)
        policy.onRejected(1)

        assertEquals(ExternalServiceFinalization.None, policy.onRejected(1))
    }

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
    fun taskAFinalizationRetainsForegroundWhileTaskBStartIsPending() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(1)
        policy.onAccepted(1)
        policy.onStart(2)

        val taskAFinalization = policy.onAcceptedFinished(1)
        policy.onAccepted(2)
        val taskBFinalization = policy.onAcceptedFinished(2)

        assertEquals(ExternalServiceFinalization.None, taskAFinalization)
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
    fun rejectedPendingStartKeepsForegroundUntilTheRejectionFinalizes() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(1)
        policy.onAccepted(1)
        policy.onStart(2)

        val taskFinalization = policy.onAcceptedFinished(1)
        val rejection = policy.onRejected(2)

        assertEquals(ExternalServiceFinalization.None, taskFinalization)
        assertEquals(ExternalServiceFinalization(removeForeground = true, stopStartId = 2), rejection)
    }

    @Test
    fun standaloneRejectedStartRemovesBootstrapForegroundBeforeStopping() {
        val policy = ExternalServiceLifecyclePolicy()
        policy.onStart(7)

        assertEquals(
            ExternalServiceFinalization(removeForeground = true, stopStartId = 7),
            policy.onRejected(7),
        )
    }
}

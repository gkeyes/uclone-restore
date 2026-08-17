package com.uclone.restore.crossuser

import com.uclone.restore.crossuser.session.CrossUserGateResult
import com.uclone.restore.crossuser.session.CrossUserResourceGate
import com.uclone.restore.crossuser.session.CrossUserResourceKey
import com.uclone.restore.crossuser.session.CrossUserResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CrossUserResourceGateTest {
    @Test
    fun packageRuntimeBlocksSamePackageDataMutation() {
        val gate = CrossUserResourceGate()
        val runtime = gate.tryAcquire(
            owner = "session",
            keys = setOf(
                CrossUserResourceKey(
                    kind = CrossUserResourceKind.PACKAGE_RUNTIME,
                    userId = 10,
                    packageName = "com.example.app",
                ),
            ),
        )

        val dataMutation = gate.tryAcquire(
            owner = "restore",
            keys = setOf(
                CrossUserResourceKey(
                    kind = CrossUserResourceKind.PACKAGE_DATA,
                    userId = 10,
                    packageName = "com.example.app",
                ),
            ),
        )

        assertIs<CrossUserGateResult.Acquired>(runtime)
        assertIs<CrossUserGateResult.Busy>(dataMutation)
        assertEquals("session", dataMutation.conflicts.single().owner)
    }

    @Test
    fun differentPackagesCanProceedUntilGlobalMutexIsUsed() {
        val gate = CrossUserResourceGate()
        assertIs<CrossUserGateResult.Acquired>(
            gate.tryAcquire(
                owner = "runtime-a",
                keys = setOf(
                    CrossUserResourceKey(
                        CrossUserResourceKind.PACKAGE_RUNTIME,
                        userId = 10,
                        packageName = "com.example.a",
                    ),
                ),
            ),
        )
        assertIs<CrossUserGateResult.Acquired>(
            gate.tryAcquire(
                owner = "runtime-b",
                keys = setOf(
                    CrossUserResourceKey(
                        CrossUserResourceKind.PACKAGE_RUNTIME,
                        userId = 10,
                        packageName = "com.example.b",
                    ),
                ),
            ),
        )

        val globalData = gate.tryAcquire(
            owner = "transaction",
            keys = setOf(CrossUserResourceKey(CrossUserResourceKind.GLOBAL_DATA_TRANSACTION)),
        )

        assertIs<CrossUserGateResult.Acquired>(globalData)
    }

    @Test
    fun globalCrossUserSessionBlocksGlobalDataTransaction() {
        val gate = CrossUserResourceGate()
        val session = gate.tryAcquire(
            owner = "session",
            keys = setOf(CrossUserResourceKey(CrossUserResourceKind.GLOBAL_CROSS_USER_SESSION)),
        )
        val data = gate.tryAcquire(
            owner = "restore",
            keys = setOf(CrossUserResourceKey(CrossUserResourceKind.GLOBAL_DATA_TRANSACTION)),
        )

        assertIs<CrossUserGateResult.Acquired>(session)
        assertIs<CrossUserGateResult.Busy>(data)
    }

    @Test
    fun releaseAllowsNextOwner() {
        val gate = CrossUserResourceGate()
        val first = gate.tryAcquire(
            owner = "first",
            keys = setOf(CrossUserResourceKey(CrossUserResourceKind.DISPLAY, displayId = 9)),
        )
        assertIs<CrossUserGateResult.Acquired>(first)

        assertTrue(gate.release(first.lease.leaseId))

        assertIs<CrossUserGateResult.Acquired>(
            gate.tryAcquire(
                owner = "second",
                keys = setOf(CrossUserResourceKey(CrossUserResourceKind.DISPLAY, displayId = 9)),
            ),
        )
    }
}

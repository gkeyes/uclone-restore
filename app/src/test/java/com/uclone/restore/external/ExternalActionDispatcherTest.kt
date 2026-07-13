package com.uclone.restore.external

import com.uclone.restore.sync.AppDataState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExternalActionDispatcherTest {
    @Test
    fun switchOrRestoreDecision_switchesOnlyFromKnownMainState() {
        assertEquals(SwitchOrRestoreDecision.SwitchToClone, switchOrRestoreDecision(AppDataState.Main))
    }

    @Test
    fun switchOrRestoreDecision_restoresTheKnownMainReturnPointFromCloneState() {
        assertEquals(
            SwitchOrRestoreDecision.RestoreMain("rollback-1"),
            switchOrRestoreDecision(AppDataState.Clone("rollback-1")),
        )
    }

    @Test
    fun switchOrRestoreDecision_rejectsUnknownState() {
        val error = assertFailsWith<IllegalStateException> {
            switchOrRestoreDecision(AppDataState.Unknown)
        }

        assertTrue(error.message.orEmpty().contains("状态未知"))
    }
}

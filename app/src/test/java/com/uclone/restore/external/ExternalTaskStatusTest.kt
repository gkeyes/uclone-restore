package com.uclone.restore.external

import com.uclone.restore.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalTaskStatusTest {
    @Test
    fun everyTaskStatusHasAnExplicitProtocolStatusAndDiagnosticStage() {
        val expected = mapOf(
            TaskStatus.ACCEPTED to (ExternalActionContract.STATUS_ACCEPTED to ExternalRequestStage.ACCEPTED),
            TaskStatus.RUNNING to (ExternalActionContract.STATUS_RUNNING to ExternalRequestStage.RUNNING),
            TaskStatus.AUTO_ROLLING_BACK to (ExternalActionContract.STATUS_RUNNING to ExternalRequestStage.RUNNING),
            TaskStatus.ROLLED_BACK to (ExternalActionContract.STATUS_FAILED to ExternalRequestStage.FAILED),
            TaskStatus.SUCCESS to (ExternalActionContract.STATUS_SUCCESS to ExternalRequestStage.SUCCESS),
            TaskStatus.SUCCESS_WITH_WARNINGS to (
                ExternalActionContract.STATUS_SUCCESS_WITH_WARNINGS to ExternalRequestStage.SUCCESS_WITH_WARNINGS
            ),
            TaskStatus.FAILED to (ExternalActionContract.STATUS_FAILED to ExternalRequestStage.FAILED),
            TaskStatus.FAILED_FATAL to (ExternalActionContract.STATUS_FAILED to ExternalRequestStage.FAILED),
            TaskStatus.RECOVERY_REQUIRED to (
                ExternalActionContract.STATUS_RECOVERY_REQUIRED to ExternalRequestStage.RECOVERY_REQUIRED
            ),
            TaskStatus.INTERRUPTED to (ExternalActionContract.STATUS_INTERRUPTED to ExternalRequestStage.INTERRUPTED),
        )

        assertEquals(TaskStatus.entries.toSet(), expected.keys)
        expected.forEach { (taskStatus, mapping) ->
            assertEquals(mapping.first, taskStatus.toExternalStatus(), taskStatus.name)
            assertEquals(mapping.second, taskStatus.toExternalRequestStage(), taskStatus.name)
        }
    }

    @Test
    fun everyDeclaredWireStatusHasAPersistedDiagnosticStage() {
        val expected = mapOf(
            ExternalActionContract.STATUS_SERVICE_RECEIVED to ExternalRequestStage.SERVICE_RECEIVED,
            ExternalActionContract.STATUS_ACCEPTED to ExternalRequestStage.ACCEPTED,
            ExternalActionContract.STATUS_RUNNING to ExternalRequestStage.RUNNING,
            ExternalActionContract.STATUS_SUCCESS to ExternalRequestStage.SUCCESS,
            ExternalActionContract.STATUS_SUCCESS_WITH_WARNINGS to ExternalRequestStage.SUCCESS_WITH_WARNINGS,
            ExternalActionContract.STATUS_FAILED to ExternalRequestStage.FAILED,
            ExternalActionContract.STATUS_INTERRUPTED to ExternalRequestStage.INTERRUPTED,
            ExternalActionContract.STATUS_STILL_RUNNING to ExternalRequestStage.STILL_RUNNING,
            ExternalActionContract.STATUS_ORPHANED to ExternalRequestStage.ORPHANED,
            ExternalActionContract.STATUS_FAILED_PROCESS_DIED to ExternalRequestStage.FAILED_PROCESS_DIED,
            ExternalActionContract.STATUS_REJECTED to ExternalRequestStage.REJECTED,
            ExternalActionContract.STATUS_BUSY to ExternalRequestStage.BUSY,
            ExternalActionContract.STATUS_ALREADY_RUNNING to ExternalRequestStage.RUNNING,
            ExternalActionContract.STATUS_RECOVERY_REQUIRED to ExternalRequestStage.RECOVERY_REQUIRED,
        )

        expected.forEach { (status, stage) ->
            assertEquals(stage, status.toExternalRequestStage(), status)
        }
    }

    @Test
    fun onlyFinalProtocolStagesAreDurableReplayBarriers() {
        val terminalStages = setOf(
            ExternalRequestStage.SUCCESS,
            ExternalRequestStage.SUCCESS_WITH_WARNINGS,
            ExternalRequestStage.BUSY,
            ExternalRequestStage.REJECTED,
            ExternalRequestStage.FAILED,
            ExternalRequestStage.INTERRUPTED,
            ExternalRequestStage.FAILED_PROCESS_DIED,
            ExternalRequestStage.RECOVERY_REQUIRED,
        )

        ExternalRequestStage.entries.forEach { stage ->
            assertEquals(stage in terminalStages, stage.isTerminalProtocolStage, stage.name)
        }
    }
}

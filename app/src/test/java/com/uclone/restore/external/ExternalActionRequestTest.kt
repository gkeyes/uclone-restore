package com.uclone.restore.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.uclone.restore.model.TaskType

class ExternalActionRequestTest {
    @Test
    fun parseRejectsBlankRequestId() {
        val request = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            packageName = "com.example.app",
            requestId = " ",
            source = ExternalActionContract.SOURCE_MODULE,
        )

        assertNull(request)
    }

    @Test
    fun parseRejectsRequestIdsThatCanEscapeTemporaryPaths() {
        listOf("../request", "request/child", "request id", "x".repeat(129)).forEach { requestId ->
            assertNull(
                ExternalActionRequest.parse(
                    protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
                    operation = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
                    packageName = "com.example.app",
                    requestId = requestId,
                    source = ExternalActionContract.SOURCE_MODULE,
                ),
                requestId,
            )
        }
    }

    @Test
    fun parseAcceptsKnownOperationWithRequestId() {
        val request = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            packageName = "com.example.app",
            requestId = "request-42",
            source = ExternalActionContract.SOURCE_MODULE,
        )

        assertEquals("request-42", request?.requestId)
    }

    @Test
    fun parseRejectsPackageNamesThatCanEscapeRootPaths() {
        val invalidPackageNames = listOf(
            "../com.example.app",
            "com/example/app",
            "com.example app",
            "com.example.app\nnext",
            ".",
            "..",
        )

        invalidPackageNames.forEach { packageName ->
            assertNull(
                ExternalActionRequest.parse(
                    protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
                    operation = ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
                    packageName = packageName,
                    requestId = "request-42",
                    source = ExternalActionContract.SOURCE_MODULE,
                ),
                packageName,
            )
        }
    }

    @Test
    fun rollbackOperationRequiresRollbackId() {
        val missing = ExternalActionRequest.parse(
            ExternalActionContract.PROTOCOL_VERSION,
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            "com.example.app",
            "request-42",
            ExternalActionContract.SOURCE_APP,
        )
        val valid = ExternalActionRequest.parse(
            ExternalActionContract.PROTOCOL_VERSION,
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            "com.example.app",
            "request-43",
            ExternalActionContract.SOURCE_APP,
            "rollback-1",
        )

        assertNull(missing)
        assertEquals("rollback-1", valid?.rollbackId)
    }

    @Test
    fun rollbackOperationRejectsIdsThatCanEscapeBackupDirectory() {
        listOf("../rollback-1", "folder/rollback-1", ".", "..", "rollback id").forEach { rollbackId ->
            assertNull(
                ExternalActionRequest.parse(
                    protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
                    operation = ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
                    packageName = "com.example.app",
                    requestId = "request-rollback",
                    source = ExternalActionContract.SOURCE_APP,
                    rollbackId = rollbackId,
                ),
                rollbackId,
            )
        }
    }

    @Test
    fun internalOperationsMapToPersistentTaskTypes() {
        assertEquals(TaskType.RESTORE_FROM_CLONE_LATEST, taskTypeForOperation(ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST))
        assertEquals(TaskType.RESET_SWITCH_STATE, taskTypeForOperation(ExternalActionContract.OPERATION_RESET_SWITCH_STATE))
        assertEquals(TaskType.RESET_WORKSPACE, taskTypeForOperation(ExternalActionContract.OPERATION_RESET_WORKSPACE))
        assertEquals(TaskType.STOP_CLONE_USER, taskTypeForOperation(ExternalActionContract.OPERATION_STOP_CLONE_USER))
        assertEquals(
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER,
            taskTypeForOperation(ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER),
        )
    }

    @Test
    fun installOperationsRequireTargetUser() {
        val missingTarget = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            packageName = "com.example.app",
            requestId = "install-1",
            source = ExternalActionContract.SOURCE_APP,
        )
        val valid = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            packageName = "com.example.app",
            requestId = "install-2",
            source = ExternalActionContract.SOURCE_APP,
            targetUserId = 10,
        )

        assertNull(missingTarget)
        assertEquals(10, valid?.targetUserId)
    }
}

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
    fun internalOperationsMapToPersistentTaskTypes() {
        assertEquals(TaskType.RESTORE_FROM_CLONE_LATEST, taskTypeForOperation(ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST))
        assertEquals(TaskType.RESET_WORKSPACE, taskTypeForOperation(ExternalActionContract.OPERATION_RESET_WORKSPACE))
        assertEquals(TaskType.INSTALL_TO_OTHER_USER, taskTypeForOperation(ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER))
        assertEquals(
            TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            taskTypeForOperation(ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER),
        )
        assertEquals(
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER,
            taskTypeForOperation(ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER),
        )
        assertEquals(TaskType.STOP_CLONE_USER, taskTypeForOperation(ExternalActionContract.OPERATION_STOP_CLONE_USER))
    }

    @Test
    fun crossUserInstallRequiresAnExplicitNonNegativeTargetUser() {
        val missing = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            packageName = "com.example.app",
            requestId = "request-install-missing",
            source = ExternalActionContract.SOURCE_APP,
        )
        val negative = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            packageName = "com.example.app",
            requestId = "request-install-negative",
            source = ExternalActionContract.SOURCE_APP,
            targetUserId = -1,
        )
        val valid = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            packageName = "com.example.app",
            requestId = "request-install-valid",
            source = ExternalActionContract.SOURCE_APP,
            targetUserId = 10,
        )

        assertNull(missing)
        assertNull(negative)
        assertEquals(10, valid?.targetUserId)
    }
}

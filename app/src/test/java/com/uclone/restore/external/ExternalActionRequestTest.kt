package com.uclone.restore.external

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.uclone.restore.model.BackupKind
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
        assertEquals(TaskType.UPDATE_MAIN_RETURN_POINT, taskTypeForOperation(ExternalActionContract.OPERATION_UPDATE_MAIN_RETURN_POINT))
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
        assertEquals(TaskType.BACKUP_USER_STATE, taskTypeForOperation(ExternalActionContract.OPERATION_BACKUP_USER_STATE))
        assertEquals(TaskType.RESTORE_SELECTED_BACKUP, taskTypeForOperation(ExternalActionContract.OPERATION_RESTORE_SELECTED_BACKUP))
        assertEquals(TaskType.UPDATE_BACKUP_METADATA, taskTypeForOperation(ExternalActionContract.OPERATION_UPDATE_BACKUP_METADATA))
        assertEquals(TaskType.DELETE_BACKUP, taskTypeForOperation(ExternalActionContract.OPERATION_DELETE_BACKUP))
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

    @Test
    fun namedBackupRequiresCaptureSourceAndSafeName() {
        val missingSource = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_BACKUP_USER_STATE,
            packageName = "com.example.app",
            requestId = "request-named-missing-source",
            source = ExternalActionContract.SOURCE_APP,
            captureUserId = 0,
            backupName = "微信 主系统",
        )
        val invalidName = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_BACKUP_USER_STATE,
            packageName = "com.example.app",
            requestId = "request-named-invalid-name",
            source = ExternalActionContract.SOURCE_APP,
            captureUserId = 0,
            sourceUserId = 0,
            backupName = "bad\nname",
        )
        val valid = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_BACKUP_USER_STATE,
            packageName = "com.example.app",
            requestId = "request-named-valid",
            source = ExternalActionContract.SOURCE_APP,
            captureUserId = 0,
            sourceUserId = 10,
            backupName = "微信 主系统",
        )

        assertNull(missingSource)
        assertNull(invalidName)
        assertEquals(0, valid?.captureUserId)
        assertEquals(10, valid?.sourceUserId)
        assertEquals("微信 主系统", valid?.backupName)
    }

    @Test
    fun selectedBackupOperationsRequireSafeBackupRef() {
        val invalidKind = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_RESTORE_SELECTED_BACKUP,
            packageName = "com.example.app",
            requestId = "request-restore-invalid-kind",
            source = ExternalActionContract.SOURCE_APP,
            targetUserId = 0,
            backupKind = "unknown",
            backupId = "manual_1",
        )
        val pathTraversal = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_DELETE_BACKUP,
            packageName = "com.example.app",
            requestId = "request-delete-traversal",
            source = ExternalActionContract.SOURCE_APP,
            backupKind = BackupKind.MANUAL.wireName,
            backupId = "../manual_1",
        )
        val validRestore = ExternalActionRequest.parse(
            protocolVersion = ExternalActionContract.PROTOCOL_VERSION,
            operation = ExternalActionContract.OPERATION_RESTORE_SELECTED_BACKUP,
            packageName = "com.example.app",
            requestId = "request-restore-valid",
            source = ExternalActionContract.SOURCE_APP,
            targetUserId = 10,
            backupKind = BackupKind.MANUAL.wireName,
            backupId = "manual_1",
        )

        assertNull(invalidKind)
        assertNull(pathTraversal)
        assertEquals(BackupKind.MANUAL, validRestore?.backupKind)
        assertEquals("manual_1", validRestore?.backupId)
        assertEquals(10, validRestore?.targetUserId)
    }
}

package com.uclone.restore.external

import android.content.Intent
import com.uclone.restore.model.BackupKind

data class ExternalActionRequest(
    val operation: String,
    val packageName: String,
    val requestId: String,
    val source: String,
    val rollbackId: String?,
    val expectedWorkspaceRoot: String? = null,
    val targetUserId: Int? = null,
    val sourceUserId: Int? = null,
    val captureUserId: Int? = null,
    val backupKind: BackupKind? = null,
    val backupId: String? = null,
    val backupName: String? = null,
    val internalToken: String?,
) {
    companion object {
        fun from(intent: Intent): ExternalActionRequest? {
            return parse(
                protocolVersion = intent.getIntExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, 0),
                operation = intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION),
                packageName = intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME),
                requestId = intent.getStringExtra(ExternalActionContract.EXTRA_REQUEST_ID),
                source = intent.getStringExtra(ExternalActionContract.EXTRA_SOURCE),
                rollbackId = intent.getStringExtra(ExternalActionContract.EXTRA_ROLLBACK_ID),
                expectedWorkspaceRoot = intent.getStringExtra(ExternalActionContract.EXTRA_EXPECTED_WORKSPACE_ROOT),
                targetUserId = if (intent.hasExtra(ExternalActionContract.EXTRA_TARGET_USER_ID)) {
                    intent.getIntExtra(ExternalActionContract.EXTRA_TARGET_USER_ID, -1)
                } else {
                    null
                },
                sourceUserId = if (intent.hasExtra(ExternalActionContract.EXTRA_SOURCE_USER_ID)) {
                    intent.getIntExtra(ExternalActionContract.EXTRA_SOURCE_USER_ID, -1)
                } else {
                    null
                },
                captureUserId = if (intent.hasExtra(ExternalActionContract.EXTRA_CAPTURE_USER_ID)) {
                    intent.getIntExtra(ExternalActionContract.EXTRA_CAPTURE_USER_ID, -1)
                } else {
                    null
                },
                backupKind = intent.getStringExtra(ExternalActionContract.EXTRA_BACKUP_KIND),
                backupId = intent.getStringExtra(ExternalActionContract.EXTRA_BACKUP_ID),
                backupName = intent.getStringExtra(ExternalActionContract.EXTRA_BACKUP_NAME),
                internalToken = intent.getStringExtra(ExternalActionContract.EXTRA_INTERNAL_TOKEN),
            )
        }

        internal fun parse(
            protocolVersion: Int,
            operation: String?,
            packageName: String?,
            requestId: String?,
            source: String?,
            rollbackId: String? = null,
            expectedWorkspaceRoot: String? = null,
            targetUserId: Int? = null,
            sourceUserId: Int? = null,
            captureUserId: Int? = null,
            backupKind: String? = null,
            backupId: String? = null,
            backupName: String? = null,
            internalToken: String? = null,
        ): ExternalActionRequest? {
            if (protocolVersion != ExternalActionContract.PROTOCOL_VERSION) return null
            val normalizedOperation = operation?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (normalizedOperation !in allowedOperations) return null
            val normalizedPackage = packageName?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (!safePackageToken.matches(normalizedPackage)) return null
            if (normalizedOperation in operationsRequiringAppPackage && !androidPackageName.matches(normalizedPackage)) return null
            val normalizedRequestId = requestId?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val normalizedSource = source?.trim()?.takeIf(String::isNotEmpty)
                ?: ExternalActionContract.SOURCE_MODULE
            val normalizedRollbackId = rollbackId?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedOperation in operationsRequiringRollbackId && normalizedRollbackId == null) return null
            val normalizedWorkspaceRoot = expectedWorkspaceRoot?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedOperation == ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP && normalizedWorkspaceRoot == null) return null
            val normalizedTargetUserId = targetUserId?.takeIf { it >= 0 }
            if (normalizedOperation in operationsRequiringTargetUser && normalizedTargetUserId == null) return null
            val normalizedSourceUserId = sourceUserId?.takeIf { it >= 0 }
            if (normalizedOperation in operationsRequiringSourceUser && normalizedSourceUserId == null) return null
            val normalizedCaptureUserId = captureUserId?.takeIf { it >= 0 }
            if (normalizedOperation in operationsRequiringCaptureUser && normalizedCaptureUserId == null) return null
            val normalizedBackupKind = backupKind?.trim()?.let(BackupKind::fromWireName)
            if (normalizedOperation in operationsRequiringBackupRef && normalizedBackupKind == null) return null
            val normalizedBackupId = backupId?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedOperation in operationsRequiringBackupRef && normalizedBackupId?.let(safeBackupId::matches) != true) return null
            val normalizedBackupName = backupName
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.takeIf(::isSafeDisplayText)
                ?.take(80)
            if (normalizedOperation in operationsRequiringBackupName && normalizedBackupName == null) return null
            return ExternalActionRequest(
                operation = normalizedOperation,
                packageName = normalizedPackage,
                requestId = normalizedRequestId,
                source = normalizedSource,
                rollbackId = normalizedRollbackId,
                expectedWorkspaceRoot = normalizedWorkspaceRoot,
                targetUserId = normalizedTargetUserId,
                sourceUserId = normalizedSourceUserId,
                captureUserId = normalizedCaptureUserId,
                backupKind = normalizedBackupKind,
                backupId = normalizedBackupId,
                backupName = normalizedBackupName,
                internalToken = internalToken?.takeIf(String::isNotBlank),
            )
        }

        private val allowedOperations = setOf(
            ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_MAIN,
            ExternalActionContract.OPERATION_UPDATE_MAIN_RETURN_POINT,
            ExternalActionContract.OPERATION_BACKUP_USER_STATE,
            ExternalActionContract.OPERATION_RESTORE_SELECTED_BACKUP,
            ExternalActionContract.OPERATION_UPDATE_BACKUP_METADATA,
            ExternalActionContract.OPERATION_DELETE_BACKUP,
            ExternalActionContract.OPERATION_BACKUP_DEFAULT,
            ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP,
            ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK,
            ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST,
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            ExternalActionContract.OPERATION_DELETE_SNAPSHOT,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_AUDIT_RESTORE,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_SCAN_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
        )
        private val operationsRequiringRollbackId = setOf(
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
        )
        private val operationsRequiringTargetUser = setOf(
            ExternalActionContract.OPERATION_RESTORE_SELECTED_BACKUP,
            ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER,
        )
        private val operationsRequiringSourceUser = setOf(
            ExternalActionContract.OPERATION_BACKUP_USER_STATE,
            ExternalActionContract.OPERATION_UPDATE_BACKUP_METADATA,
        )
        private val operationsRequiringCaptureUser = setOf(
            ExternalActionContract.OPERATION_BACKUP_USER_STATE,
        )
        private val operationsRequiringBackupRef = setOf(
            ExternalActionContract.OPERATION_RESTORE_SELECTED_BACKUP,
            ExternalActionContract.OPERATION_UPDATE_BACKUP_METADATA,
            ExternalActionContract.OPERATION_DELETE_BACKUP,
        )
        private val operationsRequiringBackupName = setOf(
            ExternalActionContract.OPERATION_BACKUP_USER_STATE,
            ExternalActionContract.OPERATION_UPDATE_BACKUP_METADATA,
        )
        private val operationsRequiringAppPackage = allowedOperations - setOf(
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_SCAN_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
        )
        private val safePackageToken = Regex("[A-Za-z0-9_.]+")
        private val androidPackageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val safeBackupId = Regex("[A-Za-z0-9_.-]+")

        private fun isSafeDisplayText(value: String): Boolean =
            value.length <= 80 && value.none { it == '\u0000' || it == '\n' || it == '\r' || it == '\t' }
    }
}

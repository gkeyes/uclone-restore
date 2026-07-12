package com.uclone.restore.external

import android.content.Intent

data class ExternalActionRequest(
    val operation: String,
    val packageName: String,
    val requestId: String,
    val source: String,
    val rollbackId: String?,
    val internalToken: String?,
    val targetUserId: Int? = null,
    val expectedWorkspaceRoot: String? = null,
    val allowVersionMismatch: Boolean = false,
    val allowLegacyIdentity: Boolean = false,
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
                internalToken = intent.getStringExtra(ExternalActionContract.EXTRA_INTERNAL_TOKEN),
                targetUserId = intent.getIntExtra(ExternalActionContract.EXTRA_TARGET_USER_ID, Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
                expectedWorkspaceRoot = intent.getStringExtra(ExternalActionContract.EXTRA_EXPECTED_WORKSPACE_ROOT),
                allowVersionMismatch = intent.getBooleanExtra(ExternalActionContract.EXTRA_ALLOW_VERSION_MISMATCH, false),
                allowLegacyIdentity = intent.getBooleanExtra(ExternalActionContract.EXTRA_ALLOW_LEGACY_IDENTITY, false),
            )
        }

        internal fun parse(
            protocolVersion: Int,
            operation: String?,
            packageName: String?,
            requestId: String?,
            source: String?,
            rollbackId: String? = null,
            internalToken: String? = null,
            targetUserId: Int? = null,
            expectedWorkspaceRoot: String? = null,
            allowVersionMismatch: Boolean = false,
            allowLegacyIdentity: Boolean = false,
        ): ExternalActionRequest? {
            if (protocolVersion != ExternalActionContract.PROTOCOL_VERSION) return null
            val normalizedOperation = operation?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (normalizedOperation !in allowedOperations) return null
            val normalizedPackage = packageName?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (!safePackageToken.matches(normalizedPackage)) return null
            if (normalizedOperation in operationsRequiringAppPackage && !androidPackageName.matches(normalizedPackage)) return null
            val normalizedRequestId = requestId?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (!safeRequestId.matches(normalizedRequestId)) return null
            val normalizedSource = source?.trim()?.takeIf(String::isNotEmpty)
                ?: ExternalActionContract.SOURCE_MODULE
            val normalizedRollbackId = rollbackId?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedRollbackId != null && !isSafeStorageId(normalizedRollbackId)) return null
            if (normalizedOperation in operationsRequiringRollbackId && normalizedRollbackId == null) return null
            if (normalizedOperation in operationsRequiringTargetUser && targetUserId == null) return null
            val normalizedExpectedRoot = expectedWorkspaceRoot?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedExpectedRoot != null && !isSafeWorkspaceRoot(normalizedExpectedRoot)) return null
            return ExternalActionRequest(
                operation = normalizedOperation,
                packageName = normalizedPackage,
                requestId = normalizedRequestId,
                source = normalizedSource,
                rollbackId = normalizedRollbackId,
                internalToken = internalToken?.takeIf(String::isNotBlank),
                targetUserId = targetUserId,
                expectedWorkspaceRoot = normalizedExpectedRoot,
                allowVersionMismatch = allowVersionMismatch,
                allowLegacyIdentity = allowLegacyIdentity,
            )
        }

        private val allowedOperations = setOf(
            ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_MAIN,
            ExternalActionContract.OPERATION_BACKUP_DEFAULT,
            ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP,
            ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK,
            ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST,
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            ExternalActionContract.OPERATION_RESET_SWITCH_STATE,
            ExternalActionContract.OPERATION_DELETE_SNAPSHOT,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
            ExternalActionContract.OPERATION_DELETE_CLONE_ROLLBACK,
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_AUDIT_RESTORE,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
            ExternalActionContract.OPERATION_SCAN_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER,
            ExternalActionContract.OPERATION_RECOVER_INTERRUPTED_TRANSACTION,
        )
        private val operationsRequiringRollbackId = setOf(
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
            ExternalActionContract.OPERATION_DELETE_CLONE_ROLLBACK,
            ExternalActionContract.OPERATION_RECOVER_INTERRUPTED_TRANSACTION,
        )
        private val operationsRequiringTargetUser = setOf(
            ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER,
        )
        private val operationsRequiringAppPackage = allowedOperations - setOf(
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
            ExternalActionContract.OPERATION_SCAN_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_RECOVER_INTERRUPTED_TRANSACTION,
        )
        private val safePackageToken = Regex("[A-Za-z0-9_.]+")
        private val safeRequestId = Regex("[A-Za-z0-9_.-]{1,128}")
        private val safeStorageId = Regex("[A-Za-z0-9_.-]{1,160}")
        private val androidPackageName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")

        private fun isSafeStorageId(value: String): Boolean =
            value != "." && value != ".." && safeStorageId.matches(value)

        private fun isSafeWorkspaceRoot(value: String): Boolean =
            value.startsWith('/') &&
                value !in setOf("/", "/data", "/data/adb") &&
                value.none(Char::isISOControl) &&
                value.split('/').none { it == "." || it == ".." }
    }
}

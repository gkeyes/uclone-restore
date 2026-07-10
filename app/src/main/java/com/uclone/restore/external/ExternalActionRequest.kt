package com.uclone.restore.external

import android.content.Intent

data class ExternalActionRequest(
    val operation: String,
    val packageName: String,
    val requestId: String,
    val source: String,
    val rollbackId: String?,
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
            internalToken: String? = null,
        ): ExternalActionRequest? {
            if (protocolVersion != ExternalActionContract.PROTOCOL_VERSION) return null
            val normalizedOperation = operation?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (normalizedOperation !in allowedOperations) return null
            val normalizedPackage = packageName?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val normalizedRequestId = requestId?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val normalizedSource = source?.trim()?.takeIf(String::isNotEmpty)
                ?: ExternalActionContract.SOURCE_MODULE
            val normalizedRollbackId = rollbackId?.trim()?.takeIf(String::isNotEmpty)
            if (normalizedOperation in operationsRequiringRollbackId && normalizedRollbackId == null) return null
            return ExternalActionRequest(
                operation = normalizedOperation,
                packageName = normalizedPackage,
                requestId = normalizedRequestId,
                source = normalizedSource,
                rollbackId = normalizedRollbackId,
                internalToken = internalToken?.takeIf(String::isNotBlank),
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
            ExternalActionContract.OPERATION_DELETE_SNAPSHOT,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_AUDIT_RESTORE,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
        )
        private val operationsRequiringRollbackId = setOf(
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
        )
    }
}

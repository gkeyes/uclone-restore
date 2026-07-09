package com.uclone.restore.external

import android.content.Intent

data class ExternalActionRequest(
    val operation: String,
    val packageName: String,
    val requestId: String,
    val source: String,
) {
    companion object {
        fun from(intent: Intent): ExternalActionRequest? {
            val version = intent.getIntExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, 0)
            if (version != ExternalActionContract.PROTOCOL_VERSION) return null
            val operation = intent.getStringExtra(ExternalActionContract.EXTRA_OPERATION)
                ?.takeIf(String::isNotBlank)
                ?: return null
            if (operation !in allowedOperations) return null
            val packageName = intent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME)
                ?.takeIf(String::isNotBlank)
                ?: return null
            val requestId = intent.getStringExtra(ExternalActionContract.EXTRA_REQUEST_ID).orEmpty()
            val source = intent.getStringExtra(ExternalActionContract.EXTRA_SOURCE)
                ?.takeIf(String::isNotBlank)
                ?: ExternalActionContract.SOURCE_MODULE
            return ExternalActionRequest(operation, packageName, requestId, source)
        }

        private val allowedOperations = setOf(
            ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_MAIN,
            ExternalActionContract.OPERATION_BACKUP_DEFAULT,
            ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP,
            ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK,
        )
    }
}

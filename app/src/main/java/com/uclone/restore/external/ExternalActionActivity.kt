package com.uclone.restore.external

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log

class ExternalActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            ExternalActionService.start(this, intent)
        }.onFailure { error ->
            reportLaunchFailure(intent, error)
        }
        finish()
    }

    private fun reportLaunchFailure(sourceIntent: Intent, error: Throwable) {
        val operation = sourceIntent.getStringExtra(ExternalActionContract.EXTRA_OPERATION).orEmpty()
        val packageName = sourceIntent.getStringExtra(ExternalActionContract.EXTRA_PACKAGE_NAME).orEmpty()
        val message = error.message ?: "无法启动外部任务服务"
        Log.e(TAG, "external action launch failed operation=$operation package=$packageName", error)
        runCatching {
            ExternalActionNotifier(this).notifyResult(
                packageName,
                operation,
                ExternalActionContract.STATUS_FAILED,
                message,
            )
        }

        val statusIntent = Intent(ExternalActionContract.ACTION_STATUS)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
            .putExtra(ExternalActionContract.EXTRA_OPERATION, operation)
            .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, packageName)
            .putExtra(
                ExternalActionContract.EXTRA_REQUEST_ID,
                sourceIntent.getStringExtra(ExternalActionContract.EXTRA_REQUEST_ID).orEmpty(),
            )
            .putExtra(
                ExternalActionContract.EXTRA_SOURCE,
                sourceIntent.getStringExtra(ExternalActionContract.EXTRA_SOURCE).orEmpty(),
            )
            .putExtra(ExternalActionContract.EXTRA_STATUS, ExternalActionContract.STATUS_FAILED)
            .putExtra(ExternalActionContract.EXTRA_MESSAGE, message)
        val source = sourceIntent.getStringExtra(ExternalActionContract.EXTRA_SOURCE).orEmpty()
        if (source == ExternalActionContract.SOURCE_MODULE || source == ExternalActionContract.SOURCE_LAUNCHER_MODULE) {
            statusIntent.component = ComponentName(
                ExternalActionContract.LAUNCHER_MODULE_PACKAGE,
                ExternalActionContract.LAUNCHER_MODULE_STATUS_RECEIVER,
            )
        }
        sendBroadcast(statusIntent, ExternalActionContract.PERMISSION_CONTROL)
    }

    private companion object {
        const val TAG = "UCloneExternalAction"
    }
}

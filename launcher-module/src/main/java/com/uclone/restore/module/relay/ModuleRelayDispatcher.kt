package com.uclone.restore.module.relay

import android.content.Context
import android.content.Intent
import android.os.Build

object ModuleRelayDispatcher {
    fun dispatch(context: Context, intent: Intent?, origin: String) {
        if (intent == null) {
            ModuleSettingsStore.recordRelayEvent(context, "$origin reject missing intent")
            return
        }
        val packageName = intent.getStringExtra(ModuleRelayContract.EXTRA_PACKAGE_NAME).orEmpty()
        val requestId = intent.getStringExtra(ModuleRelayContract.EXTRA_REQUEST_ID).orEmpty()
        if (packageName.isBlank()) {
            ModuleSettingsStore.recordRelayEvent(context, "$origin reject missing packageName request=$requestId")
            return
        }
        ModuleSettingsStore.recordRelayEvent(context, "$origin received package=$packageName request=$requestId")
        runCatching {
            val ucloneIntent = ucloneIntent(packageName, requestId)
            context.startActivity(ucloneIntent)
            ModuleSettingsStore.recordRelayEvent(context, "$origin launched activity package=$packageName request=$requestId")
        }.onFailure { activityError ->
            ModuleSettingsStore.recordRelayEvent(
                context,
                "$origin activity failed package=$packageName request=$requestId type=${activityError.javaClass.simpleName} error=${activityError.message}",
            )
            runCatching {
                val ucloneIntent = ucloneIntent(packageName, requestId)
                    .setClassName(ModuleConstants.UCLONE_PACKAGE, ModuleConstants.UCLONE_SERVICE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(ucloneIntent)
                } else {
                    context.startService(ucloneIntent)
                }
                ModuleSettingsStore.recordRelayEvent(context, "$origin fallback service package=$packageName request=$requestId")
            }.onFailure { serviceError ->
                ModuleSettingsStore.recordRelayEvent(
                    context,
                    "$origin status=FAILED operation=${ModuleRelayContract.OPERATION_SWITCH_OR_RESTORE} package=$packageName request=$requestId type=${serviceError.javaClass.simpleName} error=${serviceError.message}",
                )
            }
        }
    }

    private fun ucloneIntent(packageName: String, requestId: String): Intent =
        Intent(ModuleRelayContract.UCLONE_ACTION_EXECUTE)
            .setClassName(ModuleConstants.UCLONE_PACKAGE, ModuleConstants.UCLONE_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ModuleRelayContract.UCLONE_EXTRA_PROTOCOL_VERSION, ModuleRelayContract.UCLONE_PROTOCOL_VERSION)
            .putExtra(ModuleRelayContract.UCLONE_EXTRA_OPERATION, ModuleRelayContract.OPERATION_SWITCH_OR_RESTORE)
            .putExtra(ModuleRelayContract.UCLONE_EXTRA_PACKAGE_NAME, packageName)
            .putExtra(ModuleRelayContract.UCLONE_EXTRA_REQUEST_ID, requestId)
            .putExtra(ModuleRelayContract.UCLONE_EXTRA_SOURCE, ModuleRelayContract.UCLONE_SOURCE)
}

package com.uclone.restore.module.relay

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

class ModuleRelayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val packageName = intent.getStringExtra(ModuleRelayContract.EXTRA_PACKAGE_NAME).orEmpty()
        val requestId = intent.getStringExtra(ModuleRelayContract.EXTRA_REQUEST_ID).orEmpty()
        if (packageName.isBlank()) {
            ModuleSettingsStore.recordRelayEvent(this, "reject missing packageName request=$requestId")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        ModuleSettingsStore.recordRelayEvent(this, "received package=$packageName request=$requestId")
        runCatching {
            val ucloneIntent = Intent(ModuleRelayContract.UCLONE_ACTION_EXECUTE)
                .setClassName(ModuleConstants.UCLONE_PACKAGE, ModuleConstants.UCLONE_SERVICE)
                .putExtra(ModuleRelayContract.UCLONE_EXTRA_PROTOCOL_VERSION, ModuleRelayContract.UCLONE_PROTOCOL_VERSION)
                .putExtra(ModuleRelayContract.UCLONE_EXTRA_OPERATION, ModuleRelayContract.OPERATION_SWITCH_OR_RESTORE)
                .putExtra(ModuleRelayContract.UCLONE_EXTRA_PACKAGE_NAME, packageName)
                .putExtra(ModuleRelayContract.UCLONE_EXTRA_REQUEST_ID, requestId)
                .putExtra(ModuleRelayContract.UCLONE_EXTRA_SOURCE, ModuleRelayContract.UCLONE_SOURCE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(ucloneIntent)
            } else {
                startService(ucloneIntent)
            }
            ModuleSettingsStore.recordRelayEvent(this, "sent package=$packageName request=$requestId")
        }.onFailure { error ->
            ModuleSettingsStore.recordRelayEvent(
                this,
                "failed package=$packageName request=$requestId type=${error.javaClass.simpleName} error=${error.message}",
            )
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

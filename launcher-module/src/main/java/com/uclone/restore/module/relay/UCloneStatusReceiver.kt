package com.uclone.restore.module.relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UCloneStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.uclone.restore.action.STATUS") return
        val packageName = intent.getStringExtra("com.uclone.restore.extra.PACKAGE_NAME").orEmpty()
        val operation = intent.getStringExtra("com.uclone.restore.extra.OPERATION").orEmpty()
        val requestId = intent.getStringExtra("com.uclone.restore.extra.REQUEST_ID").orEmpty()
        val status = intent.getStringExtra(ModuleRelayContract.UCLONE_EXTRA_STATUS).orEmpty()
        val message = intent.getStringExtra(ModuleRelayContract.UCLONE_EXTRA_MESSAGE).orEmpty()
        ModuleSettingsStore.recordRelayEvent(
            context,
            "stage=$status package=$packageName operation=$operation request=$requestId message=$message",
        )
    }
}

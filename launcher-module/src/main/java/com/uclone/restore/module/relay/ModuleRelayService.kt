package com.uclone.restore.module.relay

import android.app.Service
import android.content.Intent
import android.os.IBinder

class ModuleRelayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ModuleRelayDispatcher.dispatch(this, intent, "service")
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

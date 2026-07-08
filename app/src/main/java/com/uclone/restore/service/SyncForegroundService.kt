package com.uclone.restore.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.uclone.restore.R

class SyncForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val message = intent?.getStringExtra(EXTRA_MESSAGE) ?: "正在执行 UClone 任务"
        ensureChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("UClone Restore")
                .setContentText(message)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "UClone tasks", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "uclone_tasks"
        private const val NOTIFICATION_ID = 41010
        private const val EXTRA_MESSAGE = "message"
        private const val ACTION_STOP = "com.uclone.restore.STOP_SYNC_SERVICE"

        fun start(context: Context, message: String) {
            val intent = Intent(context, SyncForegroundService::class.java).putExtra(EXTRA_MESSAGE, message)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SyncForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}

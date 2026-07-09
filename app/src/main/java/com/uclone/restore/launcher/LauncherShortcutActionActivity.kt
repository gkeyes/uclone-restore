package com.uclone.restore.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.uclone.restore.UCloneApplication
import com.uclone.restore.external.ExternalActionContract
import com.uclone.restore.external.ExternalActionService
import java.util.UUID

class LauncherShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as? UCloneApplication)?.container?.launcherShortcutController
        val request = controller?.let { LauncherShortcutRequest.fromIntent(intent, it.shortcutToken()) }
        val started = request?.let { runCatching { startExternalAction(this, it.packageName) }.getOrDefault(false) } ?: false
        if (!started) {
            Toast.makeText(this, "快捷入口已失效，请打开 UClone 刷新收藏快捷方式", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        fun startExternalAction(context: Context, packageName: String): Boolean {
            if (!isFavoriteShortcutTarget(context, packageName)) return false
            val intent = Intent(context, ExternalActionService::class.java)
                .setAction(ExternalActionContract.ACTION_EXECUTE)
                .putExtra(ExternalActionContract.EXTRA_PROTOCOL_VERSION, ExternalActionContract.PROTOCOL_VERSION)
                .putExtra(ExternalActionContract.EXTRA_OPERATION, ExternalActionContract.OPERATION_SWITCH_OR_RESTORE)
                .putExtra(ExternalActionContract.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(ExternalActionContract.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
                .putExtra(ExternalActionContract.EXTRA_SOURCE, ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT)
            ExternalActionService.start(context, intent)
            return true
        }

        private fun isFavoriteShortcutTarget(context: Context, packageName: String): Boolean {
            val app = context.applicationContext as? UCloneApplication ?: return false
            return packageName in app.container.settingsStore.load().favoritePackages
        }
    }
}

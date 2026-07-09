package com.uclone.restore

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uclone.restore.launcher.LauncherShortcutActionActivity
import com.uclone.restore.launcher.LauncherShortcutRequest
import com.uclone.restore.ui.UCloneApp
import com.uclone.restore.ui.UCloneViewModel
import com.uclone.restore.ui.UCloneViewModelFactory

class MainActivity : ComponentActivity() {
    private var launcherShortcutRequest by mutableStateOf<LauncherShortcutRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleLauncherShortcutIntent(intent)) return
        val container = (application as UCloneApplication).container
        setContent {
            val viewModel: UCloneViewModel = viewModel(
                factory = UCloneViewModelFactory(application, container),
            )
            UCloneApp(
                viewModel = viewModel,
                launcherShortcutRequest = launcherShortcutRequest,
                onLauncherShortcutHandled = { launcherShortcutRequest = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLauncherShortcutIntent(intent)
    }

    private fun handleLauncherShortcutIntent(intent: Intent?): Boolean {
        if (!LauncherShortcutRequest.isShortcutIntent(intent)) return false
        val controller = (application as UCloneApplication).container.launcherShortcutController
        val request = LauncherShortcutRequest.fromIntent(intent, controller.shortcutToken())
        val started = request
            ?.let { runCatching { LauncherShortcutActionActivity.startExternalAction(this, it.packageName) }.getOrDefault(false) }
            ?: false
        if (!started) {
            Toast.makeText(this, "快捷入口已失效，请打开 UClone 刷新收藏快捷方式", Toast.LENGTH_SHORT).show()
        }
        launcherShortcutRequest = null
        finish()
        return true
    }
}

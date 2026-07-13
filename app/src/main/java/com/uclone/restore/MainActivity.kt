package com.uclone.restore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uclone.restore.launcher.LauncherShortcutActionActivity
import com.uclone.restore.launcher.LauncherShortcutRequest
import com.uclone.restore.ui.UCloneApp
import com.uclone.restore.ui.UCloneViewModel
import com.uclone.restore.ui.UCloneViewModelFactory

class MainActivity : ComponentActivity() {
    private var launcherShortcutRequest by mutableStateOf<LauncherShortcutRequest?>(null)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (handleLauncherShortcutIntent(intent)) return
        val container = (application as UCloneApplication).container
        launcherShortcutRequest = LauncherShortcutRequest.fromIntent(
            intent,
            container.launcherShortcutController.shortcutToken(),
        )
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
        requestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleLauncherShortcutIntent(intent)) {
            val controller = (application as UCloneApplication).container.launcherShortcutController
            launcherShortcutRequest = LauncherShortcutRequest.fromIntent(intent, controller.shortcutToken())
        }
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

    private fun requestNotificationPermission() {
        val isGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (shouldRequestNotificationPermission(Build.VERSION.SDK_INT, isGranted)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

internal fun shouldRequestNotificationPermission(sdkInt: Int, isGranted: Boolean): Boolean =
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !isGranted

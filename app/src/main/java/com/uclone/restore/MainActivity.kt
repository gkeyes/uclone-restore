package com.uclone.restore

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uclone.restore.launcher.LauncherShortcutRequest
import com.uclone.restore.ui.UCloneApp
import com.uclone.restore.ui.UCloneViewModel
import com.uclone.restore.ui.UCloneViewModelFactory

class MainActivity : ComponentActivity() {
    private var launcherShortcutRequest by mutableStateOf<LauncherShortcutRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcherShortcutRequest = LauncherShortcutRequest.fromIntent(intent)
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
        launcherShortcutRequest = LauncherShortcutRequest.fromIntent(intent)
    }
}

package com.uclone.restore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.uclone.restore.ui.UCloneApp
import com.uclone.restore.ui.UCloneViewModel
import com.uclone.restore.ui.UCloneViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as UCloneApplication).container
        setContent {
            val viewModel: UCloneViewModel = viewModel(
                factory = UCloneViewModelFactory(application, container),
            )
            UCloneApp(viewModel)
        }
    }
}

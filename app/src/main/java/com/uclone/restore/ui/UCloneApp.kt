package com.uclone.restore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Destination(val label: String) {
    HOME("首页"),
    APPS("App"),
    DATA("数据"),
    HISTORY("历史"),
    SETTINGS("设置"),
    DIAGNOSTICS("诊断"),
    DETAIL("详情"),
    DATA_DETAIL("备份详情"),
}

@Composable
fun UCloneApp(viewModel: UCloneViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var previousTopLevelDestination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var dataDetailPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var dataDetailRollbackId by rememberSaveable { mutableStateOf<String?>(null) }

    UCloneTheme {
        BackHandler(enabled = destination == Destination.DETAIL || destination == Destination.DATA_DETAIL) {
            destination = if (destination == Destination.DATA_DETAIL) Destination.DATA else previousTopLevelDestination
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    listOf(
                        Destination.HOME to Icons.Default.Home,
                        Destination.APPS to Icons.Default.Apps,
                        Destination.DATA to Icons.Default.Assessment,
                        Destination.HISTORY to Icons.Default.History,
                        Destination.SETTINGS to Icons.Default.Settings,
                        Destination.DIAGNOSTICS to Icons.Default.Terminal,
                    ).forEach { (item, icon) ->
                        NavigationBarItem(
                            selected = destination == item || (destination == Destination.DATA_DETAIL && item == Destination.DATA),
                            onClick = {
                                previousTopLevelDestination = item
                                destination = item
                            },
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(item.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = IosBlue,
                                selectedTextColor = IosBlue,
                                indicatorColor = IosBlue.copy(alpha = 0.12f),
                                unselectedIconColor = IosTertiaryText,
                                unselectedTextColor = IosTertiaryText,
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (destination) {
                Destination.HOME -> HomeScreen(state, viewModel, modifier) {
                    previousTopLevelDestination = Destination.HOME
                    destination = Destination.DETAIL
                }
                Destination.APPS -> AppListScreen(state, viewModel, modifier) {
                    previousTopLevelDestination = Destination.APPS
                    destination = Destination.DETAIL
                }
                Destination.DATA -> DataScreen(
                    state = state,
                    viewModel = viewModel,
                    modifier = modifier,
                    openActiveBackup = {
                        dataDetailPackage = it
                        dataDetailRollbackId = null
                        destination = Destination.DATA_DETAIL
                    },
                    openPassiveBackup = {
                        dataDetailPackage = it.packageName
                        dataDetailRollbackId = it.rollbackId
                        destination = Destination.DATA_DETAIL
                    },
                )
                Destination.HISTORY -> HistoryScreen(state, viewModel, modifier)
                Destination.SETTINGS -> SettingsScreen(state, viewModel, modifier)
                Destination.DIAGNOSTICS -> DiagnosticsScreen(state, viewModel, modifier)
                Destination.DETAIL -> AppDetailScreen(state, viewModel, modifier) {
                    destination = previousTopLevelDestination
                }
                Destination.DATA_DETAIL -> DataBackupDetailScreen(
                    state = state,
                    viewModel = viewModel,
                    modifier = modifier,
                    packageName = dataDetailPackage,
                    rollbackId = dataDetailRollbackId,
                ) {
                    destination = Destination.DATA
                }
            }
        }
    }
}

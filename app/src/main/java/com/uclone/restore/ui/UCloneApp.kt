package com.uclone.restore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uclone.restore.launcher.LauncherShortcutRequest
import kotlinx.coroutines.launch

private enum class Destination(val label: String, val icon: ImageVector?) {
    HOME("首页", Icons.Default.Home),
    APPS("App", Icons.Default.Apps),
    DATA("数据", Icons.Default.Assessment),
    HISTORY("历史", Icons.Default.History),
    SETTINGS("设置", Icons.Default.Settings),
    DIAGNOSTICS("诊断", Icons.Default.Terminal),
    DETAIL("App 详情", null),
    DATA_DETAIL("备份详情", null),
}

private enum class NavigationLayout { COMPACT, MEDIUM, EXPANDED }

private val topLevelDestinations = listOf(
    Destination.HOME,
    Destination.APPS,
    Destination.DATA,
    Destination.HISTORY,
    Destination.SETTINGS,
    Destination.DIAGNOSTICS,
)

@Composable
fun UCloneApp(
    viewModel: UCloneViewModel,
    launcherShortcutRequest: LauncherShortcutRequest? = null,
    onLauncherShortcutHandled: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var previousTopLevelDestination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var dataDetailPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var dataDetailRollbackId by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    UCloneTheme {
        DisposableEffect(lifecycleOwner, viewModel) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshTaskHistory()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(launcherShortcutRequest) {
            launcherShortcutRequest?.let { request ->
                previousTopLevelDestination = Destination.HOME
                destination = Destination.HOME
                viewModel.handleLauncherFavoriteShortcut(request.packageName)
                onLauncherShortcutHandled()
            }
        }

        val navigateBack = {
            destination = if (destination == Destination.DATA_DETAIL) {
                Destination.DATA
            } else {
                previousTopLevelDestination
            }
        }
        BackHandler(enabled = destination == Destination.DETAIL || destination == Destination.DATA_DETAIL) {
            navigateBack()
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val layout = when {
                maxWidth < 600.dp -> NavigationLayout.COMPACT
                maxWidth < 840.dp -> NavigationLayout.MEDIUM
                else -> NavigationLayout.EXPANDED
            }
            val selectDestination: (Destination) -> Unit = { item ->
                previousTopLevelDestination = item
                destination = item
            }
            val openHistory = { selectDestination(Destination.HISTORY) }
            val content: @Composable (Modifier) -> Unit = { modifier ->
                DestinationContent(
                    destination = destination,
                    state = state,
                    viewModel = viewModel,
                    modifier = modifier,
                    openAppDetail = {
                        previousTopLevelDestination = destination.takeIf { it in topLevelDestinations }
                            ?: previousTopLevelDestination
                        destination = Destination.DETAIL
                    },
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
                    dataDetailPackage = dataDetailPackage,
                    dataDetailRollbackId = dataDetailRollbackId,
                )
            }

            when (layout) {
                NavigationLayout.COMPACT -> CompactShell(
                    destination = destination,
                    taskActive = state.currentTask.task != null,
                    onSelect = selectDestination,
                    onBack = navigateBack,
                    onOpenHistory = openHistory,
                    content = content,
                )
                NavigationLayout.MEDIUM -> MediumShell(
                    destination = destination,
                    taskActive = state.currentTask.task != null,
                    onSelect = selectDestination,
                    onBack = navigateBack,
                    onOpenHistory = openHistory,
                    content = content,
                )
                NavigationLayout.EXPANDED -> ExpandedShell(
                    destination = destination,
                    taskActive = state.currentTask.task != null,
                    onSelect = selectDestination,
                    onBack = navigateBack,
                    onOpenHistory = openHistory,
                    content = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactShell(
    destination: Destination,
    taskActive: Boolean,
    onSelect: (Destination) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(296.dp)) {
                DrawerHeader()
                NavigationItems(
                    destination = destination,
                    onSelect = {
                        onSelect(it)
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        AppScaffold(
            destination = destination,
            taskActive = taskActive,
            navigationIcon = {
                if (destination in topLevelDestinations) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Default.Menu, contentDescription = "打开导航")
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            onOpenHistory = onOpenHistory,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediumShell(
    destination: Destination,
    taskActive: Boolean,
    onSelect: (Destination) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Spacer(Modifier.height(12.dp))
            topLevelDestinations.forEach { item ->
                NavigationRailItem(
                    selected = destination.belongsTo(item),
                    onClick = { onSelect(item) },
                    icon = { Icon(item.icon!!, contentDescription = null) },
                    label = { Text(item.label) },
                    alwaysShowLabel = true,
                )
            }
        }
        VerticalDivider()
        AppScaffold(
            destination = destination,
            taskActive = taskActive,
            navigationIcon = if (destination in topLevelDestinations) null else {
                {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            onOpenHistory = onOpenHistory,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedShell(
    destination: Destination,
    taskActive: Boolean,
    onSelect: (Destination) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    PermanentNavigationDrawer(
        drawerContent = {
            PermanentDrawerSheet(modifier = Modifier.width(248.dp)) {
                DrawerHeader()
                NavigationItems(destination, onSelect)
            }
        },
    ) {
        AppScaffold(
            destination = destination,
            taskActive = taskActive,
            navigationIcon = if (destination in topLevelDestinations) null else {
                {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            onOpenHistory = onOpenHistory,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    destination: Destination,
    taskActive: Boolean,
    navigationIcon: (@Composable () -> Unit)?,
    onOpenHistory: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(destination.label, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { navigationIcon?.invoke() },
                actions = {
                    if (taskActive) {
                        IconButton(onClick = onOpenHistory) {
                            Icon(
                                Icons.Default.PendingActions,
                                contentDescription = "查看当前任务",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun DrawerHeader() {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("U", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("UClone Restore", style = MaterialTheme.typography.titleLarge)
        Text("系统数据控制台", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun NavigationItems(destination: Destination, onSelect: (Destination) -> Unit) {
    topLevelDestinations.forEach { item ->
        NavigationDrawerItem(
            label = { Text(item.label) },
            selected = destination.belongsTo(item),
            onClick = { onSelect(item) },
            icon = { Icon(item.icon!!, contentDescription = null) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            shape = MaterialTheme.shapes.medium,
        )
    }
}

private fun Destination.belongsTo(topLevel: Destination): Boolean = when (this) {
    Destination.DETAIL -> topLevel == Destination.APPS
    Destination.DATA_DETAIL -> topLevel == Destination.DATA
    else -> this == topLevel
}

@Composable
private fun DestinationContent(
    destination: Destination,
    state: UiState,
    viewModel: UCloneViewModel,
    modifier: Modifier,
    openAppDetail: () -> Unit,
    openActiveBackup: (String) -> Unit,
    openPassiveBackup: (com.uclone.restore.model.RestoreBackupEntry) -> Unit,
    dataDetailPackage: String?,
    dataDetailRollbackId: String?,
) {
    when (destination) {
        Destination.HOME -> HomeScreen(state, viewModel, modifier, openAppDetail)
        Destination.APPS -> AppListScreen(state, viewModel, modifier, openAppDetail)
        Destination.DATA -> DataScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier,
            openActiveBackup = openActiveBackup,
            openPassiveBackup = openPassiveBackup,
        )
        Destination.HISTORY -> HistoryScreen(state, viewModel, modifier)
        Destination.SETTINGS -> SettingsScreen(state, viewModel, modifier)
        Destination.DIAGNOSTICS -> DiagnosticsScreen(state, viewModel, modifier)
        Destination.DETAIL -> AppDetailScreen(state, viewModel, modifier)
        Destination.DATA_DETAIL -> DataBackupDetailScreen(
            state = state,
            viewModel = viewModel,
            modifier = modifier,
            packageName = dataDetailPackage,
            rollbackId = dataDetailRollbackId,
        )
    }
}

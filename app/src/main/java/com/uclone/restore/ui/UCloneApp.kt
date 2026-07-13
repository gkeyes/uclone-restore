package com.uclone.restore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uclone.restore.launcher.LauncherShortcutRequest

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
                NavigationLayout.MEDIUM -> SideShell(
                    destination = destination,
                    taskActive = state.currentTask.task != null,
                    expanded = false,
                    onSelect = selectDestination,
                    onBack = navigateBack,
                    onOpenHistory = openHistory,
                    content = content,
                )
                NavigationLayout.EXPANDED -> SideShell(
                    destination = destination,
                    taskActive = state.currentTask.task != null,
                    expanded = true,
                    onSelect = selectDestination,
                    onBack = navigateBack,
                    onOpenHistory = openHistory,
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun CompactShell(
    destination: Destination,
    taskActive: Boolean,
    onSelect: (Destination) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    content: @Composable (Modifier) -> Unit,
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
        bottomBar = if (destination in topLevelDestinations) {
            { FloatingTabBar(destination = destination, onSelect = onSelect) }
        } else {
            null
        },
        content = content,
    )
}

@Composable
private fun SideShell(
    destination: Destination,
    taskActive: Boolean,
    expanded: Boolean,
    onSelect: (Destination) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        SideNavigation(
            destination = destination,
            expanded = expanded,
            onSelect = onSelect,
        )
        AppScaffold(
            destination = destination,
            taskActive = taskActive,
            modifier = Modifier.weight(1f),
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
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)?,
    onOpenHistory: () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = { navigationIcon?.invoke() },
                actions = {
                    if (taskActive) {
                        Surface(
                            onClick = onOpenHistory,
                            modifier = Modifier.size(48.dp).padding(4.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.PendingActions,
                                    contentDescription = "查看当前任务",
                                    modifier = Modifier.size(21.dp),
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = { bottomBar?.invoke() },
    ) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun FloatingTabBar(
    destination: Destination,
    onSelect: (Destination) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            color = Color.Transparent,
            border = BorderStroke(0.5.dp, MaterialTheme.ucloneColors.glassHighlight),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(
                                MaterialTheme.ucloneColors.navigationSurface.copy(alpha = 0.94f),
                                MaterialTheme.ucloneColors.navigationSurface.copy(alpha = 0.80f),
                            ),
                        ),
                        shape = RoundedCornerShape(30.dp),
                    )
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                topLevelDestinations.forEach { item ->
                    FloatingTabItem(
                        item = item,
                        selected = destination.belongsTo(item),
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.FloatingTabItem(
    item: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 72.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                border = if (selected) {
                    BorderStroke(0.5.dp, MaterialTheme.ucloneColors.glassHighlight.copy(alpha = 0.72f))
                } else {
                    null
                },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(item.icon!!, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.label,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SideNavigation(
    destination: Destination,
    expanded: Boolean,
    onSelect: (Destination) -> Unit,
) {
    Surface(
        modifier = Modifier.width(if (expanded) 248.dp else 92.dp).fillMaxSize(),
        color = MaterialTheme.ucloneColors.navigationSurface,
        border = BorderStroke(0.5.dp, MaterialTheme.ucloneColors.separator.copy(alpha = 0.55f)),
    ) {
        Column(
            Modifier.padding(horizontal = if (expanded) 14.dp else 8.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (expanded) 8.dp else 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("U", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                if (expanded) {
                    Column {
                        Text("UClone Restore", style = MaterialTheme.typography.titleMedium)
                        Text("数据控制台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            topLevelDestinations.forEach { item ->
                SideNavigationItem(
                    item = item,
                    expanded = expanded,
                    selected = destination.belongsTo(item),
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@Composable
private fun SideNavigationItem(
    item: Destination,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        if (expanded) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(item.icon!!, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        } else {
            Column(
                Modifier.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon!!, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(item.label, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
            }
        }
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

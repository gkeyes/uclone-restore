package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskStep
import com.uclone.restore.model.User10CeState
import com.uclone.restore.sync.AppDataState
import com.uclone.restore.util.Formatters

@Composable
fun HomeScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, openDetail: () -> Unit) {
    var confirm by remember { mutableStateOf<HomeConfirm?>(null) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        contentPadding = PaddingValues(bottom = LocalBottomBarContentPadding.current),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageDescription("先确认系统状态，再处理收藏 App 的切换、还原和推送。") }
        item { SystemHealthSection(state, viewModel) }
        if (state.currentTask.task != null) item { CurrentTaskCard(state) }
        item { SectionLabel("收藏 App", "每个 App 只突出当前状态对应的主动作。") }
        if (state.favoriteApps.isEmpty()) {
            item {
                SectionCard("暂无收藏") {
                    Text("在 App 页面点星标后，常用 App 会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item(key = "favorite-apps") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.ucloneColors.groupedSurface,
                ) {
                    Column {
                        state.favoriteApps.forEachIndexed { index, app ->
                            FavoriteAppRow(
                                app = app,
                                dataState = state.dataStateFor(app.packageName),
                                showDivider = index < state.favoriteApps.lastIndex,
                                onOpen = {
                                    viewModel.selectPackage(app.packageName)
                                    openDetail()
                                },
                                onPush = { confirm = HomeConfirm.Push(app.packageName, app.label) },
                                onSwitch = { confirm = HomeConfirm.Switch(app.packageName, app.label) },
                                onRestore = { confirm = HomeConfirm.Restore(app.packageName, app.label) },
                            )
                        }
                    }
                }
            }
        }
    }
    confirm?.let { action ->
        HomeConfirmDialog(
            action = action,
            mainUserId = state.settings.mainUserId,
            cloneUserId = state.settings.cloneUserId,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (action) {
                    is HomeConfirm.Push -> viewModel.pushMainToClone(action.packageName)
                    is HomeConfirm.Switch -> viewModel.switchToCloneState(action.packageName)
                    is HomeConfirm.Restore -> viewModel.restoreSwitchMainState(action.packageName)
                }
            },
        )
    }
}

@Composable
private fun SystemHealthSection(state: UiState, viewModel: UCloneViewModel) {
    val env = state.environment
    val usable = env?.root?.ok == true && env.dataAdbWritable.ok && env.user10Present
    val cloneRunning = env?.user10CeState is User10CeState.StartedLocked ||
        env?.user10CeState is User10CeState.RunningUnlocked
    SectionCard(if (usable) "系统可用" else "需要检查") {
        Text(
            if (usable) "Root、工作区和分身用户已通过基础检查。" else "至少一项基础条件尚未确认，请先重新检测。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusChip(env?.root?.ok == true, if (env?.root?.ok == true) "Root 正常" else "Root 未就绪")
            StatusChip(env?.user10Present == true, "分身 user${state.settings.cloneUserId}")
        }
        InfoRow("当前用户", env?.currentUser ?: "未检测")
        InfoRow("分身系统", env?.user10CeState?.cloneLifecycleLabel ?: "未检测")
        InfoRow("数据状态", env?.user10CeState?.userFacingLabel ?: "未检测")
        HorizontalDivider(color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.45f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            InlineActionButton(
                text = "重新检测",
                onClick = viewModel::refreshEnvironment,
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Refresh,
            )
            if (env != null) {
                Box(
                    Modifier
                        .width(0.5.dp)
                        .height(30.dp)
                        .background(MaterialTheme.ucloneColors.separator.copy(alpha = 0.65f)),
                )
                InlineActionButton(
                    text = if (cloneRunning) "关闭分身" else "启动分身",
                    onClick = if (cloneRunning) viewModel::stopCloneUser else viewModel::startCloneUser,
                    modifier = Modifier.weight(1f),
                    danger = cloneRunning,
                    icon = if (cloneRunning) Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                )
            }
        }
    }
}

@Composable
private fun CurrentTaskCard(state: UiState) {
    val task = state.currentTask.task ?: return
    val activeStep = task.currentStage?.let { TaskStep(it.displayLabel, StepStatus.RUNNING) }
        ?: state.currentTask.steps.firstOrNull { it.status == StepStatus.RUNNING }
        ?: state.currentTask.steps.lastOrNull { it.status == StepStatus.SUCCESS }
    SectionCard("当前任务") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.packageName, fontWeight = FontWeight.SemiBold)
                Text(
                    "${task.type.displayName} · ${task.status.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            activeStep?.let { StatusBadge(it.label, it.status.homeBadgeColor()) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun FavoriteAppRow(
    app: AppEntry,
    dataState: AppDataState,
    showDivider: Boolean,
    onOpen: () -> Unit,
    onPush: () -> Unit,
    onSwitch: () -> Unit,
    onRestore: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, fontWeight = FontWeight.SemiBold)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${dataState.compactLabel()} · ${Formatters.kilobytes(app.snapshotSizeKb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = dataState.color(),
                    fontWeight = FontWeight.Medium,
                )
            }
            CompactActionButton(
                text = when (dataState) {
                    AppDataState.Main -> "切换"
                    is AppDataState.Clone -> "还原"
                    AppDataState.Unknown -> "检查"
                },
                onClick = when (dataState) {
                    AppDataState.Main -> onSwitch
                    is AppDataState.Clone -> onRestore
                    AppDataState.Unknown -> onOpen
                },
                primary = dataState != AppDataState.Unknown,
            )
            Box {
                UtilityIconButton(
                    imageVector = Icons.Default.MoreHoriz,
                    contentDescription = "更多操作",
                    onClick = { menuExpanded = true },
                    framed = true,
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("推送当前主系统数据到分身") },
                        leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPush()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("查看 App 详情") },
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        },
                    )
                }
            }
        }
    }
    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 64.dp),
            color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.42f),
        )
    }
}

private fun AppDataState.compactLabel(): String = when (this) {
    AppDataState.Main -> "MAIN 主数据"
    is AppDataState.Clone -> "CLONE 分数据"
    AppDataState.Unknown -> "UNKNOWN 状态未知"
}

@Composable
private fun AppDataState.color(): Color = when (this) {
    AppDataState.Main -> MaterialTheme.ucloneColors.success
    is AppDataState.Clone -> MaterialTheme.colorScheme.primary
    AppDataState.Unknown -> MaterialTheme.ucloneColors.warning
}

private sealed class HomeConfirm {
    data class Push(val packageName: String, val label: String) : HomeConfirm()
    data class Switch(val packageName: String, val label: String) : HomeConfirm()
    data class Restore(val packageName: String, val label: String) : HomeConfirm()
}

@Composable
private fun HomeConfirmDialog(
    action: HomeConfirm,
    mainUserId: Int,
    cloneUserId: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        is HomeConfirm.Push -> "推送到分身"
        is HomeConfirm.Switch -> "切换到分身态"
        is HomeConfirm.Restore -> "还原主系统态"
    }
    val body = when (action) {
        is HomeConfirm.Push -> "来源：user$mainUserId 当前 ${action.label} 数据。\n目标：user$cloneUserId 分身 App 数据。\n保护：执行前保存分身回滚。\n后果：覆盖分身当前数据，不改变首页切换标记。"
        is HomeConfirm.Switch -> "来源：user$cloneUserId 分身最新 ${action.label} 数据。\n目标：user$mainUserId App 数据。\n保护：先保存当前主数据返回点。\n后果：主系统将进入分数据 CLONE 状态。"
        is HomeConfirm.Restore -> "来源：切换前保存的 MAIN 返回点。\n目标：user$mainUserId App 数据。\n保护：本次任务仍会建立事务回滚。\n后果：清除首页还原标记。"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { DialogActionButton("继续", onConfirm, primary = true) },
        dismissButton = { DialogActionButton("取消", onDismiss) },
    )
}

@Composable
private fun StepStatus.homeBadgeColor(): Color = when (this) {
    StepStatus.SUCCESS -> MaterialTheme.ucloneColors.success
    StepStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    StepStatus.RUNNING -> MaterialTheme.ucloneColors.warning
    StepStatus.PENDING -> MaterialTheme.ucloneColors.neutral
}

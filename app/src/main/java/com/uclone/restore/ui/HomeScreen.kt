package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.StepStatus
import com.uclone.restore.model.TaskStep
import com.uclone.restore.sync.AppDataState
import com.uclone.restore.sync.TransactionRecoveryState
import com.uclone.restore.util.Formatters

@Composable
fun HomeScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, openDetail: () -> Unit) {
    var confirm by remember { mutableStateOf<HomeConfirm?>(null) }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeader("首页", "收藏 App 的切换、还原与单向推送。")
        }
        item {
            SectionCard("系统状态") {
                val env = state.environment
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusChip(env?.root?.ok == true, if (env?.root?.ok == true) "Root 正常" else "Root 异常")
                    StatusChip(env?.user10Present == true, "user${state.settings.cloneUserId}")
                }
                InfoRow("当前用户", env?.currentUser ?: "未检测")
                InfoRow("分身状态", env?.user10State ?: "未检测")
                InfoRow("CE gate", env?.user10CeState?.label ?: "未检测")
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IosPrimaryButton(onClick = viewModel::refreshEnvironment, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("检测")
                    }
                    IosSecondaryButton(onClick = viewModel::startCloneUser, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text("启动")
                    }
                    IosSecondaryButton(onClick = viewModel::stopCloneUser, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Text("关闭")
                    }
                }
            }
        }
        if (state.transactionRecovery !is TransactionRecoveryState.Ready) {
            item {
                TransactionRecoveryCard(state.transactionRecovery, viewModel::retryInterruptedTransactionRecovery)
            }
        }
        if (state.currentTask.task != null) {
            item {
                CurrentTaskCard(state, viewModel::cancelCurrentTaskSafely)
            }
        }
        item {
            Text(
                "收藏 App",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, end = 4.dp),
            )
        }
        if (state.favoriteApps.isEmpty()) {
            item {
                SectionCard("未收藏") {
                    Text("在 App 列表点星标加入首页。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.favoriteApps, key = { it.packageName }) { app ->
                FavoriteAppRow(
                    app = app,
                    dataState = state.dataStateFor(app.packageName),
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
    confirm?.let { action ->
        HomeConfirmDialog(
            action = action,
            forceUpdateCloneDataBeforeMainRestore = state.settings.forceUpdateCloneDataBeforeMainRestore,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (action) {
                    is HomeConfirm.Push -> viewModel.pushMainToClone(action.packageName)
                    is HomeConfirm.Switch -> {
                        if (state.dataStateFor(action.packageName).homePrimaryAction == HomePrimaryAction.SWITCH) {
                            viewModel.switchToCloneState(action.packageName)
                        } else {
                            viewModel.selectPackage(action.packageName)
                            openDetail()
                        }
                    }
                    is HomeConfirm.Restore -> {
                        if (state.dataStateFor(action.packageName).homePrimaryAction == HomePrimaryAction.RESTORE) {
                            viewModel.restoreSwitchMainState(action.packageName)
                        } else {
                            viewModel.selectPackage(action.packageName)
                            openDetail()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun TransactionRecoveryCard(
    recovery: TransactionRecoveryState,
    onRetry: () -> Unit,
) {
    SectionCard("数据安全恢复") {
        val message = when (recovery) {
            TransactionRecoveryState.Scanning -> "正在检查上次任务是否完整结束。"
            TransactionRecoveryState.Ready -> "事务状态正常。"
            is TransactionRecoveryState.RootTaskStillRunning -> "上次 Root 数据任务仍在运行，完成前不会启动新的覆盖任务。"
            is TransactionRecoveryState.Required -> "检测到 ${recovery.transactions.size} 个未完成事务，目标 App 会保持冻结，直到回滚完成。"
            is TransactionRecoveryState.Recovering -> "正在恢复 ${recovery.transaction.packageName} 的操作前数据。"
            is TransactionRecoveryState.Failed -> "无法完成安全恢复：${recovery.message}"
        }
        Text(
            message,
            color = if (recovery is TransactionRecoveryState.Failed || recovery is TransactionRecoveryState.Required) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (recovery is TransactionRecoveryState.Scanning || recovery is TransactionRecoveryState.Recovering) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (recovery is TransactionRecoveryState.Required || recovery is TransactionRecoveryState.Failed) {
            IosPrimaryButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("重新执行安全恢复")
            }
        }
    }
}

@Composable
private fun CurrentTaskCard(state: UiState, onCancel: () -> Unit) {
    val task = state.currentTask.task ?: return
    val activeStep = state.currentTask.task?.currentStage?.let { TaskStep(it.displayLabel, StepStatus.RUNNING) }
        ?: state.currentTask.steps.firstOrNull { it.status == StepStatus.RUNNING }
        ?: state.currentTask.steps.lastOrNull { it.status == StepStatus.SUCCESS }
    SectionCard("最新任务") {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.packageName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${task.type.userFacingLabel} · ${task.status.userFacingLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            activeStep?.let { step ->
                IosStatusPill(step.label, step.status.homePillColor())
            }
        }
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            IosSecondaryButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("安全停止")
            }
        }
    }
}

@Composable
private fun FavoriteAppRow(
    app: AppEntry,
    dataState: AppDataState,
    onOpen: () -> Unit,
    onPush: () -> Unit,
    onSwitch: () -> Unit,
    onRestore: () -> Unit,
) {
    val primaryAction = dataState.homePrimaryAction
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = IosGlass),
        border = BorderStroke(1.dp, IosGlassBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (primaryAction == HomePrimaryAction.OPEN_DETAILS) {
                        "状态未知 · 进入详情确认"
                    } else {
                        "${app.packageName} · ${Formatters.kilobytes(app.snapshotSizeKb)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (primaryAction == HomePrimaryAction.OPEN_DETAILS) {
                        IosOrange
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IosCompactButton(
                    text = "推送",
                    onClick = onPush,
                    icon = Icons.Default.Upload,
                )
                IosCompactButton(
                    text = when (primaryAction) {
                        HomePrimaryAction.SWITCH -> "切换"
                        HomePrimaryAction.RESTORE -> "还原"
                        HomePrimaryAction.OPEN_DETAILS -> "检查"
                    },
                    onClick = {
                        when (primaryAction) {
                            HomePrimaryAction.SWITCH -> onSwitch()
                            HomePrimaryAction.RESTORE -> onRestore()
                            HomePrimaryAction.OPEN_DETAILS -> onOpen()
                        }
                    },
                    primary = primaryAction == HomePrimaryAction.SWITCH,
                    semanticTint = if (primaryAction == HomePrimaryAction.OPEN_DETAILS) IosOrange else null,
                    icon = when (primaryAction) {
                        HomePrimaryAction.SWITCH -> Icons.Default.Sync
                        HomePrimaryAction.RESTORE -> Icons.Default.Refresh
                        HomePrimaryAction.OPEN_DETAILS -> Icons.AutoMirrored.Outlined.FactCheck
                    },
                )
            }
        }
    }
}

private sealed class HomeConfirm {
    data class Push(val packageName: String, val label: String) : HomeConfirm()
    data class Switch(val packageName: String, val label: String) : HomeConfirm()
    data class Restore(val packageName: String, val label: String) : HomeConfirm()
}

@Composable
private fun HomeConfirmDialog(
    action: HomeConfirm,
    forceUpdateCloneDataBeforeMainRestore: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        is HomeConfirm.Push -> "推送到分身"
        is HomeConfirm.Switch -> "切换到分身态"
        is HomeConfirm.Restore -> "还原主系统态"
    }
    val body = when (action) {
        is HomeConfirm.Push -> "会把主系统当前 ${action.label} 数据覆盖到分身。执行前确保分身有长期回滚点，并另外建立本次任务专属临时回滚；不影响首页切换/还原状态。"
        is HomeConfirm.Switch -> "会先确保主系统有长期返回点，并建立本次任务专属临时回滚，再使用分身最新状态恢复 ${action.label}。"
        is HomeConfirm.Restore -> if (forceUpdateCloneDataBeforeMainRestore) {
            "会先把 ${action.label} 当前分身态数据同步回分系统；只有同步成功后，才会恢复切换前的主数据并清除还原标记。同步失败时仍保持分身态。"
        } else {
            "会用切换前保存的被动备份恢复 ${action.label}，并清除首页还原标记。"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { IosDialogButton("继续", onConfirm, primary = true) },
        dismissButton = { IosDialogButton("取消", onDismiss) },
    )
}

private fun StepStatus.homePillColor() = when (this) {
    StepStatus.SUCCESS -> IosGreen
    StepStatus.FAILED -> IosRed
    StepStatus.RUNNING -> IosOrange
    StepStatus.PENDING -> IosSecondaryText
}

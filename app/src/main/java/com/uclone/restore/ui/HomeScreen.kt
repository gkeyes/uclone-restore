package com.uclone.restore.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            ScreenHeader("首页", "收藏 App 的一键切换与还原。")
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IosPrimaryButton(onClick = viewModel::refreshEnvironment, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("检测")
                    }
                    IosSecondaryButton(onClick = viewModel::startCloneUser, modifier = Modifier.weight(1f)) {
                        Text("启动")
                    }
                    IosSecondaryButton(onClick = viewModel::stopCloneUser, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Text("关闭")
                    }
                }
            }
        }
        item {
            Text(
                "收藏 App",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, top = 2.dp),
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
                    canRestore = state.switchRollbackIds.containsKey(app.packageName),
                    onOpen = {
                        viewModel.selectPackage(app.packageName)
                        openDetail()
                    },
                    onSwitch = { confirm = HomeConfirm.Switch(app.packageName, app.label) },
                    onRestore = { confirm = HomeConfirm.Restore(app.packageName, app.label) },
                )
            }
        }
        if (state.message != null) {
            item {
                Text(state.message, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    confirm?.let { action ->
        HomeConfirmDialog(
            action = action,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (action) {
                    is HomeConfirm.Switch -> viewModel.switchToCloneState(action.packageName)
                    is HomeConfirm.Restore -> viewModel.restoreSwitchMainState(action.packageName)
                }
            },
        )
    }
}

@Composable
private fun FavoriteAppRow(
    app: AppEntry,
    canRestore: Boolean,
    onOpen: () -> Unit,
    onSwitch: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    "${app.packageName} · ${Formatters.kilobytes(app.snapshotSizeKb)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IosCompactButton("切换", onClick = onSwitch, primary = true)
            IosCompactButton("还原", onClick = onRestore, enabled = canRestore)
        }
    }
}

private sealed class HomeConfirm {
    data class Switch(val packageName: String, val label: String) : HomeConfirm()
    data class Restore(val packageName: String, val label: String) : HomeConfirm()
}

@Composable
private fun HomeConfirmDialog(action: HomeConfirm, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val title = when (action) {
        is HomeConfirm.Switch -> "切换到分身态"
        is HomeConfirm.Restore -> "还原主系统态"
    }
    val body = when (action) {
        is HomeConfirm.Switch -> "会先保存当前主系统数据为还原点，再使用分身最新状态恢复 ${action.label}。"
        is HomeConfirm.Restore -> "会用切换前保存的还原点恢复 ${action.label}，并清除首页还原标记。"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("继续") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

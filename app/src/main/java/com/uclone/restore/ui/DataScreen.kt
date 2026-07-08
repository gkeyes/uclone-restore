package com.uclone.restore.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ChevronRight
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
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.util.Formatters

@Composable
fun DataScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, openDetail: () -> Unit) {
    var confirmRestore by remember { mutableStateOf<RestoreBackupEntry?>(null) }
    val appByPackage = state.apps.associateBy { it.packageName }
    val activeBackups = state.apps
        .filter { it.lastSnapshotAt != null }
        .sortedByDescending { it.lastSnapshotAt ?: 0L }
    val passiveBackups = state.restoreBackups

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeader("数据", "主动备份和被动备份分开查看。")
        }
        item {
            SectionCard("存储区分") {
                Text("主动备份: ${state.settings.rootDir}/snapshots/<包名>/active")
                Text("被动备份: ${state.settings.rootDir}/rollback/<包名>/<时间>")
                Text(
                    "两类数据在不同目录下保存，不会和 adb 目录里的其它文件混在一起。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            DataSectionHeader("主动备份", "手动建立或任务中刷新出的分身快照。")
        }
        if (activeBackups.isEmpty()) {
            item {
                SectionCard("暂无主动备份") {
                    Text("点击 App 详情里的“建立主动备份”后会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(activeBackups, key = { "active-${it.packageName}" }) { app ->
                ActiveBackupRow(app) {
                    viewModel.selectPackage(app.packageName)
                    openDetail()
                }
            }
        }
        item {
            DataSectionHeader("被动备份", "恢复、切换、还原前自动生成的主系统备份。")
        }
        if (passiveBackups.isEmpty()) {
            item {
                SectionCard("暂无被动备份") {
                    Text("执行恢复或切换前，App 会自动保存当前 user0 数据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(passiveBackups, key = { "passive-${it.packageName}-${it.rollbackId}" }) { backup ->
                PassiveBackupRow(
                    backup = backup,
                    app = appByPackage[backup.packageName],
                    onOpenDetail = {
                        viewModel.selectPackage(backup.packageName)
                        openDetail()
                    },
                    onRestore = { confirmRestore = backup },
                )
            }
        }
    }

    confirmRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("恢复被动备份") },
            text = {
                Text(
                    "将使用 ${backup.rollbackId} 覆盖主系统 user0 的 ${backup.packageName} 数据。该备份来源: ${backup.reason}。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = null
                        viewModel.restoreBackup(backup.packageName, backup.rollbackId)
                    },
                ) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DataSectionHeader(title: String, caption: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActiveBackupRow(app: AppEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    Formatters.kilobytes(app.snapshotSizeKb),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Formatters.time(app.lastSnapshotAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = IosTertiaryText)
        }
    }
}

@Composable
private fun PassiveBackupRow(
    backup: RestoreBackupEntry,
    app: AppEntry?,
    onOpenDetail: () -> Unit,
    onRestore: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
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
            AppIcon(backup.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    app?.label ?: backup.packageName,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    backup.rollbackId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    backup.reason + if (backup.isActiveSwitchBackup) " · 当前切换被动备份" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = IosOrange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    Formatters.kilobytes(backup.sizeKb),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    Formatters.time(backup.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IosCompactButton(text = "恢复", onClick = onRestore)
            }
        }
    }
}

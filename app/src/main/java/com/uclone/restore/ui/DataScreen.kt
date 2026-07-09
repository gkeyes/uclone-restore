package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.RestoreBackupEntry

@Composable
fun DataScreen(
    state: UiState,
    viewModel: UCloneViewModel,
    modifier: Modifier,
    openActiveBackup: (String) -> Unit,
    openPassiveBackup: (RestoreBackupEntry) -> Unit,
) {
    var confirmRestore by remember { mutableStateOf<RestoreBackupEntry?>(null) }
    val appByPackage = state.apps.associateBy { it.packageName }
    val rootDir = state.settings.rootDir
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
                SingleLinePathText("主动快照: $rootDir/snapshots/<包名>/active")
                SingleLinePathText("被动备份: $rootDir/rollback/<包名>/<备份ID>")
                Text(
                    "切换和还原产生的被动备份每个 App 只显示最新一份。",
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
                    openActiveBackup(app.packageName)
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
                    rootDir = rootDir,
                    app = appByPackage[backup.packageName],
                    onOpenDetail = {
                        openPassiveBackup(backup)
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将使用以下被动备份覆盖主系统 user0 数据。")
                    SingleLinePathText("$rootDir/rollback/${backup.packageName}/${backup.rollbackId}")
                    Text("来源: ${backup.reason}")
                }
            },
            confirmButton = {
                IosDialogButton(
                    text = "恢复",
                    onClick = {
                        confirmRestore = null
                        viewModel.restoreBackup(backup.packageName, backup.rollbackId)
                    },
                    primary = true,
                )
            },
            dismissButton = {
                IosDialogButton("取消", onClick = { confirmRestore = null })
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

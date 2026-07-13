package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.PassiveBackupStateKind
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
    var confirmCloneRestore by remember { mutableStateOf<RestoreBackupEntry?>(null) }
    val appByPackage = state.apps.associateBy { it.packageName }
    val rootDir = state.settings.rootDir
    val activeBackups = state.apps
        .filter { it.lastSnapshotAt != null }
        .sortedByDescending { it.lastSnapshotAt ?: 0L }
    val passiveBackups = state.restoreBackups
    val cloneRollbackBackups = state.cloneRollbackBackups

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, top = 12.dp, end = 16.dp),
        contentPadding = PaddingValues(bottom = LocalBottomBarContentPadding.current),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Box(Modifier.padding(bottom = 12.dp)) {
                PageDescription("按主动快照、主系统侧备份和分身回滚分别管理。")
            }
        }
        item {
            Box(Modifier.padding(bottom = 12.dp)) {
                SectionCard("存储区分") {
                    SingleLinePathText("主动快照: $rootDir/snapshots/<包名>/active")
                    SingleLinePathText("被动备份: $rootDir/rollback/<包名>/<备份ID>")
                    SingleLinePathText("分身回滚: $rootDir/clone_rollback/<包名>/latest")
                    Text(
                        "主数据 MAIN 与分数据 CLONE 分别标记；长期状态备份与本次事务回滚分开处理。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            SectionLabel("主动备份", "手动建立或任务中刷新出的分身快照。")
        }
        if (activeBackups.isEmpty()) {
            item {
                Box(Modifier.padding(bottom = 12.dp)) {
                    SectionCard("暂无主动备份") {
                        Text("点击 App 详情里的“建立主动备份”后会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            itemsIndexed(activeBackups, key = { _, app -> "active-${app.packageName}" }) { index, app ->
                ActiveBackupRow(
                    app = app,
                    shape = groupedRowShape(index, activeBackups.lastIndex),
                    showDivider = index < activeBackups.lastIndex,
                ) {
                    openActiveBackup(app.packageName)
                }
                if (index == activeBackups.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        item {
            SectionLabel("主系统侧备份", "显示 user${state.settings.mainUserId} 中保存过的主数据或分数据状态。")
        }
        if (passiveBackups.isEmpty()) {
            item {
                Box(Modifier.padding(bottom = 12.dp)) {
                    SectionCard("暂无被动备份") {
                        Text("执行恢复或切换前，App 会自动保存当前 user${state.settings.mainUserId} 数据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            itemsIndexed(
                passiveBackups,
                key = { _, backup -> "passive-${backup.packageName}-${backup.rollbackId}" },
            ) { index, backup ->
                PassiveBackupRow(
                    backup = backup,
                    app = appByPackage[backup.packageName],
                    onOpenDetail = { openPassiveBackup(backup) },
                    onRestore = { confirmRestore = backup },
                    shape = groupedRowShape(index, passiveBackups.lastIndex),
                    showDivider = index < passiveBackups.lastIndex,
                )
                if (index == passiveBackups.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        item {
            SectionLabel("分身回滚", "推送到分身前自动保存的分身侧最新备份。")
        }
        if (cloneRollbackBackups.isEmpty()) {
            item {
                SectionCard("暂无分身回滚") {
                    Text("执行“推送到分身”前会保存分身当前数据，方便撤回本次推送。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            itemsIndexed(
                cloneRollbackBackups,
                key = { _, backup -> "clone-${backup.packageName}-${backup.rollbackId}" },
            ) { index, backup ->
                PassiveBackupRow(
                    backup = backup,
                    app = appByPackage[backup.packageName],
                    onOpenDetail = null,
                    onRestore = { confirmCloneRestore = backup },
                    shape = groupedRowShape(index, cloneRollbackBackups.lastIndex),
                    showDivider = index < cloneRollbackBackups.lastIndex,
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
                    Text("将使用以下被动备份覆盖主系统 user${state.settings.mainUserId} 数据。")
                    SingleLinePathText("$rootDir/rollback/${backup.packageName}/${backup.rollbackId}")
                    Text(
                        when (backup.stateKind) {
                            PassiveBackupStateKind.MAIN -> "执行后主系统将标记为主数据 MAIN，可继续切换到分身态。"
                            PassiveBackupStateKind.CLONE -> "执行后主系统将标记为分数据 CLONE，可从主数据返回点还原。"
                            null -> "该备份没有可靠来源标签，执行后状态将标记为 UNKNOWN，请先核对数据来源。"
                        },
                    )
                    Text("来源: ${backup.reason}")
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "恢复",
                    onClick = {
                        confirmRestore = null
                        viewModel.restoreBackup(backup.packageName, backup.rollbackId)
                    },
                    primary = true,
                )
            },
            dismissButton = {
                DialogActionButton("取消", onClick = { confirmRestore = null })
            },
        )
    }

    confirmCloneRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmCloneRestore = null },
            title = { Text("恢复分身回滚") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将使用以下分身回滚覆盖分身 user${state.settings.cloneUserId} 数据。")
                    SingleLinePathText("$rootDir/clone_rollback/${backup.packageName}/${backup.rollbackId}")
                    Text("来源: ${backup.reason}")
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "恢复",
                    onClick = {
                        confirmCloneRestore = null
                        viewModel.restoreCloneRollback(backup.packageName)
                    },
                    primary = true,
                )
            },
            dismissButton = {
                DialogActionButton("取消", onClick = { confirmCloneRestore = null })
            },
        )
    }
}

private fun groupedRowShape(index: Int, lastIndex: Int): Shape = when {
    lastIndex <= 0 -> RoundedCornerShape(12.dp)
    index == 0 -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
    index == lastIndex -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    else -> RoundedCornerShape(0.dp)
}

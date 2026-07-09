package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.uclone.restore.util.Formatters

@Composable
fun DataBackupDetailScreen(
    state: UiState,
    viewModel: UCloneViewModel,
    modifier: Modifier,
    packageName: String?,
    rollbackId: String?,
    onBack: () -> Unit,
) {
    var confirm by remember(packageName, rollbackId) { mutableStateOf<DataBackupAction?>(null) }
    val rootDir = state.settings.rootDir
    val app = packageName?.let { pkg -> state.apps.firstOrNull { it.packageName == pkg } }
    val passiveBackup = if (packageName == null || rollbackId == null) {
        null
    } else {
        state.restoreBackups.firstOrNull { it.packageName == packageName && it.rollbackId == rollbackId }
    }
    val activePath = packageName?.let { "$rootDir/snapshots/$it/active" }
    val passivePath = if (packageName == null || rollbackId == null) {
        null
    } else {
        "$rootDir/rollback/$packageName/$rollbackId"
    }
    val isPassive = rollbackId != null
    val backupExists = if (isPassive) {
        passiveBackup != null
    } else {
        app?.lastSnapshotAt != null
    }

    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DataDetailHeader(
            title = if (isPassive) "被动备份详情" else "主动快照详情",
            subtitle = "这里只处理恢复和删除。",
            onBack = onBack,
        )
        if (packageName == null || !backupExists) {
            SectionCard("备份不存在") {
                Text("该备份可能已被删除，或不再是当前最新备份。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        SectionCard("备份信息") {
            InfoRow("App", app?.label ?: packageName)
            InfoRow("包名", packageName)
            InfoRow("类型", if (isPassive) "被动备份" else "主动快照")
            InfoRow("时间", Formatters.time(passiveBackup?.createdAt ?: app?.lastSnapshotAt))
            InfoRow("大小", Formatters.kilobytes(passiveBackup?.sizeKb ?: app?.snapshotSizeKb))
            if (passiveBackup != null) {
                InfoRow("来源", passiveBackup.reason)
            }
            Text("保存位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    passivePath ?: activePath.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SectionCard("操作") {
            IosPrimaryButton(
                onClick = { confirm = DataBackupAction.RESTORE },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Text(if (isPassive) "恢复被动备份" else "恢复到主系统")
            }
            IosSecondaryButton(
                onClick = { confirm = DataBackupAction.DELETE },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = IosRed)
                Text(if (isPassive) "删除被动备份" else "删除 active 快照", color = IosRed)
            }
        }
    }

    confirm?.let { action ->
        val path = passivePath ?: activePath.orEmpty()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    when (action) {
                        DataBackupAction.RESTORE -> if (isPassive) "恢复被动备份" else "恢复主动快照"
                        DataBackupAction.DELETE -> if (isPassive) "删除被动备份" else "删除 active 快照"
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        DataBackupAction.RESTORE -> "将使用 $path 覆盖主系统 user0 数据。"
                        DataBackupAction.DELETE -> if (isPassive) {
                            "将删除 $rootDir/rollback/$packageName 下该 App 的全部被动备份。主动快照不会被删除。"
                        } else {
                            "将删除 $path。删除后不能从这份快照恢复。"
                        }
                    },
                )
            },
            confirmButton = {
                IosDialogButton(
                    text = if (action == DataBackupAction.DELETE) "删除" else "继续",
                    onClick = {
                        confirm = null
                        val targetPackageName = packageName
                        if (targetPackageName != null) {
                            when (action) {
                                DataBackupAction.RESTORE -> {
                                    if (isPassive && rollbackId != null) {
                                        viewModel.restoreBackup(targetPackageName, rollbackId)
                                    } else {
                                        viewModel.restoreSnapshot(targetPackageName)
                                    }
                                }
                                DataBackupAction.DELETE -> {
                                    if (isPassive && rollbackId != null) {
                                        viewModel.deleteRestoreBackup(targetPackageName, rollbackId)
                                    } else {
                                        viewModel.deleteSnapshot(targetPackageName)
                                    }
                                    onBack()
                                }
                            }
                        }
                    },
                    primary = action != DataBackupAction.DELETE,
                    danger = action == DataBackupAction.DELETE,
                )
            },
            dismissButton = {
                IosDialogButton("取消", onClick = { confirm = null })
            },
        )
    }
}

private enum class DataBackupAction { RESTORE, DELETE }

@Composable
private fun DataDetailHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IosGlassIconButton(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            tint = IosText,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

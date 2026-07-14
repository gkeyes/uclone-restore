package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uclone.restore.util.Formatters

@Composable
fun DataBackupDetailScreen(
    state: UiState,
    viewModel: UCloneViewModel,
    modifier: Modifier,
    packageName: String?,
    rollbackId: String?,
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageDescription(
            if (isPassive) {
                "查看指定被动备份，并决定是否用它覆盖主系统 user${state.settings.mainUserId}。"
            } else {
                "查看 active 主动快照，并决定是否用它覆盖主系统 user${state.settings.mainUserId}。"
            },
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
            SingleLinePathText(passivePath ?: activePath.orEmpty())
        }
        SectionCard("备份操作") {
            ToolRow(
                title = if (isPassive) "用指定被动备份覆盖 user${state.settings.mainUserId}" else "用 active 快照覆盖 user${state.settings.mainUserId}",
                description = "将先建立本次专用回滚，再覆盖主系统中的 App 数据。",
                actionLabel = "恢复",
                icon = Icons.Default.RestartAlt,
                onClick = { confirm = DataBackupAction.RESTORE },
                primary = true,
            )
            ToolRow(
                title = if (isPassive) "删除这份被动备份" else "删除 active 主动快照",
                description = if (isPassive) "只删除当前备份 ID，不影响其他被动备份或主动快照。" else "删除后不能再从这份 active 快照恢复。",
                actionLabel = "删除",
                icon = Icons.Default.Delete,
                onClick = { confirm = DataBackupAction.DELETE },
                danger = true,
                showDivider = false,
            )
        }
    }

    confirm?.let { action ->
        val path = passivePath ?: activePath.orEmpty()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    when (action) {
                        DataBackupAction.RESTORE -> if (isPassive) "用指定被动备份覆盖 user${state.settings.mainUserId}" else "用 active 快照覆盖 user${state.settings.mainUserId}"
                        DataBackupAction.DELETE -> if (isPassive) "删除被动备份" else "删除 active 快照"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (action) {
                        DataBackupAction.RESTORE -> {
                            Text("将使用以下备份覆盖主系统 user${state.settings.mainUserId} 数据。")
                            SingleLinePathText(path)
                        }
                        DataBackupAction.DELETE -> if (isPassive) {
                            Text("只删除下面这份被动备份，其他被动备份和主动快照不受影响。")
                            SingleLinePathText(path)
                        } else {
                            Text("将删除以下快照。删除后不能从这份快照恢复。")
                            SingleLinePathText(path)
                        }
                    }
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = if (action == DataBackupAction.DELETE) "删除" else "继续",
                    onClick = {
                        confirm = null
                        val targetPackageName = packageName
                        if (targetPackageName != null) {
                            when (action) {
                                DataBackupAction.RESTORE -> {
                                    rollbackId?.let { viewModel.restoreBackup(targetPackageName, it) }
                                        ?: viewModel.restoreSnapshot(targetPackageName)
                                }
                                DataBackupAction.DELETE -> {
                                    rollbackId?.let { viewModel.deleteRestoreBackup(targetPackageName, it) }
                                        ?: viewModel.deleteSnapshot(targetPackageName)
                                }
                            }
                        }
                    },
                    primary = action != DataBackupAction.DELETE,
                    danger = action == DataBackupAction.DELETE,
                )
            },
            dismissButton = {
                DialogActionButton("取消", onClick = { confirm = null })
            },
        )
    }
}

private enum class DataBackupAction { RESTORE, DELETE }

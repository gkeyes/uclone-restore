package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.TaskType
import com.uclone.restore.util.Formatters

@Composable
fun HistoryScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("历史", "只查看任务记录；备份集中在“数据”页。")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.history, key = { it.id }) { task ->
                SectionCard(task.packageName) {
                    InfoRow("类型", task.type.userFacingLabel)
                    InfoRow("开始", Formatters.time(task.startedAt))
                    InfoRow("结束", Formatters.time(task.finishedAt))
                    val statusColor = when (task.status) {
                        TaskStatus.FAILED,
                        TaskStatus.FAILED_FATAL,
                        TaskStatus.RECOVERY_REQUIRED,
                        TaskStatus.INTERRUPTED,
                        -> MaterialTheme.colorScheme.error

                        TaskStatus.ACCEPTED,
                        TaskStatus.RUNNING,
                        TaskStatus.AUTO_ROLLING_BACK,
                        TaskStatus.SUCCESS_WITH_WARNINGS,
                        -> MaterialTheme.colorScheme.tertiary

                        TaskStatus.ROLLED_BACK,
                        TaskStatus.SUCCESS,
                        -> MaterialTheme.colorScheme.primary
                    }
                    InfoRow("状态", task.status.userFacingLabel, statusColor)
                    InfoRow("结果", task.message)
                    if (
                        task.type == TaskType.DELETE_SNAPSHOT ||
                        task.type == TaskType.DELETE_RESTORE_BACKUP ||
                        task.type == TaskType.DELETE_CLONE_ROLLBACK
                    ) {
                        InfoRow("调用来源", task.audit.source.userFacingTaskSource())
                        InfoRow(
                            "备份类型",
                            when (task.audit.backupKind) {
                                BackupKind.ACTIVE_SNAPSHOT -> "主动快照"
                                BackupKind.PASSIVE_BACKUP -> "指定被动备份"
                                BackupKind.CLONE_ROLLBACK -> "分身回滚"
                                BackupKind.WORKSPACE -> "工作区"
                                null -> "未记录"
                            },
                        )
                        task.audit.backupId?.let { InfoRow("备份 ID", it) }
                        task.audit.sizeKb?.let { InfoRow("删除前大小", Formatters.kilobytes(it)) }
                        task.audit.path?.let {
                            Text("实际路径", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SingleLinePathText(it)
                        }
                    }
                    Text("日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleLinePathText(task.logPath)
                    if (task.packageName != state.selectedPackage) {
                        IosSecondaryButton(onClick = { viewModel.selectPackage(task.packageName) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Apps, contentDescription = null)
                            Text("选择此 App")
                        }
                    }
                }
            }
        }
    }
}

private fun String.userFacingTaskSource(): String = when (this) {
    "app" -> "主 App"
    "launcher_shortcut" -> "桌面快捷方式"
    "launcher_module", "module" -> "LSPosed 模块"
    else -> ifBlank { "未知" }
}

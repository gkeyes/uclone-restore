package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.uclone.restore.util.Formatters
import java.util.Locale

@Composable
fun HistoryScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageDescription("这里记录已接受的业务任务；备份文件仍集中在“数据”页管理。")
        if (state.history.isEmpty()) {
            SectionCard("暂无任务记录") {
                Text("执行切换、推送、恢复或维护任务后，结果会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.history, key = { it.id }) { task ->
                SectionCard(task.packageName.ifBlank { "系统任务" }) {
                    InfoRow("任务", task.type.displayName)
                    InfoRow("开始", Formatters.time(task.startedAt))
                    InfoRow("结束", Formatters.time(task.finishedAt))
                    val statusColor = when (task.status) {
                        TaskStatus.FAILED,
                        TaskStatus.FAILED_FATAL,
                        TaskStatus.INTERRUPTED,
                        -> MaterialTheme.colorScheme.error

                        TaskStatus.SUCCESS_WITH_WARNINGS,
                        -> MaterialTheme.ucloneColors.warning

                        TaskStatus.ROLLED_BACK,
                        TaskStatus.SUCCESS,
                        -> MaterialTheme.ucloneColors.success

                        TaskStatus.ACCEPTED,
                        TaskStatus.RUNNING,
                        TaskStatus.AUTO_ROLLING_BACK,
                        -> MaterialTheme.colorScheme.primary
                    }
                    StatusBadge(task.status.displayName, statusColor)
                    task.finishedAt?.let { finishedAt ->
                        InfoRow("耗时", formatDuration(finishedAt - task.startedAt))
                    }
                    if (task.metrics.copiedFiles > 0L || task.metrics.copiedBytes > 0L) {
                        InfoRow("复制", "${task.metrics.copiedFiles} 个文件 · ${Formatters.bytes(task.metrics.copiedBytes)}")
                    }
                    task.metrics.targetDowntimeMs?.let { downtime ->
                        InfoRow("目标 App 停机", formatDuration(downtime))
                    }
                    InfoRow("结果", task.message)
                    Text("日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleLinePathText(task.logPath)
                    if (task.packageName != state.selectedPackage) {
                        ToolRow(
                            title = "在 App 页面选择此目标",
                            description = "将 ${task.packageName} 设为当前 App，不执行任何数据操作。",
                            actionLabel = "选择",
                            icon = Icons.Default.Apps,
                            onClick = { viewModel.selectPackage(task.packageName) },
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000.0
    return if (seconds < 60) {
        String.format(Locale.US, "%.1f 秒", seconds)
    } else {
        val wholeSeconds = seconds.toLong()
        "${wholeSeconds / 60} 分 ${wholeSeconds % 60} 秒"
    }
}

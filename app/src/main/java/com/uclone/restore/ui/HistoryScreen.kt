package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.util.Formatters
import java.util.Locale

@Composable
fun HistoryScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    var expandedTaskId by remember { mutableStateOf<Long?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                start = 16.dp,
                top = LocalTopBarContentPadding.current,
                end = 16.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageDescription("已接受的业务任务与执行结果")
        if (state.history.isEmpty()) {
            SectionCard("暂无任务记录") {
                Text("执行切换、推送、恢复或维护任务后，结果会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = LocalBottomBarContentPadding.current),
        ) {
            itemsIndexed(state.history, key = { _, task -> task.id }) { index, task ->
                HistoryTaskRow(
                    task = task,
                    expanded = expandedTaskId == task.id,
                    first = index == 0,
                    last = index == state.history.lastIndex,
                    showDivider = index < state.history.lastIndex,
                    selectedPackage = state.selectedPackage,
                    onToggle = {
                        expandedTaskId = if (expandedTaskId == task.id) null else task.id
                    },
                    onSelectPackage = { viewModel.selectPackage(task.packageName) },
                )
            }
        }
    }
}

@Composable
private fun HistoryTaskRow(
    task: TaskRecord,
    expanded: Boolean,
    first: Boolean,
    last: Boolean,
    showDivider: Boolean,
    selectedPackage: String?,
    onToggle: () -> Unit,
    onSelectPackage: () -> Unit,
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.ucloneColors.groupedSurface,
        shape = when {
            first && last -> MaterialTheme.shapes.medium
            first -> RoundedCornerShape(
                topStart = UCloneGroupedCornerRadius,
                topEnd = UCloneGroupedCornerRadius,
            )
            last -> RoundedCornerShape(
                bottomStart = UCloneGroupedCornerRadius,
                bottomEnd = UCloneGroupedCornerRadius,
            )
            else -> RoundedCornerShape(0.dp)
        },
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        task.packageName.ifBlank { "系统任务" },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        task.type.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        Formatters.time(task.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusBadge(task.status.displayName, task.status.historyColor())
                    task.finishedAt?.let {
                        Text(
                            formatDuration(it - task.startedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.42f))
                Column(
                    Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    InfoRow("开始", Formatters.time(task.startedAt))
                    InfoRow("结束", Formatters.time(task.finishedAt))
                    if (task.metrics.copiedFiles > 0L || task.metrics.copiedBytes > 0L) {
                        InfoRow("复制", "${task.metrics.copiedFiles} 个文件 · ${Formatters.bytes(task.metrics.copiedBytes)}")
                    }
                    task.metrics.targetDowntimeMs?.let { InfoRow("目标 App 停机", formatDuration(it)) }
                    InfoRow("结果", task.message)
                    Text("日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SingleLinePathText(task.logPath)
                    if (task.packageName.isNotBlank() && task.packageName != selectedPackage) {
                        ToolRow(
                            title = "在 App 页面选择此目标",
                            description = "将 ${task.packageName} 设为当前 App，不执行任何数据操作。",
                            actionLabel = "选择",
                            icon = Icons.Default.Apps,
                            onClick = onSelectPackage,
                            showDivider = false,
                        )
                    }
                }
            }
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 14.dp),
                    color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.42f),
                )
            }
        }
    }
}

@Composable
private fun TaskStatus.historyColor(): Color = when (this) {
    TaskStatus.FAILED,
    TaskStatus.FAILED_FATAL,
    TaskStatus.INTERRUPTED,
    -> MaterialTheme.colorScheme.error

    TaskStatus.SUCCESS_WITH_WARNINGS -> MaterialTheme.ucloneColors.warning
    TaskStatus.ROLLED_BACK,
    TaskStatus.SUCCESS,
    -> MaterialTheme.ucloneColors.success

    TaskStatus.ACCEPTED,
    TaskStatus.RUNNING,
    TaskStatus.AUTO_ROLLING_BACK,
    -> MaterialTheme.colorScheme.primary
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

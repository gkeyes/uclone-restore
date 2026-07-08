package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.util.Formatters

@Composable
fun HistoryScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader("历史", "查看任务记录，并从恢复前备份回滚。")
        if (state.selectedPackage != null && state.rollbackIds.isNotEmpty()) {
            SectionCard("可用回滚点") {
                state.rollbackIds.take(8).forEach { id ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(id, Modifier.weight(1f))
                        IosSecondaryButton(onClick = { viewModel.rollbackSelected(id) }) { Text("回滚") }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.history, key = { it.id }) { task ->
                SectionCard(task.packageName) {
                    InfoRow("类型", task.type.name)
                    InfoRow("开始", Formatters.time(task.startedAt))
                    InfoRow("结束", Formatters.time(task.finishedAt))
                    InfoRow("状态", task.status.name, if (task.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    InfoRow("日志", task.logPath)
                    if (task.packageName != state.selectedPackage) {
                        IosSecondaryButton(onClick = { viewModel.selectPackage(task.packageName) }, modifier = Modifier.fillMaxWidth()) { Text("选择此 App") }
                    }
                }
            }
        }
    }
}

package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.util.Formatters

@Composable
fun HomeScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, openDetail: () -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("UClone Restore", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("主系统控制端，读取分身快照并恢复到 user${state.settings.mainUserId}")
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
                InfoRow("快照目录", if (env?.snapshotDirReady?.ok == true) "可用" else "待检测")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::refreshEnvironment) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text("环境检测")
                    }
                    OutlinedButton(onClick = viewModel::startCloneUser) { Text("启动分身") }
                    OutlinedButton(onClick = viewModel::stopCloneUser) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Text("关闭")
                    }
                }
            }
        }
        item {
            SectionCard("快捷恢复") {
                Text("选择已有快照的 App 后，可直接恢复到主系统。")
            }
        }
        items(state.apps.filter { it.lastSnapshotAt != null }.take(5)) { app ->
            SectionCard(app.label) {
                InfoRow("包名", app.packageName)
                InfoRow("快照", Formatters.time(app.lastSnapshotAt))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.selectPackage(app.packageName)
                        openDetail()
                    }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Text("打开详情")
                    }
                }
            }
        }
        if (state.message != null) {
            item {
                Text(state.message, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

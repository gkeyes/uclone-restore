package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    val env = state.environment
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("诊断", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard("Root 与用户") {
            InfoRow("Root", if (env?.root?.ok == true) "uid=0" else "不可用")
            InfoRow("当前用户", env?.currentUser ?: "未检测")
            InfoRow("user${state.settings.mainUserId}", if (env?.user0Present == true) "存在" else "未确认")
            InfoRow("user${state.settings.cloneUserId}", if (env?.user10Present == true) "存在" else "未确认")
            InfoRow("user${state.settings.cloneUserId} 状态", env?.user10State ?: "未检测")
        }
        SectionCard("目录") {
            InfoRow("/data/adb/uclone 可写", if (env?.dataAdbWritable?.ok == true) "正常" else "失败")
            InfoRow("快照目录", if (env?.snapshotDirReady?.ok == true) "已准备" else "未准备")
        }
        SectionCard("操作") {
            Button(onClick = viewModel::refreshEnvironment) { Text("重新检测") }
            Button(onClick = viewModel::startCloneUser) { Text("启动分身 user${state.settings.cloneUserId}") }
            Button(onClick = viewModel::switchToCloneUser) { Text("切换到分身解锁") }
        }
        if (state.message != null) {
            Text(state.message, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

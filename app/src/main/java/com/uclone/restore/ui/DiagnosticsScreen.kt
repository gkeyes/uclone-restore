package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DiagnosticsScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    val env = state.environment
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("诊断", "确认 root、用户和工作目录是否满足恢复条件。")
        SectionCard("Root 与用户") {
            InfoRow("Root", if (env?.root?.ok == true) "uid=0" else "不可用")
            InfoRow("当前用户", env?.currentUser ?: "未检测")
            InfoRow("user${state.settings.mainUserId}", if (env?.user0Present == true) "存在" else "未确认")
            InfoRow("user${state.settings.cloneUserId}", if (env?.user10Present == true) "存在" else "未确认")
            InfoRow("user${state.settings.cloneUserId} 状态", env?.user10State ?: "未检测")
            InfoRow("CE gate", env?.user10CeState?.label ?: "未检测")
        }
        SectionCard("目录") {
            InfoRow("/data/adb/uclone 可写", if (env?.dataAdbWritable?.ok == true) "正常" else "失败")
            InfoRow("快照目录", if (env?.snapshotDirReady?.ok == true) "已准备" else "未准备")
            InfoRow("CE 基础目录", env?.user10CeBaseReadable?.detail ?: "未检测")
            InfoRow("DE 基础目录", env?.user10DeBaseReadable?.detail ?: "未检测")
        }
        SectionCard("操作") {
            IosPrimaryButton(onClick = viewModel::refreshEnvironment, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("重新检测")
            }
            IosPrimaryButton(onClick = viewModel::probeCloneCe, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Text("检测 CE 状态")
            }
            IosPrimaryButton(onClick = viewModel::debugCloneSystem, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text("分身系统调试")
            }
            IosPrimaryButton(
                onClick = viewModel::unlockCloneWithCredential,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null)
                Text("无感启动分身")
            }
            IosSecondaryButton(onClick = viewModel::startCloneUser, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Text("启动分身 user${state.settings.cloneUserId}")
            }
            IosSecondaryButton(onClick = viewModel::switchToCloneUser, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text("切换到分身解锁")
            }
        }
    }
}

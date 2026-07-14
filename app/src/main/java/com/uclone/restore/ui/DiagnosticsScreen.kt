package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageDescription("先看结论，再按需执行只读检测或分身生命周期操作。")
        val ready = env?.root?.ok == true &&
            env.user0Present &&
            env.user10Present &&
            env.dataAdbWritable.ok &&
            env.snapshotDirReady.ok
        SectionCard("诊断结论") {
            StatusChip(
                ok = ready,
                label = when {
                    env == null -> "尚未检测"
                    ready -> "基础环境正常"
                    else -> "存在需要处理的项目"
                },
            )
            Text(
                when {
                    env == null -> "执行重新检测后，这里会汇总 Root、用户和工作区状态。"
                    ready -> "Root、目标用户和 UClone 工作区已满足基础条件。"
                    else -> "请查看下方失败项；只读检测不会修改 App 数据。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("Root 与用户") {
            InfoRow("Root", if (env?.root?.ok == true) "uid=0" else "不可用")
            InfoRow("当前用户", env?.currentUser ?: "未检测")
            InfoRow("user${state.settings.mainUserId}", if (env?.user0Present == true) "存在" else "未确认")
            InfoRow("user${state.settings.cloneUserId}", if (env?.user10Present == true) "存在" else "未确认")
            InfoRow("user${state.settings.cloneUserId} 状态", env?.user10State ?: "未检测")
            InfoRow("分身数据解锁", env?.user10CeState?.userFacingLabel ?: "未检测")
        }
        SectionCard("目录") {
            InfoRow("/data/adb/uclone 可写", if (env?.dataAdbWritable?.ok == true) "正常" else "失败")
            InfoRow("快照目录", if (env?.snapshotDirReady?.ok == true) "已准备" else "未准备")
            InfoRow("CE 基础目录", env?.user10CeBaseReadable?.detail ?: "未检测")
            InfoRow("DE 基础目录", env?.user10DeBaseReadable?.detail ?: "未检测")
        }
        SectionCard("只读检测") {
            ToolRow(
                title = "重新检测环境",
                description = "重新读取 Root、用户和工作区状态。",
                actionLabel = "检测",
                icon = Icons.Default.Refresh,
                primary = true,
                onClick = viewModel::refreshEnvironment,
            )
            ToolRow(
                title = "检测分身 CE 状态",
                description = "确认 user${state.settings.cloneUserId} 私有数据是否可读。",
                actionLabel = "检测",
                icon = Icons.Default.LockOpen,
                onClick = viewModel::probeCloneCe,
            )
            ToolRow(
                title = "生成分身系统调试日志",
                description = "采集用户状态和目录诊断信息，不执行数据切换。",
                actionLabel = "执行",
                icon = Icons.Default.Sync,
                onClick = viewModel::debugCloneSystem,
                showDivider = false,
            )
        }
        SectionCard("分身系统控制") {
            ToolRow(
                title = "无感启动并解锁分身",
                description = "使用已保存凭据启动并解锁 user${state.settings.cloneUserId}；完成后保持运行。",
                actionLabel = "启动",
                icon = Icons.Default.LockOpen,
                onClick = viewModel::unlockCloneWithCredential,
            )
            ToolRow(
                title = "仅启动分身用户",
                description = "启动 user${state.settings.cloneUserId}，不输入凭据，也不切换前台用户。",
                actionLabel = "启动",
                icon = Icons.Default.PlayArrow,
                onClick = viewModel::startCloneUser,
            )
            ToolRow(
                title = "切换到分身系统解锁",
                description = "把设备前台切换到 user${state.settings.cloneUserId}，由用户完成交互解锁。",
                actionLabel = "切换",
                icon = Icons.Default.Sync,
                onClick = viewModel::switchToCloneUser,
                showDivider = false,
            )
        }
    }
}

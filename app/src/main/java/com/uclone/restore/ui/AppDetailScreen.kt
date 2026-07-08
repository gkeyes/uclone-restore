package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.util.Formatters

@Composable
fun AppDetailScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    val app = state.selectedApp
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (app == null) {
            Text("请先在 App 列表选择一个目标。")
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(app.packageName)
            Column {
                Text(app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(app.packageName)
            }
        }
        SectionCard("安装状态") {
            InfoRow("主系统 user${state.settings.mainUserId}", if (app.user0Installed) "已安装 UID ${app.user0Uid}" else "未安装")
            InfoRow("分身 user${state.settings.cloneUserId}", if (app.user10Installed) "已安装 UID ${app.user10Uid}" else "未安装")
            RiskChip(app.riskLevel)
        }
        SectionCard("黄金快照") {
            InfoRow("状态", if (app.lastSnapshotAt == null) "未建立" else "已建立")
            InfoRow("时间", Formatters.time(app.lastSnapshotAt))
            Text("恢复到主系统只读取 active 快照，不会实时读取分身最新数据。")
        }
        SectionCard("数据范围") {
            SettingCheck("CE App 私有数据", state.settings.includeCe)
            SettingCheck("DE Device Protected 数据", state.settings.includeDe)
            SettingCheck("external Android/data", state.settings.includeExternal)
            SettingCheck("Android/media", state.settings.includeMedia)
            SettingCheck("OBB", state.settings.includeObb)
            SettingCheck("排除 cache/code_cache", state.settings.excludeCache)
        }
        SectionCard("操作") {
            Button(onClick = { confirm = ConfirmAction.CAPTURE }) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Text("更新分身快照")
            }
            Button(onClick = { confirm = ConfirmAction.RESTORE }) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Text("恢复到主系统")
            }
            OutlinedButton(onClick = { confirm = ConfirmAction.LATEST }) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text("从分身最新恢复")
            }
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            highRisk = app.riskLevel != RiskLevel.NORMAL,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (action) {
                    ConfirmAction.CAPTURE -> viewModel.captureSelected()
                    ConfirmAction.RESTORE -> viewModel.restoreSelected()
                    ConfirmAction.LATEST -> viewModel.restoreLatestSelected()
                }
            },
        )
    }
}

@Composable
private fun SettingCheck(label: String, checked: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label)
    }
}

private enum class ConfirmAction { CAPTURE, RESTORE, LATEST }

@Composable
private fun ConfirmDialog(action: ConfirmAction, highRisk: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val title = when (action) {
        ConfirmAction.CAPTURE -> "更新分身快照"
        ConfirmAction.RESTORE -> "恢复到主系统"
        ConfirmAction.LATEST -> "从分身最新恢复"
    }
    val body = when (action) {
        ConfirmAction.CAPTURE -> "将读取分身系统当前最新数据，并覆盖 active 快照。旧快照会移动到 history。"
        ConfirmAction.RESTORE -> "将使用已保存的黄金快照恢复主系统数据。这不会重新读取分身最新数据。"
        ConfirmAction.LATEST -> "将先更新分身快照，再恢复到主系统。该动作会覆盖主系统当前 App 数据。"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(if (highRisk) "$body\n\n该 App 可能使用 Keystore 或服务端风控，请确认风险。" else body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("继续") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

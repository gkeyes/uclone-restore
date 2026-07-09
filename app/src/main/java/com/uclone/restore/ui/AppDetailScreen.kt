package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
fun AppDetailScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, onBack: () -> Unit) {
    val app = state.selectedApp
    var confirm by remember { mutableStateOf<ConfirmAction?>(null) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (app == null) {
            DetailHeader("详情", "请先在 App 列表选择一个目标。", onBack)
            return@Column
        }
        DetailHeader("App 详情", "建立主动备份，或恢复到主系统。", onBack)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(app.packageName)
            }
            val favorite = app.packageName in state.settings.favoritePackages
            IosGlassIconButton(
                imageVector = if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                onClick = { viewModel.toggleFavorite(app.packageName) },
                tint = if (favorite) IosOrange else IosTertiaryText,
                selected = favorite,
            )
        }
        SectionCard("安装状态") {
            InfoRow("主系统 user${state.settings.mainUserId}", if (app.user0Installed) "已安装 UID ${app.user0Uid}" else "未安装")
            InfoRow("分身 user${state.settings.cloneUserId}", if (app.user10Installed) "已安装 UID ${app.user10Uid}" else "未安装")
            RiskChip(app.riskLevel)
        }
        SectionCard("主动备份") {
            InfoRow("状态", if (app.lastSnapshotAt == null) "未建立" else "已建立")
            InfoRow("时间", Formatters.time(app.lastSnapshotAt))
            InfoRow("大小", Formatters.kilobytes(app.snapshotSizeKb))
            Text("保存位置", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleLinePathText("${state.settings.rootDir}/snapshots/${app.packageName}/active")
            Text("恢复到主系统只读取 active 主动备份，不会实时读取分身最新数据。")
        }
        SectionCard("数据范围") {
            SettingCheck("CE App 私有数据", state.settings.includeCe) {
                viewModel.saveSettings(state.settings.copy(includeCe = it))
            }
            SettingCheck("DE Device Protected 数据", state.settings.includeDe) {
                viewModel.saveSettings(state.settings.copy(includeDe = it))
            }
            SettingCheck("external Android/data", state.settings.includeExternal) {
                viewModel.saveSettings(state.settings.copy(includeExternal = it))
            }
            SettingCheck("Android/media", state.settings.includeMedia) {
                viewModel.saveSettings(state.settings.copy(includeMedia = it))
            }
            SettingCheck("OBB", state.settings.includeObb) {
                viewModel.saveSettings(state.settings.copy(includeObb = it))
            }
            SettingCheck("权限/AppOps", state.settings.includePermissions) {
                viewModel.saveSettings(state.settings.copy(includePermissions = it))
            }
            SettingCheck("排除 cache/code_cache", state.settings.excludeCache) {
                viewModel.saveSettings(state.settings.copy(excludeCache = it))
            }
        }
        val task = state.currentTask.task
        if (task?.packageName == app.packageName && (state.busy || state.currentTask.steps.isNotEmpty())) {
            SectionCard("任务进度") {
                Text(task.type.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.currentTask.steps.forEach { step ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        StepIcon(step.status)
                        Text(step.label)
                    }
                }
            }
        }
        SectionCard("操作") {
            IosPrimaryButton(
                onClick = {
                    confirm = if (state.selectedSwitchRollbackId == null) {
                        ConfirmAction.SWITCH
                    } else {
                        ConfirmAction.RESTORE_SWITCH
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text(if (state.selectedSwitchRollbackId == null) "切换到分身态" else "还原主系统态")
            }
            IosPrimaryButton(onClick = { confirm = ConfirmAction.CAPTURE }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Text("建立主动备份")
            }
            IosPrimaryButton(onClick = { confirm = ConfirmAction.RESTORE }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Text("恢复到主系统")
            }
            IosSecondaryButton(onClick = { confirm = ConfirmAction.LATEST }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text("备份并恢复到主系统")
            }
            IosSecondaryButton(onClick = { confirm = ConfirmAction.AUDIT }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Text("生成恢复审计包")
            }
            IosSecondaryButton(
                onClick = { confirm = ConfirmAction.DELETE },
                modifier = Modifier.fillMaxWidth(),
                enabled = app.lastSnapshotAt != null,
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = IosRed)
                Text("删除 active 快照", color = IosRed)
            }
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            highRisk = state.selectedApp?.riskLevel != RiskLevel.NORMAL,
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (action) {
                    ConfirmAction.SWITCH -> viewModel.switchToCloneStateSelected()
                    ConfirmAction.RESTORE_SWITCH -> viewModel.restoreSwitchMainStateSelected()
                    ConfirmAction.CAPTURE -> viewModel.captureSelected()
                    ConfirmAction.RESTORE -> viewModel.restoreSelected()
                    ConfirmAction.LATEST -> viewModel.restoreLatestSelected()
                    ConfirmAction.AUDIT -> viewModel.auditRestoreConsistencySelected()
                    ConfirmAction.DELETE -> viewModel.deleteSnapshotSelected()
                }
            },
        )
    }
}

@Composable
private fun DetailHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IosGlassIconButton(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "返回",
            onClick = onBack,
            tint = IosText,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = IosGroup,
                checkedTrackColor = IosGreen,
                uncheckedThumbColor = IosGroup,
                uncheckedTrackColor = IosSeparator,
                uncheckedBorderColor = IosSeparator,
            ),
        )
    }
}

private enum class ConfirmAction { SWITCH, RESTORE_SWITCH, CAPTURE, RESTORE, LATEST, AUDIT, DELETE }

@Composable
private fun ConfirmDialog(action: ConfirmAction, highRisk: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val title = when (action) {
        ConfirmAction.SWITCH -> "切换到分身态"
        ConfirmAction.RESTORE_SWITCH -> "还原主系统态"
        ConfirmAction.CAPTURE -> "建立主动备份"
        ConfirmAction.RESTORE -> "恢复到主系统"
        ConfirmAction.LATEST -> "备份并恢复到主系统"
        ConfirmAction.AUDIT -> "生成恢复审计包"
        ConfirmAction.DELETE -> "删除 active 快照"
    }
    val body = when (action) {
        ConfirmAction.SWITCH -> "会先把当前 user0 保存为被动备份，再读取分身最新状态恢复到 user0。完成后按钮会变为还原主系统态。"
        ConfirmAction.RESTORE_SWITCH -> "会使用切换前保存的 user0 被动备份还原主系统，并清除切换标记。"
        ConfirmAction.CAPTURE -> "将读取分身系统当前最新数据，并保存为 active 主动备份。旧 active 主动备份会移动到 history。"
        ConfirmAction.RESTORE -> "将使用已保存的 active 主动备份恢复主系统数据。这不会重新读取分身最新数据。"
        ConfirmAction.LATEST -> "将先更新分身主动备份，再恢复到主系统。该动作会覆盖主系统当前 App 数据。"
        ConfirmAction.AUDIT -> "会只读采集文件树、UID、SELinux、权限和 AppOps 证据，写入审计目录；不会恢复、不会删除，也不会执行 restorecon。"
        ConfirmAction.DELETE -> "将删除当前 App 的 active 快照。history 和被动备份不会被删除。删除后无法直接恢复该 active 快照。"
    }
    val text = if (highRisk && action != ConfirmAction.DELETE && action != ConfirmAction.AUDIT) {
        "$body\n\n该 App 可能使用 Keystore 或服务端风控，请确认风险。"
    } else {
        body
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            IosDialogButton(
                text = if (action == ConfirmAction.DELETE) "删除" else "继续",
                onClick = onConfirm,
                primary = action != ConfirmAction.DELETE,
                danger = action == ConfirmAction.DELETE,
            )
        },
        dismissButton = { IosDialogButton("取消", onDismiss) },
    )
}

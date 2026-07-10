package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
        DetailHeader("App 详情", "查看数据范围，并选择来源和目标明确的操作。", onBack)
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
            Text("“用 active 主动备份恢复主系统”只读取上述路径，不会读取分身当前数据。")
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
                Text(task.type.userFacingLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val switched = state.selectedSwitchRollbackId != null
        SectionCard("操作工具", glass = false) {
            AppToolRow(
                title = if (switched) "还原切换前的主系统状态" else "切换到分身当前登录状态",
                description = if (switched) {
                    "使用本次切换前自动生成的 user${state.settings.mainUserId} 被动备份恢复主系统，并结束切换状态。"
                } else {
                    "读取 user${state.settings.cloneUserId} 当前最新数据；先保存主系统被动备份，再覆盖 user${state.settings.mainUserId}。"
                },
                actionLabel = if (switched) "还原" else "切换",
                icon = if (switched) Icons.Outlined.Restore else Icons.Outlined.SwapHoriz,
                primary = !switched,
                enabled = !state.busy,
                onClick = { confirm = if (switched) ConfirmAction.RESTORE_SWITCH else ConfirmAction.SWITCH },
            )
            ToolDivider()
            AppToolRow(
                title = "保存分身当前数据为主动备份",
                description = "读取 user${state.settings.cloneUserId} 当前最新数据并写入 active；不会修改主系统 App 数据。",
                actionLabel = "建立",
                icon = Icons.Outlined.CloudUpload,
                enabled = !state.busy,
                onClick = { confirm = ConfirmAction.CAPTURE },
            )
            ToolDivider()
            AppToolRow(
                title = "用 active 主动备份恢复主系统",
                description = "只读取已保存的 active 并覆盖 user${state.settings.mainUserId}；不会读取分身当前数据。",
                actionLabel = "恢复",
                icon = Icons.Outlined.Restore,
                enabled = app.lastSnapshotAt != null && !state.busy,
                onClick = { confirm = ConfirmAction.RESTORE },
            )
            ToolDivider()
            AppToolRow(
                title = "读取分身最新数据并恢复主系统",
                description = "先用 user${state.settings.cloneUserId} 当前数据更新 active，再把 active 恢复到 user${state.settings.mainUserId}。",
                actionLabel = "执行",
                icon = Icons.Outlined.Sync,
                enabled = !state.busy,
                onClick = { confirm = ConfirmAction.LATEST },
            )
            ToolDivider()
            AppToolRow(
                title = "生成恢复一致性审计",
                description = "只读采集文件树、UID、SELinux、权限和 AppOps；不会恢复或删除数据。",
                actionLabel = "生成",
                icon = Icons.AutoMirrored.Outlined.FactCheck,
                enabled = !state.busy,
                onClick = { confirm = ConfirmAction.AUDIT },
            )
            if (switched) {
                ToolDivider()
                AppToolRow(
                    title = "重置切换状态",
                    description = "只清除首页的“还原”标记；不恢复、不删除任何 App 数据或备份。",
                    actionLabel = "重置",
                    icon = Icons.Outlined.RestartAlt,
                    warning = true,
                    enabled = !state.busy,
                    onClick = { confirm = ConfirmAction.RESET_SWITCH },
                )
            }
            if (app.lastSnapshotAt != null) {
                ToolDivider()
                AppToolRow(
                    title = "删除 active 主动备份",
                    description = "只删除当前 active；保留 history、被动备份和 App 数据。",
                    actionLabel = "删除",
                    icon = Icons.Outlined.DeleteOutline,
                    danger = true,
                    enabled = !state.busy,
                    onClick = { confirm = ConfirmAction.DELETE },
                )
            }
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            highRisk = state.selectedApp?.riskLevel != RiskLevel.NORMAL,
            mainUserId = state.settings.mainUserId,
            cloneUserId = state.settings.cloneUserId,
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
                    ConfirmAction.RESET_SWITCH -> viewModel.resetSwitchStateSelected()
                    ConfirmAction.DELETE -> viewModel.deleteSnapshotSelected()
                }
            },
        )
    }
}

@Composable
private fun AppToolRow(
    title: String,
    description: String,
    actionLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    warning: Boolean = false,
    danger: Boolean = false,
) {
    val accent = when {
        danger -> IosRed
        warning -> IosOrange
        primary -> IosBlue
        else -> IosText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accent else IosTertiaryText,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) IosText else IosTertiaryText,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = IosSecondaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IosCompactButton(
            text = actionLabel,
            onClick = onClick,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .widthIn(min = 64.dp),
            enabled = enabled,
            primary = primary,
            danger = danger,
            semanticTint = if (warning) IosOrange else null,
        )
    }
}

@Composable
private fun ToolDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 32.dp),
        color = IosSeparator.copy(alpha = 0.55f),
    )
}

@Composable
private fun DetailHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        IosGlassIconButton(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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

private enum class ConfirmAction { SWITCH, RESTORE_SWITCH, CAPTURE, RESTORE, LATEST, AUDIT, RESET_SWITCH, DELETE }

@Composable
private fun ConfirmDialog(
    action: ConfirmAction,
    highRisk: Boolean,
    mainUserId: Int,
    cloneUserId: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        ConfirmAction.SWITCH -> "切换到分身当前登录状态"
        ConfirmAction.RESTORE_SWITCH -> "还原切换前的主系统状态"
        ConfirmAction.CAPTURE -> "保存分身当前数据为主动备份"
        ConfirmAction.RESTORE -> "用 active 主动备份恢复主系统"
        ConfirmAction.LATEST -> "读取分身最新数据并恢复主系统"
        ConfirmAction.AUDIT -> "生成恢复一致性审计"
        ConfirmAction.RESET_SWITCH -> "重置切换状态"
        ConfirmAction.DELETE -> "删除 active 主动备份"
    }
    val body = when (action) {
        ConfirmAction.SWITCH -> "会先把主系统 user$mainUserId 当前数据保存为被动备份，再读取分身 user$cloneUserId 最新数据并覆盖 user$mainUserId。完成后按钮会变为“还原”。"
        ConfirmAction.RESTORE_SWITCH -> "会读取本次切换前自动生成的 user$mainUserId 被动备份，覆盖主系统当前数据并结束切换状态。"
        ConfirmAction.CAPTURE -> "会读取分身 user$cloneUserId 当前最新数据并更新 active；不会修改主系统 user$mainUserId。旧 active 会移动到 history。"
        ConfirmAction.RESTORE -> "会读取已保存的 active 主动备份并覆盖主系统 user$mainUserId；不会读取分身 user$cloneUserId 当前数据。"
        ConfirmAction.LATEST -> "会先读取分身 user$cloneUserId 最新数据更新 active，再用 active 覆盖主系统 user$mainUserId。"
        ConfirmAction.AUDIT -> "会只读采集文件树、UID、SELinux、权限和 AppOps 证据，写入审计目录；不会恢复、不会删除，也不会执行 restorecon。"
        ConfirmAction.RESET_SWITCH -> "只清除该 App 的切换状态标记，让首页操作恢复为“切换”。不会恢复或删除任何 App 数据、主动备份和被动备份。"
        ConfirmAction.DELETE -> "将删除当前 App 的 active 快照。history 和被动备份不会被删除。删除后无法直接恢复该 active 快照。"
    }
    val text = if (
        highRisk && action != ConfirmAction.DELETE && action != ConfirmAction.AUDIT && action != ConfirmAction.RESET_SWITCH
    ) {
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
                text = when (action) {
                    ConfirmAction.DELETE -> "删除"
                    ConfirmAction.RESET_SWITCH -> "重置"
                    else -> "继续"
                },
                onClick = onConfirm,
                primary = action != ConfirmAction.DELETE && action != ConfirmAction.RESET_SWITCH,
                danger = action == ConfirmAction.DELETE,
                semanticTint = if (action == ConfirmAction.RESET_SWITCH) IosOrange else null,
            )
        },
        dismissButton = { IosDialogButton("取消", onDismiss) },
    )
}

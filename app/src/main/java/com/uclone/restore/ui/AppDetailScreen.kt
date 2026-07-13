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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.sync.AppDataState
import com.uclone.restore.util.Formatters

@Composable
fun AppDetailScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier, onBack: () -> Unit) {
    val app = state.selectedApp
    val context = LocalContext.current
    val installTargetUserId = app?.let {
        when {
            it.user0Installed && !it.user10Installed -> state.settings.cloneUserId
            !it.user0Installed && it.user10Installed -> state.settings.mainUserId
            else -> null
        }
    }
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
        if (installTargetUserId != null && !app.isSystemApp && app.packageName != context.packageName) {
            val sourceUserId = if (installTargetUserId == state.settings.mainUserId) {
                state.settings.cloneUserId
            } else {
                state.settings.mainUserId
            }
            SectionCard("安装到另一侧") {
                Text(
                    "来源 user$sourceUserId，目标 user$installTargetUserId。复用设备现有 APK，不复制 /data/app。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InstallToolRow(
                    title = "仅安装 App",
                    description = "只在目标用户启用现有 APK，不读取或同步 App 数据。",
                    onClick = { confirm = ConfirmAction.INSTALL_ONLY },
                )
                InstallToolRow(
                    title = "安装并迁移权限",
                    description = "安装后尽力合并运行时权限和 AppOps；失败会保留安装结果并提示。",
                    onClick = { confirm = ConfirmAction.INSTALL_PERMISSIONS },
                )
                InstallToolRow(
                    title = "安装并同步数据",
                    description = "安装后把来源侧当前数据同步到目标侧；同步失败不会卸载 App。",
                    onClick = { confirm = ConfirmAction.INSTALL_SYNC },
                )
            }
        }
        SectionCard("当前数据状态") {
            InfoRow(
                "user${state.settings.mainUserId}",
                when (state.selectedDataState) {
                    AppDataState.Main -> "主系统数据 MAIN"
                    is AppDataState.Clone -> "分身数据 CLONE"
                    AppDataState.Unknown, null -> "状态未知 UNKNOWN"
                },
            )
            if (state.selectedDataState == AppDataState.Unknown) {
                Text("切换状态记录不完整。请从数据页恢复一份已标识来源的备份后再继续。")
            }
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
                    confirm = when (state.selectedDataState) {
                        AppDataState.Main -> ConfirmAction.SWITCH
                        is AppDataState.Clone -> ConfirmAction.RESTORE_SWITCH
                        AppDataState.Unknown, null -> null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedDataState != AppDataState.Unknown,
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Text(
                    when (state.selectedDataState) {
                        AppDataState.Main -> "切换到分身态"
                        is AppDataState.Clone -> "还原主系统态"
                        AppDataState.Unknown, null -> "请先确认数据状态"
                    },
                )
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
            installTargetLabel = installTargetUserId?.let { "user$it" },
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
                    ConfirmAction.INSTALL_ONLY -> viewModel.installSelectedToOtherUser(CrossUserInstallMode.INSTALL_ONLY)
                    ConfirmAction.INSTALL_PERMISSIONS ->
                        viewModel.installSelectedToOtherUser(CrossUserInstallMode.INSTALL_WITH_PERMISSIONS)
                    ConfirmAction.INSTALL_SYNC -> viewModel.installSelectedToOtherUser(CrossUserInstallMode.INSTALL_AND_SYNC)
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

@Composable
private fun InstallToolRow(title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IosCompactButton(text = "执行", onClick = onClick)
    }
}

private enum class ConfirmAction {
    SWITCH,
    RESTORE_SWITCH,
    CAPTURE,
    RESTORE,
    LATEST,
    AUDIT,
    DELETE,
    INSTALL_ONLY,
    INSTALL_PERMISSIONS,
    INSTALL_SYNC,
}

@Composable
private fun ConfirmDialog(
    action: ConfirmAction,
    highRisk: Boolean,
    installTargetLabel: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        ConfirmAction.SWITCH -> "切换到分身态"
        ConfirmAction.RESTORE_SWITCH -> "还原主系统态"
        ConfirmAction.CAPTURE -> "建立主动备份"
        ConfirmAction.RESTORE -> "恢复到主系统"
        ConfirmAction.LATEST -> "备份并恢复到主系统"
        ConfirmAction.AUDIT -> "生成恢复审计包"
        ConfirmAction.DELETE -> "删除 active 快照"
        ConfirmAction.INSTALL_ONLY -> "仅安装到${installTargetLabel ?: "另一侧"}"
        ConfirmAction.INSTALL_PERMISSIONS -> "安装并迁移权限"
        ConfirmAction.INSTALL_SYNC -> "安装并同步数据"
    }
    val body = when (action) {
        ConfirmAction.SWITCH -> "会先把当前 user0 保存为被动备份，再读取分身最新状态恢复到 user0。完成后按钮会变为还原主系统态。"
        ConfirmAction.RESTORE_SWITCH -> "会使用切换前保存的 user0 被动备份还原主系统，并清除切换标记。"
        ConfirmAction.CAPTURE -> "将读取分身系统当前最新数据，并保存为 active 主动备份。旧 active 主动备份会移动到 history。"
        ConfirmAction.RESTORE -> "将使用已保存的 active 主动备份恢复主系统数据。这不会重新读取分身最新数据。"
        ConfirmAction.LATEST -> "将先更新分身主动备份，再恢复到主系统。该动作会覆盖主系统当前 App 数据。"
        ConfirmAction.AUDIT -> "会只读采集文件树、UID、SELinux、权限和 AppOps 证据，写入审计目录；不会恢复、不会删除，也不会执行 restorecon。"
        ConfirmAction.DELETE -> "将删除当前 App 的 active 快照。history 和被动备份不会被删除。删除后无法直接恢复该 active 快照。"
        ConfirmAction.INSTALL_ONLY -> "只会在${installTargetLabel ?: "目标用户"}启用设备中已存在的同版本 APK，不会读取或修改两侧 App 数据。"
        ConfirmAction.INSTALL_PERMISSIONS -> "会先安装到${installTargetLabel ?: "目标用户"}，再以合并方式迁移可支持的权限和 AppOps。权限迁移失败不会撤销安装。"
        ConfirmAction.INSTALL_SYNC -> "会先安装到${installTargetLabel ?: "目标用户"}，再把来源侧当前 App 数据同步到目标侧。同步失败时 App 仍保持已安装，并以部分成功记录。"
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

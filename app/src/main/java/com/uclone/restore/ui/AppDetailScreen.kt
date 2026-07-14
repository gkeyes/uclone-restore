package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.SwitchSafetyMode
import com.uclone.restore.sync.AppDataState
import com.uclone.restore.util.Formatters

@Composable
fun AppDetailScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    val app = state.selectedApp
    val mainReturnPoint = state.restoreBackups.firstOrNull { backup ->
        backup.packageName == app?.packageName &&
            backup.rollbackId == "persistent_main" &&
            backup.stateKind == PassiveBackupStateKind.MAIN
    }
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
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (app == null) {
            PageDescription("请先在 App 页面选择一个目标。")
            return@Column
        }
        PageDescription("先确认安装和数据状态，再选择对应操作。")
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
            UtilityIconButton(
                imageVector = if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                onClick = { viewModel.toggleFavorite(app.packageName) },
                tint = if (favorite) MaterialTheme.ucloneColors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                framed = true,
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
            StatusBadge(
                label = when (state.selectedDataState) {
                    AppDataState.Main -> "MAIN · 主数据正在 user${state.settings.mainUserId} 使用"
                    is AppDataState.Clone -> "CLONE · 分数据正在 user${state.settings.mainUserId} 使用"
                    AppDataState.Unknown, null -> "UNKNOWN · 数据来源无法确认"
                },
                color = when (state.selectedDataState) {
                    AppDataState.Main -> MaterialTheme.ucloneColors.success
                    is AppDataState.Clone -> MaterialTheme.colorScheme.onPrimaryContainer
                    AppDataState.Unknown, null -> MaterialTheme.ucloneColors.warning
                },
            )
            if (state.selectedDataState == AppDataState.Unknown) {
                Text("切换状态记录不完整。请从数据页恢复一份已标识来源的备份后再继续。")
            }
        }
        SectionCard("固定 MAIN 返回点") {
            InfoRow("状态", if (mainReturnPoint == null) "未建立" else "已建立")
            InfoRow("时间", Formatters.time(mainReturnPoint?.createdAt))
            InfoRow("大小", Formatters.kilobytes(mainReturnPoint?.sizeKb))
            Text(
                "还原 MAIN 始终使用这一份固定数据。普通切换不会更新它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToolRow(
                title = "更新固定 MAIN 返回点",
                description = if (state.selectedPackage?.let(state::hasConfirmedMainState) == true) {
                    "用 user${state.settings.mainUserId} 当前已确认 MAIN 数据替换返回点。"
                } else {
                    "需要先完成一次受 UClone 记录的 MAIN 还原，确认当前数据来源后才能更新。"
                },
                actionLabel = "更新",
                icon = Icons.Default.RestartAlt,
                enabled = state.selectedDataState == AppDataState.Main &&
                    state.selectedPackage?.let(state::hasConfirmedMainState) == true &&
                    !state.busy,
                onClick = { confirm = ConfirmAction.UPDATE_MAIN_RETURN_POINT },
                showDivider = false,
            )
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
                Text(task.type.displayName, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        SectionCard("主要操作") {
            ToolRow(
                title = when (state.selectedDataState) {
                    AppDataState.Main -> "切换到分身态"
                    is AppDataState.Clone -> "还原主系统态"
                    AppDataState.Unknown, null -> "数据状态待确认"
                },
                description = when (state.selectedDataState) {
                    AppDataState.Main -> if (mainReturnPoint == null) {
                        "首次建立固定 MAIN 返回点，再从 user${state.settings.cloneUserId} 读取当前分数据。"
                    } else {
                        "保留固定 MAIN 返回点，从 user${state.settings.cloneUserId} 读取当前分数据。"
                    }
                    is AppDataState.Clone -> when (state.settings.switchSafetyMode) {
                        SwitchSafetyMode.SAFE -> "安全返回：保存 CLONE 检查点、同步 user${state.settings.cloneUserId}、恢复固定 MAIN，共 3 次完整写入。"
                        SwitchSafetyMode.DANGEROUS_FAST -> "危险快速返回：直接同步 user${state.settings.cloneUserId} 后恢复固定 MAIN，共 2 次完整写入，无本地 CLONE 检查点。"
                    }
                    AppDataState.Unknown, null -> "先恢复一份已标明 MAIN 或 CLONE 来源的备份。"
                },
                actionLabel = when (state.selectedDataState) {
                    AppDataState.Main -> "切换"
                    is AppDataState.Clone -> "还原"
                    AppDataState.Unknown, null -> "不可用"
                },
                icon = Icons.Default.Sync,
                onClick = {
                    confirm = when (state.selectedDataState) {
                        AppDataState.Main -> ConfirmAction.SWITCH
                        is AppDataState.Clone -> ConfirmAction.RESTORE_SWITCH
                        AppDataState.Unknown, null -> null
                    }
                },
                enabled = state.selectedDataState != AppDataState.Unknown,
                primary = state.selectedDataState != AppDataState.Unknown,
                showDivider = false,
            )
        }
        SectionCard("备份与恢复工具") {
            ToolRow(
                title = "建立主动备份",
                description = "读取分身当前数据，保存为 active 快照。",
                actionLabel = "执行",
                icon = Icons.Default.CloudDownload,
                onClick = { confirm = ConfirmAction.CAPTURE },
            )
            ToolRow(
                title = "用 active 快照覆盖 user${state.settings.mainUserId}",
                description = "只使用已保存快照，不重新读取分身最新数据。",
                actionLabel = "执行",
                icon = Icons.Default.RestartAlt,
                onClick = { confirm = ConfirmAction.RESTORE },
            )
            ToolRow(
                title = "读取分身最新数据并恢复 user${state.settings.mainUserId}",
                description = "先更新主动快照，再用新快照覆盖主系统数据。",
                actionLabel = "执行",
                icon = Icons.Default.Sync,
                onClick = { confirm = ConfirmAction.LATEST },
            )
            ToolRow(
                title = "生成恢复审计包",
                description = "只读采集文件、权限与上下文证据，不修改数据。",
                actionLabel = "执行",
                icon = Icons.Default.CloudDownload,
                onClick = { confirm = ConfirmAction.AUDIT },
                showDivider = false,
            )
        }
        SectionCard("危险操作") {
            ToolRow(
                title = "删除 active 主动快照",
                description = "删除当前 active；history 和被动备份不受影响。",
                actionLabel = "删除",
                icon = Icons.Default.Delete,
                enabled = app.lastSnapshotAt != null,
                danger = true,
                onClick = { confirm = ConfirmAction.DELETE },
                showDivider = false,
            )
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            highRisk = state.selectedApp?.riskLevel != RiskLevel.NORMAL,
            mainUserId = state.settings.mainUserId,
            cloneUserId = state.settings.cloneUserId,
            hasMainReturnPoint = mainReturnPoint != null,
            switchSafetyMode = state.settings.switchSafetyMode,
            installTargetLabel = installTargetUserId?.let { "user$it" },
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                when (action) {
                    ConfirmAction.SWITCH -> viewModel.switchToCloneStateSelected()
                    ConfirmAction.RESTORE_SWITCH -> viewModel.restoreSwitchMainStateSelected()
                    ConfirmAction.UPDATE_MAIN_RETURN_POINT -> viewModel.updateMainReturnPointSelected()
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
private fun SettingCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface)
        UCloneSwitch(
            checked = checked,
            onCheckedChange = onChange,
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
        InlineActionButton(text = "执行", onClick = onClick)
    }
}

private enum class ConfirmAction {
    SWITCH,
    RESTORE_SWITCH,
    UPDATE_MAIN_RETURN_POINT,
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
    mainUserId: Int,
    cloneUserId: Int,
    hasMainReturnPoint: Boolean,
    switchSafetyMode: SwitchSafetyMode,
    installTargetLabel: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        ConfirmAction.SWITCH -> "切换到分身态"
        ConfirmAction.RESTORE_SWITCH -> "还原主系统态"
        ConfirmAction.UPDATE_MAIN_RETURN_POINT -> "更新固定 MAIN 返回点"
        ConfirmAction.CAPTURE -> "建立主动备份"
        ConfirmAction.RESTORE -> "用 active 快照恢复 user$mainUserId"
        ConfirmAction.LATEST -> "读取分身最新数据并恢复 user$mainUserId"
        ConfirmAction.AUDIT -> "生成恢复审计包"
        ConfirmAction.DELETE -> "删除 active 快照"
        ConfirmAction.INSTALL_ONLY -> "仅安装到${installTargetLabel ?: "另一侧"}"
        ConfirmAction.INSTALL_PERMISSIONS -> "安装并迁移权限"
        ConfirmAction.INSTALL_SYNC -> "安装并同步数据"
    }
    val body = when (action) {
        ConfirmAction.SWITCH -> if (hasMainReturnPoint) {
            "会保留现有固定 MAIN 返回点，并从 user$cloneUserId 读取当前分数据覆盖 user$mainUserId。完成后按钮会变为还原主系统态。"
        } else {
            "会先把 user$mainUserId 当前 MAIN 数据建立为固定返回点，再从 user$cloneUserId 读取当前分数据覆盖 user$mainUserId。"
        }
        ConfirmAction.RESTORE_SWITCH -> when (switchSafetyMode) {
            SwitchSafetyMode.SAFE -> "安全模式，共 3 次完整写入：先保存 user$mainUserId 当前 CLONE 检查点，再同步到 user$cloneUserId，最后恢复固定 MAIN。同步失败不恢复 MAIN；MAIN 恢复失败可回滚检查点。"
            SwitchSafetyMode.DANGEROUS_FAST -> "危险快速返回，共 2 次完整写入：直接把 user$mainUserId 当前分数据同步到 user$cloneUserId，再恢复固定 MAIN。不建立本地 CLONE 检查点，MAIN 恢复失败时会进入未知状态。"
        }
        ConfirmAction.UPDATE_MAIN_RETURN_POINT -> "只会读取 UClone 已明确记录为 MAIN 的 user$mainUserId 当前数据，完整验证后替换固定返回点。普通切换不会自动执行此更新。"
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
            DialogActionButton(
                text = if (action == ConfirmAction.DELETE) "删除" else "继续",
                onClick = onConfirm,
                primary = action != ConfirmAction.DELETE,
                danger = action == ConfirmAction.DELETE,
            )
        },
        dismissButton = { DialogActionButton("取消", onDismiss) },
    )
}

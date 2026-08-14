package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.BackupEntry
import com.uclone.restore.model.CrossUserInstallMode
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.UCloneSettings
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
    var backupDialog by remember { mutableStateOf<BackupDialogKind?>(null) }
    var restoreDialogBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var showRestorePicker by remember { mutableStateOf(false) }
    var editBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var advancedExpanded by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                top = LocalTopBarContentPadding.current,
                end = 16.dp,
                bottom = LocalBottomBarContentPadding.current,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (app == null) {
            PageDescription("请先在 App 页面选择一个目标。")
            return@Column
        }
        PageDescription("安装状态、数据来源与操作")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName, size = 44.dp, cornerRadius = 12.dp)
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        AppDataSourcePanel(
            mainUserId = state.settings.mainUserId,
            dataState = state.selectedDataState,
        )
        SectionCard("备份工具") {
            ToolRow(
                title = "备份主系统",
                description = "读取当前 user${state.settings.mainUserId} 数据，生成一份独立命名备份。",
                actionLabel = "备份",
                icon = Icons.Default.CloudDownload,
                onClick = { backupDialog = BackupDialogKind.MAIN },
                primary = true,
                actionVariant = ToolRowActionVariant.Pill,
            )
            ToolRow(
                title = "备份分系统",
                description = "读取当前 user${state.settings.cloneUserId} 数据，生成一份独立命名备份。",
                actionLabel = "备份",
                icon = Icons.Default.CloudDownload,
                onClick = { backupDialog = BackupDialogKind.CLONE },
                primary = true,
                actionVariant = ToolRowActionVariant.Pill,
                showDivider = false,
            )
        }
        SectionCard("恢复工具") {
            ToolRow(
                title = "选择恢复",
                description = "从当前 App 的任意完整备份中选择，再指定恢复到 user${state.settings.mainUserId} 或 user${state.settings.cloneUserId}。",
                actionLabel = "选择",
                icon = Icons.Default.RestartAlt,
                enabled = state.selectedBackups.isNotEmpty(),
                onClick = { showRestorePicker = true },
                primary = true,
                actionVariant = ToolRowActionVariant.Pill,
            )
            ToolRow(
                title = "当前 user${state.settings.mainUserId} 覆盖 user${state.settings.cloneUserId}",
                description = "复用安全推送链路，先保存 user${state.settings.cloneUserId} 回滚。",
                actionLabel = "覆盖",
                icon = Icons.Default.Sync,
                onClick = { confirm = ConfirmAction.PUSH_MAIN },
                danger = true,
                actionVariant = ToolRowActionVariant.Pill,
            )
            ToolRow(
                title = "当前 user${state.settings.cloneUserId} 覆盖 user${state.settings.mainUserId}",
                description = when (state.selectedDataState) {
                    AppDataState.Main -> "按正常切换链路建立 MAIN 返回点后覆盖。"
                    is AppDataState.Clone -> "刷新分数据并保留原 MAIN 返回点。"
                    AppDataState.Unknown, null -> "当前 user${state.settings.mainUserId} 来源未知，请改用“选择恢复”。"
                },
                actionLabel = if (state.selectedDataState == AppDataState.Unknown) "不可用" else "覆盖",
                icon = Icons.Default.Sync,
                enabled = state.selectedDataState != AppDataState.Unknown,
                onClick = { confirm = ConfirmAction.COVER_MAIN_WITH_CLONE },
                danger = true,
                actionVariant = ToolRowActionVariant.Pill,
                showDivider = false,
            )
        }
        SectionCard("当前 App 备份") {
            if (state.selectedBackups.isEmpty()) {
                Text("暂无备份。先使用上方备份工具生成一份命名备份。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                state.selectedBackups.forEachIndexed { index, backup ->
                    BackupEntryRow(
                        backup = backup,
                        onEdit = { editBackup = backup },
                        onRestore = { restoreDialogBackup = backup },
                        onDelete = { deleteBackup = backup },
                        showDivider = index < state.selectedBackups.lastIndex,
                    )
                }
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
        SectionCard("高级工具") {
            ToolRow(
                title = if (advancedExpanded) "收起高级工具" else "展开高级工具",
                description = "保留旧版切换、active 快照、固定 MAIN 返回点、审计和数据范围设置。",
                actionLabel = if (advancedExpanded) "收起" else "展开",
                icon = Icons.Default.Storage,
                onClick = { advancedExpanded = !advancedExpanded },
                showDivider = false,
            )
            if (advancedExpanded) {
                ToolRow(
                    title = when (state.selectedDataState) {
                        AppDataState.Main -> "切换到分身态"
                        is AppDataState.Clone -> "还原主系统态"
                        AppDataState.Unknown, null -> "数据状态待确认"
                    },
                    description = when (state.selectedDataState) {
                        AppDataState.Main -> SwitchPolicyText
                            .switchToCloneConfirmation(state.settings, mainReturnPoint != null)
                            .replace('\n', ' ')
                        is AppDataState.Clone -> SwitchPolicyText.planSummary(state.settings)
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
                )
                InfoRow("固定 MAIN 返回点", if (mainReturnPoint == null) "未建立" else "${Formatters.time(mainReturnPoint.createdAt)} · ${Formatters.kilobytes(mainReturnPoint.sizeKb)}")
                ToolRow(
                    title = "更新固定 MAIN 返回点",
                    description = "用当前已确认 MAIN 的 user${state.settings.mainUserId} 数据替换返回点。",
                    actionLabel = "更新",
                    icon = Icons.Default.RestartAlt,
                    enabled = state.selectedDataState == AppDataState.Main &&
                        state.selectedPackage?.let(state::hasConfirmedMainState) == true &&
                        !state.busy,
                    onClick = { confirm = ConfirmAction.UPDATE_MAIN_RETURN_POINT },
                )
                ToolRow(
                    title = "建立 active 主动备份",
                    description = "读取分身当前数据并保存到旧版 active 快照。",
                    actionLabel = "执行",
                    icon = Icons.Default.CloudDownload,
                    onClick = { confirm = ConfirmAction.CAPTURE },
                )
                ToolRow(
                    title = "用 active 快照覆盖 user${state.settings.mainUserId}",
                    description = "兼容旧版恢复入口，只读取 active 快照。",
                    actionLabel = "执行",
                    icon = Icons.Default.RestartAlt,
                    onClick = { confirm = ConfirmAction.RESTORE },
                )
                ToolRow(
                    title = "读取分身最新数据并恢复 user${state.settings.mainUserId}",
                    description = "先更新 active，再覆盖 user${state.settings.mainUserId}。",
                    actionLabel = "执行",
                    icon = Icons.Default.Sync,
                    onClick = { confirm = ConfirmAction.LATEST },
                )
                ToolRow(
                    title = "生成恢复审计包",
                    description = "只读采集文件、权限与上下文证据。",
                    actionLabel = "执行",
                    icon = Icons.Default.CloudDownload,
                    onClick = { confirm = ConfirmAction.AUDIT },
                )
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
                ToolRow(
                    title = "删除 active 主动快照",
                    description = "删除当前 active；history、命名备份和被动备份不受影响。",
                    actionLabel = "删除",
                    icon = Icons.Default.Delete,
                    enabled = app.lastSnapshotAt != null,
                    danger = true,
                    onClick = { confirm = ConfirmAction.DELETE },
                    showDivider = false,
                )
            }
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            action = action,
            highRisk = state.selectedApp?.riskLevel != RiskLevel.NORMAL,
            settings = state.settings,
            hasMainReturnPoint = mainReturnPoint != null,
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
                    ConfirmAction.PUSH_MAIN -> state.selectedPackage?.let(viewModel::pushMainToClone)
                    ConfirmAction.COVER_MAIN_WITH_CLONE -> state.selectedPackage?.let(viewModel::coverMainWithCurrentClone)
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
    backupDialog?.let { kind ->
        BackupNameDialog(
            kind = kind,
            appName = app?.label.orEmpty(),
            dataState = state.selectedDataState,
            settings = state.settings,
            onDismiss = { backupDialog = null },
            onConfirm = { name, sourceUserId ->
                backupDialog = null
                val captureUserId = when (kind) {
                    BackupDialogKind.MAIN -> state.settings.mainUserId
                    BackupDialogKind.CLONE -> state.settings.cloneUserId
                }
                viewModel.backupUserStateSelected(captureUserId, sourceUserId, name)
            },
        )
    }
    if (showRestorePicker || restoreDialogBackup != null) {
        RestorePickerDialog(
            backups = state.selectedBackups,
            initialBackup = restoreDialogBackup,
            settings = state.settings,
            onDismiss = {
                showRestorePicker = false
                restoreDialogBackup = null
            },
            onConfirm = { backup, targetUserId ->
                showRestorePicker = false
                restoreDialogBackup = null
                viewModel.restoreSelectedBackup(backup.packageName, backup.ref.kind, backup.ref.id, targetUserId)
            },
        )
    }
    editBackup?.let { backup ->
        EditBackupDialog(
            backup = backup,
            settings = state.settings,
            onDismiss = { editBackup = null },
            onConfirm = { name, sourceUserId ->
                editBackup = null
                viewModel.updateBackupMetadata(backup, name, sourceUserId)
            },
        )
    }
    deleteBackup?.let { backup ->
        DeleteBackupDialog(
            backup = backup,
            onDismiss = { deleteBackup = null },
            onConfirm = {
                deleteBackup = null
                viewModel.deleteBackup(backup)
            },
        )
    }
}

@Composable
private fun AppDataSourcePanel(mainUserId: Int, dataState: AppDataState?) {
    val accent = when (dataState) {
        AppDataState.Main -> MaterialTheme.ucloneColors.success
        is AppDataState.Clone -> MaterialTheme.colorScheme.primary
        AppDataState.Unknown, null -> MaterialTheme.ucloneColors.warning
    }
    val title = when (dataState) {
        AppDataState.Main -> "MAIN 主数据"
        is AppDataState.Clone -> "CLONE 分数据"
        AppDataState.Unknown, null -> "UNKNOWN 来源待确认"
    }
    val description = when (dataState) {
        AppDataState.Main -> "user$mainUserId 当前使用主系统来源的数据。"
        is AppDataState.Clone -> "user$mainUserId 当前使用从分身系统切换来的数据。"
        AppDataState.Unknown, null -> "切换记录不足，恢复已标识来源的备份后才能继续安全切换。"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.ucloneColors.groupedSurface,
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.22f)),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.heightIn(min = 44.dp),
                shape = CircleShape,
                color = accent.copy(alpha = 0.12f),
                contentColor = accent,
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(horizontal = 12.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "user$mainUserId 当前数据来源",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ResponsiveSwitchRow(
        label = label,
        checked = checked,
        onCheckedChange = onChange,
    )
}

@Composable
private fun BackupEntryRow(
    backup: BackupEntry,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    showDivider: Boolean,
) {
    BackupEntryActionRow(
        backup = backup,
        onEdit = onEdit,
        onRestore = onRestore,
        onDelete = onDelete,
        showDivider = showDivider,
    )
}

@Composable
private fun BackupNameDialog(
    kind: BackupDialogKind,
    appName: String,
    dataState: AppDataState?,
    settings: UCloneSettings,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    val captureUserId = when (kind) {
        BackupDialogKind.MAIN -> settings.mainUserId
        BackupDialogKind.CLONE -> settings.cloneUserId
    }
    val defaultSourceUserId = when (kind) {
        BackupDialogKind.CLONE -> settings.cloneUserId
        BackupDialogKind.MAIN -> when (dataState) {
            is AppDataState.Clone -> settings.cloneUserId
            AppDataState.Main -> settings.mainUserId
            AppDataState.Unknown, null -> settings.mainUserId
        }
    }
    var name by remember(kind, appName, captureUserId) {
        mutableStateOf(defaultBackupName(appName, captureUserId))
    }
    var sourceUserId by remember(kind, defaultSourceUserId) { mutableStateOf(defaultSourceUserId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == BackupDialogKind.MAIN) "备份主系统" else "备份分系统") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("实际采集自 user$captureUserId。来源 user 只影响之后恢复到 user${settings.mainUserId} 时的 MAIN/CLONE 判断。")
                if (kind == BackupDialogKind.MAIN && dataState == AppDataState.Unknown) {
                    Text("当前 user${settings.mainUserId} 数据来源未知，请先确认来源标签。", color = MaterialTheme.ucloneColors.warning)
                }
                TextField(
                    value = name,
                    onValueChange = { name = it.safeBackupNameInput() },
                    label = { Text("备注名称") },
                    singleLine = true,
                )
                SourceUserSelector(
                    selected = sourceUserId,
                    settings = settings,
                    onSelect = { sourceUserId = it },
                )
            }
        },
        confirmButton = {
            DialogActionButton(
                text = "备份",
                onClick = { onConfirm(name.trim().ifBlank { defaultBackupName(appName, captureUserId) }, sourceUserId) },
                primary = true,
            )
        },
        dismissButton = { DialogActionButton("取消", onDismiss) },
    )
}

@Composable
private fun RestorePickerDialog(
    backups: List<BackupEntry>,
    initialBackup: BackupEntry?,
    settings: UCloneSettings,
    onDismiss: () -> Unit,
    onConfirm: (BackupEntry, Int) -> Unit,
) {
    var selectedBackup by remember(initialBackup?.ref?.stableKey, backups) { mutableStateOf(initialBackup) }
    var targetUserId by remember(initialBackup?.ref?.stableKey) { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selectedBackup == null) "选择恢复" else "选择恢复目标") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val current = selectedBackup
                if (current == null) {
                    if (backups.isEmpty()) {
                        Text("当前 App 暂无可恢复备份。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        backups.forEach { backup ->
                            BackupChoiceRow(backup = backup, onSelect = {
                                selectedBackup = backup
                                targetUserId = null
                            })
                        }
                    }
                } else {
                    Text(current.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${current.typeLabel} · 来源 ${current.sourceUserLabel()} · 实采 ${current.capturedUserLabel()}")
                    SingleLinePathText(current.path)
                    SourceUserSelector(
                        selected = targetUserId,
                        settings = settings,
                        mainLabel = "恢复到 user${settings.mainUserId}",
                        cloneLabel = "恢复到 user${settings.cloneUserId}",
                        onSelect = { targetUserId = it },
                    )
                }
            }
        },
        confirmButton = {
            val current = selectedBackup
            DialogActionButton(
                text = "恢复",
                onClick = {
                    val target = targetUserId
                    if (current != null && target != null) onConfirm(current, target)
                },
                primary = true,
                enabled = current != null && targetUserId != null,
            )
        },
        dismissButton = {
            DialogActionButton(
                if (selectedBackup == null || initialBackup != null) "取消" else "返回",
                onClick = {
                    if (selectedBackup != null && initialBackup == null) {
                        selectedBackup = null
                        targetUserId = null
                    } else {
                        onDismiss()
                    }
                },
            )
        },
    )
}

@Composable
private fun BackupChoiceRow(backup: BackupEntry, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(backup.name, fontWeight = FontWeight.SemiBold)
            Text(
                "${backup.typeLabel} · 来源 ${backup.sourceUserLabel()} · ${Formatters.time(backup.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InlineActionButton("选择", onSelect)
    }
}

@Composable
private fun EditBackupDialog(
    backup: BackupEntry,
    settings: UCloneSettings,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var name by remember(backup.ref.stableKey) { mutableStateOf(backup.name) }
    var sourceUserId by remember(backup.ref.stableKey) {
        mutableStateOf(backup.sourceUserId ?: settings.mainUserId)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只更新备注和来源 user，不移动、不复制备份内容。")
                InfoRow("实际采集 user", backup.capturedUserLabel())
                TextField(
                    value = name,
                    onValueChange = { name = it.safeBackupNameInput() },
                    label = { Text("备注名称") },
                    singleLine = true,
                )
                SourceUserSelector(
                    selected = sourceUserId,
                    settings = settings,
                    onSelect = { sourceUserId = it },
                )
            }
        },
        confirmButton = {
            DialogActionButton(
                text = "保存",
                onClick = { onConfirm(name.trim().ifBlank { backup.name }, sourceUserId) },
                primary = true,
            )
        },
        dismissButton = { DialogActionButton("取消", onDismiss) },
    )
}

@Composable
private fun DeleteBackupDialog(
    backup: BackupEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(backup.name, fontWeight = FontWeight.SemiBold)
                Text("${backup.typeLabel} · 来源 ${backup.sourceUserLabel()} · ${Formatters.time(backup.createdAt)}")
                SingleLinePathText(backup.path)
                Text(backup.deleteBlockedReason ?: "删除后不能从这份备份恢复，其他备份不受影响。")
            }
        },
        confirmButton = {
            DialogActionButton(
                text = if (backup.canDelete) "删除" else "知道了",
                onClick = { if (backup.canDelete) onConfirm() else onDismiss() },
                danger = backup.canDelete,
            )
        },
        dismissButton = {
            if (backup.canDelete) DialogActionButton("取消", onDismiss)
        },
    )
}

@Composable
private fun SourceUserSelector(
    selected: Int?,
    settings: UCloneSettings,
    mainLabel: String = "标记 user${settings.mainUserId}",
    cloneLabel: String = "标记 user${settings.cloneUserId}",
    onSelect: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CompactActionButton(
            text = mainLabel,
            onClick = { onSelect(settings.mainUserId) },
            modifier = Modifier.weight(1f),
            primary = selected == settings.mainUserId,
        )
        CompactActionButton(
            text = cloneLabel,
            onClick = { onSelect(settings.cloneUserId) },
            modifier = Modifier.weight(1f),
            primary = selected == settings.cloneUserId,
        )
    }
}

private fun BackupEntry.sourceUserLabel(): String =
    sourceUserId?.let { "user$it" } ?: "未标记"

private fun BackupEntry.capturedUserLabel(): String =
    capturedFromUserId?.let { "user$it" } ?: "未知"

@Composable
private fun stateKindColor(stateKind: PassiveBackupStateKind?) = when (stateKind) {
    PassiveBackupStateKind.MAIN -> MaterialTheme.ucloneColors.success
    PassiveBackupStateKind.CLONE -> MaterialTheme.colorScheme.primary
    null -> MaterialTheme.ucloneColors.warning
}

private fun defaultBackupName(appName: String, userId: Int): String {
    val label = appName.ifBlank { "App" }
    return "$label · user$userId · ${Formatters.time(System.currentTimeMillis())}"
}

@Composable
internal fun InstallToolRow(title: String, description: String, onClick: () -> Unit) {
    if (useStackedLayoutForLargeText()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InlineActionButton(
                text = "执行",
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
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
}

private enum class BackupDialogKind {
    MAIN,
    CLONE,
}

private fun String.safeBackupNameInput(): String =
    filterNot { it == '\u0000' || it == '\n' || it == '\r' || it == '\t' }.take(80)

private enum class ConfirmAction {
    SWITCH,
    RESTORE_SWITCH,
    UPDATE_MAIN_RETURN_POINT,
    CAPTURE,
    RESTORE,
    LATEST,
    PUSH_MAIN,
    COVER_MAIN_WITH_CLONE,
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
    settings: UCloneSettings,
    hasMainReturnPoint: Boolean,
    installTargetLabel: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val title = when (action) {
        ConfirmAction.SWITCH -> "切换到分身态"
        ConfirmAction.RESTORE_SWITCH -> "还原主系统态"
        ConfirmAction.UPDATE_MAIN_RETURN_POINT -> "更新固定 MAIN 返回点"
        ConfirmAction.CAPTURE -> "建立主动备份"
        ConfirmAction.RESTORE -> "用 active 快照恢复 user${settings.mainUserId}"
        ConfirmAction.LATEST -> "读取分身最新数据并恢复 user${settings.mainUserId}"
        ConfirmAction.PUSH_MAIN -> "当前 user${settings.mainUserId} 覆盖 user${settings.cloneUserId}"
        ConfirmAction.COVER_MAIN_WITH_CLONE -> "当前 user${settings.cloneUserId} 覆盖 user${settings.mainUserId}"
        ConfirmAction.AUDIT -> "生成恢复审计包"
        ConfirmAction.DELETE -> "删除 active 快照"
        ConfirmAction.INSTALL_ONLY -> "仅安装到${installTargetLabel ?: "另一侧"}"
        ConfirmAction.INSTALL_PERMISSIONS -> "安装并迁移权限"
        ConfirmAction.INSTALL_SYNC -> "安装并同步数据"
    }
    val body = when (action) {
        ConfirmAction.SWITCH -> SwitchPolicyText.switchToCloneConfirmation(settings, hasMainReturnPoint)
        ConfirmAction.RESTORE_SWITCH -> SwitchPolicyText.restoreConfirmation(settings)
        ConfirmAction.UPDATE_MAIN_RETURN_POINT -> "只会读取 UClone 已明确记录为 MAIN 的 user${settings.mainUserId} 当前数据，完整验证后替换返回点。自动更新策略启用时，明确 MAIN 状态下的正常切换也会更新。"
        ConfirmAction.CAPTURE -> "将读取分身系统当前最新数据，并保存为 active 主动备份。旧 active 主动备份会移动到 history。"
        ConfirmAction.RESTORE -> "将使用已保存的 active 主动备份恢复主系统数据。这不会重新读取分身最新数据。"
        ConfirmAction.LATEST -> "将先更新分身主动备份，再恢复到主系统。该动作会覆盖主系统当前 App 数据。"
        ConfirmAction.PUSH_MAIN -> "将把 user${settings.mainUserId} 当前 App 数据覆盖到 user${settings.cloneUserId}。执行前会先保存 user${settings.cloneUserId} 回滚。"
        ConfirmAction.COVER_MAIN_WITH_CLONE -> "将使用 user${settings.cloneUserId} 当前 App 数据覆盖 user${settings.mainUserId}。MAIN 状态会走正常切换链路，CLONE 状态会刷新分数据。"
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

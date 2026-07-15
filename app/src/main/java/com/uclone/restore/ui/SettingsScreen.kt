package com.uclone.restore.ui

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.CloneSessionPolicy
import com.uclone.restore.model.MainReturnPointPolicy
import com.uclone.restore.model.SwitchSafetyMode
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.WorkspaceOwnershipReport
import com.uclone.restore.util.Formatters

@Composable
fun SettingsScreen(
    state: UiState,
    viewModel: UCloneViewModel,
    modifier: Modifier,
    openDiagnostics: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var confirmClearLogs by remember { mutableStateOf(false) }
    var resetConfirmStage by remember { mutableStateOf(0) }
    var confirmOwnershipRepair by remember { mutableStateOf(false) }
    var confirmDangerousSwitch by remember { mutableStateOf(false) }
    var switchPolicyDialog by remember { mutableStateOf<SwitchPolicyDialog?>(null) }
    val hasUnsavedChanges = draft != state.settings
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageDescription("切换策略、用户、工作区与维护")
        if (hasUnsavedChanges) {
            SettingsSaveBanner(
                onSave = {
                    viewModel.saveSettings(draft)
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
            )
        }
        SwitchStrategySection(draft) { switchPolicyDialog = it }
        SectionCard("诊断与维护") {
            ToolRow(
                title = "诊断与维护",
                description = "检查 Root、分身用户、工作区与数据解锁状态。",
                actionLabel = "进入",
                icon = Icons.Outlined.Terminal,
                onClick = openDiagnostics,
                showDivider = false,
            )
        }
        SectionCard("用户 ID") {
            NumberField("主系统 ID", draft.mainUserId) { draft = draft.copy(mainUserId = it) }
            NumberField("分身系统 ID", draft.cloneUserId) { draft = draft.copy(cloneUserId = it) }
        }
        SectionCard("设备内保存路径") {
            GroupedTextField(
                value = draft.rootDir,
                onValueChange = { draft = draft.copy(rootDir = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Root 数据目录") },
            )
            SingleLinePathText("主动快照: ${draft.rootDir}/snapshots/<包名>/active")
            SingleLinePathText("被动备份: ${draft.rootDir}/rollback/<包名>/<备份ID>")
            SingleLinePathText("分身回滚: ${draft.rootDir}/clone_rollback/<包名>/latest")
            SingleLinePathText("日志: ${draft.rootDir}/logs")
        }
        SectionCard("分身解锁") {
            ToggleRow("分身自动解锁", draft.autoUnlockClone) {
                draft = draft.copy(autoUnlockClone = it)
            }
            GroupedTextField(
                value = draft.cloneUnlockCredential,
                onValueChange = { draft = draft.copy(cloneUnlockCredential = it.trim()) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分身锁屏 PIN/密码") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "开启后，需要读取分身 CE 数据时会自动尝试后台启动并解锁。任务日志不会记录明文；当前已保存 ${draft.cloneUnlockCredential.length} 位。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("任务结束") {
            ToggleRow(
                label = "数据任务后关闭临时分身",
                checked = draft.stopCloneAfterTask,
                description = "仅对备份、切换、推送和分身回滚等数据任务生效，而且只关闭本次任务启动的 user${draft.cloneUserId}。无感启动分身不会自动关闭。",
            ) {
                draft = draft.copy(stopCloneAfterTask = it)
            }
        }
        SectionCard("模块控制") {
            ToggleRow(
                label = "允许模块控制",
                checked = draft.allowModuleControl,
                description = "默认关闭。开启后，仅允许同签名模块通过受保护协议触发备份、恢复、切换和推送；系统 App 与 UClone 自身仍会被拒绝。",
            ) {
                draft = draft.copy(allowModuleControl = it)
            }
        }
        SectionCard("默认数据范围") {
            ToggleRow("CE 数据", draft.includeCe) { draft = draft.copy(includeCe = it) }
            ToggleRow("DE 数据", draft.includeDe) { draft = draft.copy(includeDe = it) }
            ToggleRow("external", draft.includeExternal) { draft = draft.copy(includeExternal = it) }
            ToggleRow("media", draft.includeMedia) { draft = draft.copy(includeMedia = it) }
            ToggleRow("obb", draft.includeObb) { draft = draft.copy(includeObb = it) }
            ToggleRow("权限/AppOps", draft.includePermissions) { draft = draft.copy(includePermissions = it) }
            ToggleRow("排除 cache/code_cache", draft.excludeCache) { draft = draft.copy(excludeCache = it) }
        }
        val ownership = state.workspaceOwnership
        SectionCard("备份容量归属") {
            ToolRow(
                title = "扫描备份容量归属",
                description = if (ownership == null) {
                    "只读统计受管备份中的非 root 归属项；不修改备份内容，会记录任务日志。"
                } else {
                    "上次扫描 ${ownership.totalEntries} 项，其中 ${ownership.nonRootEntries} 项需要修复。"
                },
                actionLabel = "扫描",
                icon = Icons.Outlined.Search,
                onClick = viewModel::scanWorkspaceOwnership,
                enabled = !state.busy,
                showDivider = ownership != null,
            )
            if (ownership != null) {
                WorkspaceOwnershipSummary(ownership)
            }
            if (ownership != null && ownership.nonRootEntries > 0L) {
                ToolRow(
                    title = "修复备份容量归属",
                    description = "分批修正为 root:root；不修改文件内容或 SELinux context。",
                    actionLabel = "修复",
                    icon = Icons.Outlined.Build,
                    onClick = { confirmOwnershipRepair = true },
                    enabled = !state.busy,
                    showDivider = false,
                )
            }
        }
        SectionCard("任务日志") {
            ToolRow(
                title = "清理任务日志",
                description = "只删除任务日志；主动快照、被动备份和分身回滚不受影响。",
                actionLabel = "清理",
                icon = Icons.Default.Delete,
                onClick = { confirmClearLogs = true },
                danger = true,
                showDivider = false,
            )
        }
        SectionCard("危险操作") {
            ToolRow(
                title = "重置所有 UClone 数据",
                description = "清空工作区内全部备份和记录；不删除 App 真实数据，需两次确认。",
                actionLabel = "重置",
                icon = Icons.Default.Delete,
                onClick = { resetConfirmStage = 1 },
                danger = true,
                showDivider = false,
            )
        }
    }
    if (confirmOwnershipRepair) {
        val ownership = state.workspaceOwnership
        AlertDialog(
            onDismissRequest = { confirmOwnershipRepair = false },
            title = { Text("修复备份容量归属") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将把 ${ownership?.nonRootEntries ?: 0} 个工作区文件或目录修正为 root:root。")
                    Text("不主动执行 chmod，也不修改 SELinux context 或文件内容；任务中断后可重新扫描并继续。")
                    SingleLinePathText(ownership?.canonicalRoot ?: state.settings.rootDir)
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "开始修复",
                    onClick = {
                        confirmOwnershipRepair = false
                        viewModel.repairWorkspaceOwnership()
                    },
                    primary = true,
                )
            },
            dismissButton = { DialogActionButton("取消", onClick = { confirmOwnershipRepair = false }) },
        )
    }
    if (confirmClearLogs) {
        AlertDialog(
            onDismissRequest = { confirmClearLogs = false },
            title = { Text("清理任务日志") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将删除以下目录下的 .log 文件，并清空本次运行中的历史列表。主动备份和被动备份不会被删除。")
                    SingleLinePathText("${state.settings.rootDir}/logs")
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "继续",
                    onClick = {
                        confirmClearLogs = false
                        viewModel.clearLogs()
                    },
                    danger = true,
                )
            },
            dismissButton = { DialogActionButton("取消", onClick = { confirmClearLogs = false }) },
        )
    }
    if (resetConfirmStage == 1) {
        AlertDialog(
            onDismissRequest = { resetConfirmStage = 0 },
            title = { Text("重置所有 UClone 数据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将删除主动快照、被动备份、分身回滚、切换标记、任务日志、审计包和临时文件。")
                    Text("不会删除主系统或分身系统中任何 App 的真实数据目录。")
                    SingleLinePathText(state.settings.rootDir)
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "继续",
                    onClick = { resetConfirmStage = 2 },
                    danger = true,
                )
            },
            dismissButton = { DialogActionButton("取消", onClick = { resetConfirmStage = 0 }) },
        )
    }
    if (resetConfirmStage == 2) {
        AlertDialog(
            onDismissRequest = { resetConfirmStage = 0 },
            title = { Text("最终确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这个操作不可撤销，会清空 UClone Restore 保存的所有备份和记录。")
                    Text("确认后将立即通过 root 清理 UClone 工作目录。")
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "确认重置",
                    onClick = {
                        resetConfirmStage = 0
                        viewModel.resetWorkspace()
                    },
                    danger = true,
                )
            },
            dismissButton = { DialogActionButton("取消", onClick = { resetConfirmStage = 0 }) },
        )
    }
    if (confirmDangerousSwitch) {
        AlertDialog(
            onDismissRequest = { confirmDangerousSwitch = false },
            title = { Text("启用危险快速返回") },
            text = {
                Text(SwitchPolicyText.restoreConfirmation(draft.copy(switchSafetyMode = SwitchSafetyMode.DANGEROUS_FAST)))
            },
            confirmButton = {
                DialogActionButton(
                    text = "确认启用",
                    onClick = {
                        confirmDangerousSwitch = false
                        draft = draft.copy(switchSafetyMode = SwitchSafetyMode.DANGEROUS_FAST)
                    },
                    danger = true,
                )
            },
            dismissButton = {
                DialogActionButton("取消", onClick = { confirmDangerousSwitch = false })
            },
        )
    }
    switchPolicyDialog?.let { dialog ->
        val choices = when (dialog) {
            SwitchPolicyDialog.MAIN_RETURN -> listOf(
                PolicyChoice(
                    label = "固定保存",
                    description = SwitchPolicyText.mainReturnDescription(MainReturnPointPolicy.FIXED),
                    selected = draft.mainReturnPointPolicy == MainReturnPointPolicy.FIXED,
                    onSelect = {
                        draft = draft.copy(mainReturnPointPolicy = MainReturnPointPolicy.FIXED)
                        switchPolicyDialog = null
                    },
                ),
                PolicyChoice(
                    label = "每次离开 MAIN 时更新",
                    description = SwitchPolicyText.mainReturnDescription(MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT),
                    selected = draft.mainReturnPointPolicy == MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT,
                    onSelect = {
                        draft = draft.copy(mainReturnPointPolicy = MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT)
                        switchPolicyDialog = null
                    },
                ),
            )
            SwitchPolicyDialog.CLONE_SESSION -> listOf(
                PolicyChoice(
                    label = "同步到分身系统",
                    description = SwitchPolicyText.cloneSessionDescription(CloneSessionPolicy.SYNC_TO_CLONE_USER, draft.cloneUserId),
                    selected = draft.cloneSessionPolicy == CloneSessionPolicy.SYNC_TO_CLONE_USER,
                    onSelect = {
                        draft = draft.copy(cloneSessionPolicy = CloneSessionPolicy.SYNC_TO_CLONE_USER)
                        switchPolicyDialog = null
                    },
                ),
                PolicyChoice(
                    label = "不更新分身系统",
                    description = SwitchPolicyText.cloneSessionDescription(CloneSessionPolicy.DISCARD_ON_MAIN_RETURN, draft.cloneUserId),
                    selected = draft.cloneSessionPolicy == CloneSessionPolicy.DISCARD_ON_MAIN_RETURN,
                    onSelect = {
                        draft = draft.copy(cloneSessionPolicy = CloneSessionPolicy.DISCARD_ON_MAIN_RETURN)
                        switchPolicyDialog = null
                    },
                ),
            )
            SwitchPolicyDialog.SAFETY -> listOf(
                PolicyChoice(
                    label = "安全保护",
                    description = SwitchPolicyText.safetyDescription(SwitchSafetyMode.SAFE),
                    selected = draft.switchSafetyMode == SwitchSafetyMode.SAFE,
                    onSelect = {
                        draft = draft.copy(switchSafetyMode = SwitchSafetyMode.SAFE)
                        switchPolicyDialog = null
                    },
                ),
                PolicyChoice(
                    label = "危险快速",
                    description = SwitchPolicyText.safetyDescription(SwitchSafetyMode.DANGEROUS_FAST),
                    selected = draft.switchSafetyMode == SwitchSafetyMode.DANGEROUS_FAST,
                    danger = true,
                    onSelect = {
                        switchPolicyDialog = null
                        confirmDangerousSwitch = true
                    },
                ),
            )
        }
        AlertDialog(
            onDismissRequest = { switchPolicyDialog = null },
            title = {
                Text(
                    when (dialog) {
                        SwitchPolicyDialog.MAIN_RETURN -> "选择 MAIN 返回点策略"
                        SwitchPolicyDialog.CLONE_SESSION -> "选择分数据处理方式"
                        SwitchPolicyDialog.SAFETY -> "选择失败保护"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { choice -> PolicyChoiceRow(choice) }
                }
            },
            confirmButton = {},
            dismissButton = { DialogActionButton("取消", onClick = { switchPolicyDialog = null }) },
        )
    }
}

@Composable
private fun SwitchStrategySection(
    draft: UCloneSettings,
    onSelectPolicy: (SwitchPolicyDialog) -> Unit,
) {
    SectionCard("切换策略") {
        PolicySettingRow(
            title = "MAIN 返回点",
            value = SwitchPolicyText.mainReturnLabel(draft.mainReturnPointPolicy),
            description = SwitchPolicyText.mainReturnDescription(draft.mainReturnPointPolicy),
            onClick = { onSelectPolicy(SwitchPolicyDialog.MAIN_RETURN) },
        )
        HorizontalDivider(color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.42f))
        PolicySettingRow(
            title = "返回 MAIN 时的分数据",
            value = SwitchPolicyText.cloneSessionLabel(draft.cloneSessionPolicy),
            description = SwitchPolicyText.cloneSessionDescription(draft.cloneSessionPolicy, draft.cloneUserId),
            onClick = { onSelectPolicy(SwitchPolicyDialog.CLONE_SESSION) },
        )
        HorizontalDivider(color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.42f))
        PolicySettingRow(
            title = "失败保护",
            value = SwitchPolicyText.safetyLabel(draft.switchSafetyMode),
            description = SwitchPolicyText.safetyDescription(draft.switchSafetyMode),
            onClick = { onSelectPolicy(SwitchPolicyDialog.SAFETY) },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 10.dp, end = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "当前方案 · ${SwitchPolicyText.planLabel(draft)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (draft.switchSafetyMode == SwitchSafetyMode.DANGEROUS_FAST) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                SwitchPolicyText.planSummary(draft),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "切换到 CLONE 始终读取 user${draft.cloneUserId} 当前数据，不使用长期 CLONE 备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsSaveBanner(onSave: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("有未保存的更改", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "保存后应用到后续任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CompactActionButton(
                text = "保存",
                onClick = onSave,
                primary = true,
                icon = Icons.Default.Save,
            )
        }
    }
}

@Composable
internal fun WorkspaceOwnershipSummary(report: WorkspaceOwnershipReport) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "总项数 ${report.totalEntries} · 非 root ${report.nonRootEntries}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "容量 ${Formatters.kilobytes(report.totalSizeKb)} · 扫描时间 ${Formatters.time(report.scannedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleLinePathText("规范路径：${report.canonicalRoot}")
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    GroupedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onChange) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
            )
            UCloneSwitch(
                checked = checked,
                onCheckedChange = onChange,
            )
        }
        if (description != null) {
            Text(
                description,
                modifier = Modifier.padding(end = 4.dp, bottom = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class SwitchPolicyDialog {
    MAIN_RETURN,
    CLONE_SESSION,
    SAFETY,
}

private data class PolicyChoice(
    val label: String,
    val description: String,
    val selected: Boolean,
    val danger: Boolean = false,
    val onSelect: () -> Unit,
)

@Composable
private fun PolicySettingRow(
    title: String,
    value: String,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Normal)
                Text(
                    "当前：$value",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "选择$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PolicyChoiceRow(choice: PolicyChoice) {
    val accent = if (choice.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        onClick = choice.onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (choice.selected) accent.copy(alpha = 0.10f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    choice.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                    color = if (choice.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    choice.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (choice.selected) {
                Icon(Icons.Default.Check, contentDescription = "已选择", tint = accent)
            }
        }
    }
}

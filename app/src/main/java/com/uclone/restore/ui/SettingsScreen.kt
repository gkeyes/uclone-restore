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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.SwitchSafetyMode
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
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = LocalBottomBarContentPadding.current,
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageDescription("配置用户、工作区和默认数据范围；危险维护操作集中在页面底部。")
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
        SectionCard("切换模式") {
            InfoRow("MAIN 恢复来源", "固定 MAIN 返回点")
            Text(
                "首次从 MAIN 切换到 CLONE 时建立；之后不会自动更新，只能在 App 详情中手动更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoRow("CLONE 切换来源", "user${draft.cloneUserId} 当前数据")
            Text(
                "每次切换到 CLONE 都直接读取分身系统当前数据，不使用旧版 CLONE 长期备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow(
                label = "危险快速返回",
                checked = draft.switchSafetyMode == SwitchSafetyMode.DANGEROUS_FAST,
                description = if (draft.switchSafetyMode == SwitchSafetyMode.DANGEROUS_FAST) {
                    "CLONE → MAIN 为 2 次完整写入：直接同步当前分数据到 user${draft.cloneUserId}，再恢复固定 MAIN。不建立本地 CLONE 检查点；恢复失败时会标记为未知并要求人工处理。"
                } else {
                    "安全模式。MAIN → CLONE 为 2 次完整写入；CLONE → MAIN 为 3 次：保存本地 CLONE 检查点、同步 user${draft.cloneUserId}、恢复固定 MAIN。"
                },
            ) {
                if (it) {
                    confirmDangerousSwitch = true
                } else {
                    draft = draft.copy(switchSafetyMode = SwitchSafetyMode.SAFE)
                }
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
        SectionCard("应用更改") {
            ToolRow(
                title = "保存本页设置",
                description = "应用用户、工作区和数据范围修改。",
                actionLabel = "保存",
                onClick = {
                    viewModel.saveSettings(draft)
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
                icon = Icons.Default.Save,
                showDivider = false,
            )
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
                Text(
                    "该模式只改变 CLONE → MAIN：会省去一次本地 CLONE 检查点复制。若固定 MAIN 恢复失败，user0 没有本次操作前的本地回滚，只能保持未知状态并人工处理。",
                )
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
                fontWeight = FontWeight.Medium,
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

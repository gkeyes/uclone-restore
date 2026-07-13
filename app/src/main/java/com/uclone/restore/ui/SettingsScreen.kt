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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var confirmClearLogs by remember { mutableStateOf(false) }
    var resetConfirmStage by remember { mutableStateOf(0) }
    var confirmOwnershipRepair by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageDescription("配置用户、工作区和默认数据范围；危险维护操作集中在页面底部。")
        SectionCard("用户 ID") {
            NumberField("主系统 ID", draft.mainUserId) { draft = draft.copy(mainUserId = it) }
            NumberField("分身系统 ID", draft.cloneUserId) { draft = draft.copy(cloneUserId = it) }
        }
        SectionCard("设备内保存路径") {
            OutlinedTextField(
                value = draft.rootDir,
                onValueChange = { draft = draft.copy(rootDir = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Root 数据目录") },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
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
            OutlinedTextField(
                value = draft.cloneUnlockCredential,
                onValueChange = { draft = draft.copy(cloneUnlockCredential = it.trim()) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("分身锁屏 PIN/密码") },
                shape = RoundedCornerShape(14.dp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "开启后，需要读取分身 CE 数据时会自动尝试后台启动并解锁。任务日志不会记录明文；当前已保存 ${draft.cloneUnlockCredential.length} 位。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("任务结束") {
            ToggleRow("数据任务后关闭临时分身", draft.stopCloneAfterTask) {
                draft = draft.copy(stopCloneAfterTask = it)
            }
            Text(
                "仅对备份、切换、推送和分身回滚等数据任务生效，而且只关闭本次任务启动的 user${draft.cloneUserId}。无感启动分身不会自动关闭。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("状态备份") {
            ToggleRow("已有主/分状态备份时不再更新", draft.reuseExistingPassiveBackups) {
                draft = draft.copy(reuseExistingPassiveBackups = it)
            }
            Text(
                if (draft.reuseExistingPassiveBackups) {
                    "开启后，已有 MAIN 或 CLONE 长期状态备份会继续保留。每次操作仍会先建立本次专用回滚，失败时不会使用旧备份代替操作前数据。"
                } else {
                    "关闭时，每次成功切换、还原或推送都会用操作前的最新数据更新对应 MAIN/CLONE 长期状态备份。"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToggleRow("还原主系统前强制更新分数据", draft.forceUpdateCloneDataBeforeMainRestore) {
                draft = draft.copy(forceUpdateCloneDataBeforeMainRestore = it)
            }
            Text(
                "仅在 user${draft.mainUserId} 已确认处于 CLONE 状态时生效：先把当前分数据推送到 user${draft.cloneUserId}，成功后再恢复 MAIN 返回点。推送失败时不会开始还原。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("模块控制") {
            ToggleRow("允许模块控制", draft.allowModuleControl) {
                draft = draft.copy(allowModuleControl = it)
            }
            Text(
                "默认关闭。开启后，仅允许同签名模块通过受保护协议触发备份、恢复、切换和推送；系统 App 与 UClone 自身仍会被拒绝。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        PrimaryActionButton(
            onClick = {
                viewModel.saveSettings(draft)
                Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Text("保存设置")
        }
        SectionCard("维护") {
            Text("备份容量归属", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val ownership = state.workspaceOwnership
            if (ownership == null) {
                Text("先执行只读扫描，确认旧备份是否仍归属于目标 App。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                InfoRow("文件与目录", ownership.totalEntries.toString())
                InfoRow("需要修复", ownership.nonRootEntries.toString())
                SingleLinePathText(ownership.canonicalRoot)
            }
            ToolRow(
                title = "扫描备份容量归属",
                description = "只读统计非 root 归属项，不修改工作区内容。",
                actionLabel = "扫描",
                icon = Icons.Outlined.Search,
                onClick = viewModel::scanWorkspaceOwnership,
                enabled = !state.busy,
            )
            if (ownership != null && ownership.nonRootEntries > 0L) {
                ToolRow(
                    title = "修复备份容量归属",
                    description = "分批把受管目录中的 UID/GID 修正为 root:root。",
                    actionLabel = "修复",
                    icon = Icons.Outlined.Build,
                    onClick = { confirmOwnershipRepair = true },
                    enabled = !state.busy,
                )
            }
            Text("日志目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleLinePathText("${state.settings.rootDir}/logs")
            Text("清理日志只删除任务日志文件，不会删除主动备份或被动备份。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToolRow(
                title = "清理任务日志",
                description = "只删除日志文件，不删除主动快照、被动备份或分身回滚。",
                actionLabel = "清理",
                icon = Icons.Default.Delete,
                onClick = { confirmClearLogs = true },
                danger = true,
            )
            Text(
                "重置会删除 UClone 工作目录中的所有备份、日志、审计包、切换标记和临时文件，不会删除任何 App 的真实数据目录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToolRow(
                title = "重置所有 UClone 数据",
                description = "清空 UClone 工作区中的备份、记录和临时文件，需要两次确认。",
                actionLabel = "重置",
                icon = Icons.Default.Delete,
                onClick = { resetConfirmStage = 1 },
                danger = true,
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
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.toIntOrNull()?.let(onChange) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        shape = RoundedCornerShape(14.dp),
        singleLine = true,
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.ucloneColors.success,
                uncheckedThumbColor = MaterialTheme.ucloneColors.groupedSurface,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )
    }
}

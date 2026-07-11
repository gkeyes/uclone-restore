package com.uclone.restore.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.uclone.restore.util.Formatters

@Composable
fun SettingsScreen(state: UiState, viewModel: UCloneViewModel, modifier: Modifier) {
    val context = LocalContext.current
    var draft by remember(state.settings) { mutableStateOf(state.settings) }
    var confirmClearLogs by remember { mutableStateOf(false) }
    var confirmOwnershipRepair by remember { mutableStateOf(false) }
    var resetConfirmStage by remember { mutableStateOf(0) }
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("设置", "调整用户 ID、保存路径和默认备份范围。")
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
                "仅对备份、切换、推送和分身回滚等数据任务生效，而且只关闭本次任务启动的 user10。无感启动分身不会自动关闭。",
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
        SectionCard("高级安装") {
            ToggleRow("允许系统 App 跨用户安装", draft.allowSystemAppInstall) {
                draft = draft.copy(allowSystemAppInstall = it)
            }
            Text(
                "默认关闭。开启后，系统 App 详情页才显示跨用户安装工具；安装仍使用系统现有 APK，不复制 /data/app。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard("被动备份") {
            ToggleRow("复用已有状态备份", draft.reuseExistingPassiveBackups) {
                draft = draft.copy(reuseExistingPassiveBackups = it)
            }
            Text(
                if (draft.reuseExistingPassiveBackups) {
                    "主状态、分身状态和推送回滚各保留一份。已有备份校验有效时不重复复制；缺失或损坏时仍会重新建立。"
                } else {
                    "每次覆盖前刷新对应状态备份，数据更新但耗时更长。开启复用后，请留意数据页显示的备份时间。"
                },
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
        IosPrimaryButton(
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
                Text("先执行只读扫描，确认旧备份是否仍归属于目标 App UID。扫描不会修改文件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                InfoRow("工作区文件与目录", ownership.totalEntries.toString())
                InfoRow("需要修复", ownership.nonRootEntries.toString())
                InfoRow("备份占用", Formatters.kilobytes(ownership.totalSizeKb))
                SingleLinePathText(ownership.rootPath)
            }
            IosSecondaryButton(
                onClick = viewModel::scanWorkspaceOwnership,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null)
                Text("扫描备份容量归属")
            }
            if (ownership != null && ownership.nonRootEntries > 0L) {
                IosSecondaryButton(
                    onClick = { confirmOwnershipRepair = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                ) {
                    Icon(Icons.Outlined.Build, contentDescription = null, tint = IosOrange)
                    Text("修复备份容量归属", color = IosOrange)
                }
            }
            Text("日志目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SingleLinePathText("${state.settings.rootDir}/logs")
            Text("清理日志只删除任务日志文件，不会删除主动备份或被动备份。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            IosSecondaryButton(onClick = { confirmClearLogs = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = IosRed)
                Text("清理任务日志", color = IosRed)
            }
            Text(
                "重置会删除 UClone 工作目录中的所有备份、日志、审计包、切换标记和临时文件，不会删除任何 App 的真实数据目录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IosSecondaryButton(onClick = { resetConfirmStage = 1 }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = IosRed)
                Text("重置所有 UClone 数据", color = IosRed)
            }
        }
    }
    if (confirmOwnershipRepair) {
        val ownership = state.workspaceOwnership
        AlertDialog(
            onDismissRequest = { confirmOwnershipRepair = false },
            title = { Text("修复备份容量归属") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将把 UClone 备份工作区中 ${ownership?.nonRootEntries ?: 0} 个文件或目录的 owner 修正为 root:root。")
                    Text("只修改 UID/GID，不删除文件，不修改 mode、SELinux context 或备份内容。任务可中断并安全重试。")
                    SingleLinePathText(ownership?.rootPath ?: state.settings.rootDir)
                }
            },
            confirmButton = {
                IosDialogButton(
                    text = "开始修复",
                    onClick = {
                        confirmOwnershipRepair = false
                        viewModel.repairWorkspaceOwnership()
                    },
                    semanticTint = IosOrange,
                )
            },
            dismissButton = { IosDialogButton("取消", onClick = { confirmOwnershipRepair = false }) },
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
                IosDialogButton(
                    text = "继续",
                    onClick = {
                        confirmClearLogs = false
                        viewModel.clearLogs()
                    },
                    danger = true,
                )
            },
            dismissButton = { IosDialogButton("取消", onClick = { confirmClearLogs = false }) },
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
                IosDialogButton(
                    text = "继续",
                    onClick = { resetConfirmStage = 2 },
                    danger = true,
                )
            },
            dismissButton = { IosDialogButton("取消", onClick = { resetConfirmStage = 0 }) },
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
                IosDialogButton(
                    text = "确认重置",
                    onClick = {
                        resetConfirmStage = 0
                        viewModel.resetWorkspace()
                    },
                    danger = true,
                )
            },
            dismissButton = { IosDialogButton("取消", onClick = { resetConfirmStage = 0 }) },
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
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

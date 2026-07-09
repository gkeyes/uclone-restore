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
            ToggleRow("任务完成后关闭分身系统", draft.stopCloneAfterTask) {
                draft = draft.copy(stopCloneAfterTask = it)
            }
            Text(
                "建立备份只关闭本次后台启动的分身；切换和还原会在任务结束后按此开关关闭 user10。",
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

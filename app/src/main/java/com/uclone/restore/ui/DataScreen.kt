package com.uclone.restore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.BackupEntry
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.util.Formatters

@Composable
fun DataScreen(
    state: UiState,
    viewModel: UCloneViewModel,
    modifier: Modifier,
    openActiveBackup: (String) -> Unit,
    openPassiveBackup: (RestoreBackupEntry) -> Unit,
    onOpenHistory: () -> Unit,
) {
    var confirmRestore by remember { mutableStateOf<RestoreBackupEntry?>(null) }
    var confirmCloneRestore by remember { mutableStateOf<RestoreBackupEntry?>(null) }
    var restoreBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var editBackup by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteBackup by remember { mutableStateOf<BackupEntry?>(null) }
    val appByPackage = state.apps.associateBy { it.packageName }
    val rootDir = state.settings.rootDir
    val activeBackups = state.apps
        .filter { it.lastSnapshotAt != null }
        .sortedByDescending { it.lastSnapshotAt ?: 0L }
    val passiveBackups = state.restoreBackups
    val cloneRollbackBackups = state.cloneRollbackBackups
    val allBackups = state.backups

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(
            top = LocalTopBarContentPadding.current,
            bottom = LocalBottomBarContentPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            Box(Modifier.padding(bottom = 10.dp)) {
                TopLevelHeader(
                    title = "数据",
                    description = "主动快照、MAIN 返回点与 CLONE 回滚",
                    taskActive = state.currentTask.task != null,
                    onOpenHistory = onOpenHistory,
                )
            }
        }
        item {
            Box(Modifier.padding(bottom = 12.dp)) {
                SectionCard("存储区分") {
                    StoragePathRow("主动快照", "$rootDir/snapshots/<包名>/active")
                    StoragePathRow("MAIN 返回点与事务回滚", "$rootDir/rollback/<包名>/<备份ID>")
                    StoragePathRow("CLONE 推送回滚", "$rootDir/clone_rollback/<包名>/latest")
                    Text(
                        "MAIN 使用固定返回点；CLONE 切换直接读取 user${state.settings.cloneUserId} 当前数据。本次事务回滚始终单独建立。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            SectionLabel("全部可用备份", "新版命名备份、旧 active/history、MAIN 返回点和分身回滚。")
        }
        if (allBackups.isEmpty()) {
            item {
                Box(Modifier.padding(bottom = 12.dp)) {
                    SectionCard("暂无备份") {
                        Text("在 App 详情中执行“备份主系统”或“备份分系统”后会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            itemsIndexed(
                allBackups,
                key = { _, backup -> "backup-${backup.packageName}-${backup.ref.stableKey}" },
            ) { index, backup ->
                UnifiedBackupRow(
                    backup = backup,
                    appLabel = appByPackage[backup.packageName]?.label,
                    shape = groupedRowShape(index, allBackups.lastIndex),
                    showDivider = index < allBackups.lastIndex,
                    onEdit = { editBackup = backup },
                    onRestore = { restoreBackup = backup },
                    onDelete = { deleteBackup = backup },
                )
                if (index == allBackups.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        item {
            SectionLabel("主动备份", "手动建立或任务中刷新出的分身快照。")
        }
        if (activeBackups.isEmpty()) {
            item {
                Box(Modifier.padding(bottom = 12.dp)) {
                    SectionCard("暂无主动备份") {
                        Text("点击 App 详情里的“建立主动备份”后会显示在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            itemsIndexed(activeBackups, key = { _, app -> "active-${app.packageName}" }) { index, app ->
                ActiveBackupRow(
                    app = app,
                    shape = groupedRowShape(index, activeBackups.lastIndex),
                    showDivider = index < activeBackups.lastIndex,
                ) {
                    openActiveBackup(app.packageName)
                }
                if (index == activeBackups.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        item {
            SectionLabel("主系统侧备份", "包含固定 MAIN 返回点、事务回滚和仅供手动恢复的旧版备份。")
        }
        if (passiveBackups.isEmpty()) {
            item {
                Box(Modifier.padding(bottom = 12.dp)) {
                    SectionCard("暂无被动备份") {
                        Text("执行恢复或切换前，App 会自动保存当前 user${state.settings.mainUserId} 数据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            itemsIndexed(
                passiveBackups,
                key = { _, backup -> "passive-${backup.packageName}-${backup.rollbackId}" },
            ) { index, backup ->
                PassiveBackupRow(
                    backup = backup,
                    app = appByPackage[backup.packageName],
                    onOpenDetail = { openPassiveBackup(backup) },
                    onRestore = { confirmRestore = backup },
                    shape = groupedRowShape(index, passiveBackups.lastIndex),
                    showDivider = index < passiveBackups.lastIndex,
                )
                if (index == passiveBackups.lastIndex) Spacer(Modifier.height(12.dp))
            }
        }
        item {
            SectionLabel("分身回滚", "推送到分身前自动保存的分身侧最新备份。")
        }
        if (cloneRollbackBackups.isEmpty()) {
            item {
                SectionCard("暂无分身回滚") {
                    Text("执行“推送到分身”前会保存分身当前数据，方便撤回本次推送。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            itemsIndexed(
                cloneRollbackBackups,
                key = { _, backup -> "clone-${backup.packageName}-${backup.rollbackId}" },
            ) { index, backup ->
                PassiveBackupRow(
                    backup = backup,
                    app = appByPackage[backup.packageName],
                    onOpenDetail = null,
                    onRestore = { confirmCloneRestore = backup },
                    shape = groupedRowShape(index, cloneRollbackBackups.lastIndex),
                    showDivider = index < cloneRollbackBackups.lastIndex,
                )
            }
        }
    }

    confirmRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("恢复被动备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将使用以下被动备份覆盖主系统 user${state.settings.mainUserId} 数据。")
                    SingleLinePathText("$rootDir/rollback/${backup.packageName}/${backup.rollbackId}")
                    Text(
                        when (backup.stateKind) {
                            PassiveBackupStateKind.MAIN -> "执行后主系统将标记为主数据 MAIN，可继续切换到分身态。"
                            PassiveBackupStateKind.CLONE -> "执行后主系统将标记为分数据 CLONE，可从主数据返回点还原。"
                            null -> "该备份没有可靠来源标签，执行后状态将标记为 UNKNOWN，请先核对数据来源。"
                        },
                    )
                    Text("来源: ${backup.reason}")
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "恢复",
                    onClick = {
                        confirmRestore = null
                        viewModel.restoreBackup(backup.packageName, backup.rollbackId)
                    },
                    primary = true,
                )
            },
            dismissButton = {
                DialogActionButton("取消", onClick = { confirmRestore = null })
            },
        )
    }

    confirmCloneRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { confirmCloneRestore = null },
            title = { Text("恢复分身回滚") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("将使用以下分身回滚覆盖分身 user${state.settings.cloneUserId} 数据。")
                    SingleLinePathText("$rootDir/clone_rollback/${backup.packageName}/${backup.rollbackId}")
                    Text("来源: ${backup.reason}")
                }
            },
            confirmButton = {
                DialogActionButton(
                    text = "恢复",
                    onClick = {
                        confirmCloneRestore = null
                        viewModel.restoreCloneRollback(backup.packageName)
                    },
                    primary = true,
                )
            },
            dismissButton = {
                DialogActionButton("取消", onClick = { confirmCloneRestore = null })
            },
        )
    }

    restoreBackup?.let { backup ->
        RestoreSelectedBackupDialog(
            backup = backup,
            settings = state.settings,
            onDismiss = { restoreBackup = null },
            onConfirm = { targetUserId ->
                restoreBackup = null
                viewModel.restoreSelectedBackup(backup.packageName, backup.ref.kind, backup.ref.id, targetUserId)
            },
        )
    }

    editBackup?.let { backup ->
        EditUnifiedBackupDialog(
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
        DeleteUnifiedBackupDialog(
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
private fun StoragePathRow(label: String, path: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleLinePathText(path)
    }
}

@Composable
private fun UnifiedBackupRow(
    backup: BackupEntry,
    appLabel: String?,
    shape: Shape,
    showDivider: Boolean,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val largeText = useStackedLayoutForLargeText()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.ucloneColors.groupedSurface,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(backup.packageName, size = 38.dp, cornerRadius = 10.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(appLabel ?: backup.packageName, fontWeight = FontWeight.SemiBold)
                    Text(backup.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${backup.typeLabel} · 来源 ${backup.sourceUserLabel()} · 实采 ${backup.capturedUserLabel()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = backup.stateColor(),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${Formatters.kilobytes(backup.sizeKb)} · ${Formatters.time(backup.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    backup.deleteBlockedReason?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.ucloneColors.warning)
                    }
                }
                if (!largeText) {
                    InlineActionButton("编辑", onEdit, icon = Icons.Default.Edit)
                    InlineActionButton("恢复", onRestore, icon = Icons.Default.RestartAlt)
                    InlineActionButton("删除", onDelete, enabled = backup.canDelete, danger = true, icon = Icons.Default.Delete)
                }
            }
            if (largeText) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    InlineActionButton("编辑", onEdit, icon = Icons.Default.Edit)
                    InlineActionButton("恢复", onRestore, icon = Icons.Default.RestartAlt)
                    InlineActionButton("删除", onDelete, enabled = backup.canDelete, danger = true, icon = Icons.Default.Delete)
                }
            }
            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 64.dp),
                    color = MaterialTheme.ucloneColors.separator.copy(alpha = 0.42f),
                )
            }
        }
    }
}

@Composable
private fun RestoreSelectedBackupDialog(
    backup: BackupEntry,
    settings: UCloneSettings,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var targetUserId by remember(backup.ref.stableKey) { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择恢复目标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(backup.name, fontWeight = FontWeight.SemiBold)
                Text("${backup.typeLabel} · 来源 ${backup.sourceUserLabel()} · 实采 ${backup.capturedUserLabel()}")
                SingleLinePathText(backup.path)
                UserChoiceRow(
                    selected = targetUserId,
                    settings = settings,
                    mainLabel = "恢复到 user${settings.mainUserId}",
                    cloneLabel = "恢复到 user${settings.cloneUserId}",
                    onSelect = { targetUserId = it },
                )
            }
        },
        confirmButton = {
            DialogActionButton(
                text = "恢复",
                onClick = { targetUserId?.let(onConfirm) },
                primary = true,
            )
        },
        dismissButton = { DialogActionButton("取消", onDismiss) },
    )
}

@Composable
private fun EditUnifiedBackupDialog(
    backup: BackupEntry,
    settings: UCloneSettings,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var name by remember(backup.ref.stableKey) { mutableStateOf(backup.name) }
    var sourceUserId by remember(backup.ref.stableKey) { mutableStateOf(backup.sourceUserId ?: settings.mainUserId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑备份") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只修改附加元数据，不移动备份内容。")
                Text("实际采集自 ${backup.capturedUserLabel()}。")
                TextField(
                    value = name,
                    onValueChange = { name = it.safeBackupNameInput() },
                    label = { Text("备注名称") },
                    singleLine = true,
                )
                UserChoiceRow(
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
private fun DeleteUnifiedBackupDialog(
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
private fun UserChoiceRow(
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
private fun BackupEntry.stateColor() = when (stateKind) {
    PassiveBackupStateKind.MAIN -> MaterialTheme.ucloneColors.success
    PassiveBackupStateKind.CLONE -> MaterialTheme.colorScheme.primary
    null -> MaterialTheme.ucloneColors.warning
}

private fun String.safeBackupNameInput(): String =
    filterNot { it == '\u0000' || it == '\n' || it == '\r' || it == '\t' }.take(80)

private fun groupedRowShape(index: Int, lastIndex: Int): Shape = when {
    lastIndex <= 0 -> RoundedCornerShape(UCloneGroupedCornerRadius)
    index == 0 -> RoundedCornerShape(topStart = UCloneGroupedCornerRadius, topEnd = UCloneGroupedCornerRadius)
    index == lastIndex -> RoundedCornerShape(
        bottomStart = UCloneGroupedCornerRadius,
        bottomEnd = UCloneGroupedCornerRadius,
    )
    else -> RoundedCornerShape(0.dp)
}

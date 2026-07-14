package com.uclone.restore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.util.Formatters

@Composable
fun ActiveBackupRow(app: AppEntry, shape: Shape, showDivider: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.ucloneColors.groupedSurface,
        shadowElevation = 0.dp,
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.packageName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(app.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${Formatters.kilobytes(app.snapshotSizeKb)} · ${Formatters.time(app.lastSnapshotAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge("主动快照", MaterialTheme.colorScheme.onPrimaryContainer)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun PassiveBackupRow(
    backup: RestoreBackupEntry,
    app: AppEntry?,
    onOpenDetail: (() -> Unit)?,
    onRestore: () -> Unit,
    shape: Shape,
    showDivider: Boolean,
) {
    val stateLabel = when (backup.stateKind) {
        PassiveBackupStateKind.MAIN -> "MAIN 主数据"
        PassiveBackupStateKind.CLONE -> "CLONE 分数据"
        null -> "来源未标记"
    }
    val stateColor = when (backup.stateKind) {
        PassiveBackupStateKind.MAIN -> MaterialTheme.ucloneColors.success
        PassiveBackupStateKind.CLONE -> MaterialTheme.colorScheme.onPrimaryContainer
        null -> MaterialTheme.ucloneColors.warning
    }
    val retentionLabel = when {
        backup.rollbackId == "persistent_main" -> "固定 MAIN 返回点"
        backup.rollbackId == "persistent_clone" -> "旧版 CLONE 备份，不参与切换"
        backup.isPersistentStateBackup -> "旧版长期状态备份"
        backup.isActiveSwitchBackup -> "当前 MAIN 返回点"
        backup.isCloneRollback -> "分身推送回滚"
        else -> "事务被动备份"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.ucloneColors.groupedSurface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = onOpenDetail != null) { onOpenDetail?.invoke() }
                    .heightIn(min = 72.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(backup.packageName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        app?.label ?: backup.packageName,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (app != null) {
                        Text(
                            backup.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "$stateLabel · $retentionLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = stateColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${Formatters.kilobytes(backup.sizeKb)} · ${Formatters.time(backup.createdAt)} · 来源：${backup.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InlineActionButton(text = "恢复", onClick = onRestore, icon = Icons.Default.RestartAlt)
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

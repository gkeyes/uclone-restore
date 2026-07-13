package com.uclone.restore.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.util.Formatters

@Composable
fun ActiveBackupRow(app: AppEntry, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(app.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${Formatters.kilobytes(app.snapshotSizeKb)} · ${Formatters.time(app.lastSnapshotAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusBadge("active", MaterialTheme.colorScheme.primary)
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PassiveBackupRow(
    backup: RestoreBackupEntry,
    rootDir: String,
    app: AppEntry?,
    onOpenDetail: (() -> Unit)?,
    onRestore: () -> Unit,
) {
    val stateLabel = when (backup.stateKind) {
        PassiveBackupStateKind.MAIN -> "MAIN 主数据"
        PassiveBackupStateKind.CLONE -> "CLONE 分数据"
        null -> "来源未标记"
    }
    val stateColor = when (backup.stateKind) {
        PassiveBackupStateKind.MAIN -> MaterialTheme.ucloneColors.success
        PassiveBackupStateKind.CLONE -> MaterialTheme.colorScheme.primary
        null -> MaterialTheme.ucloneColors.warning
    }
    val retentionLabel = when {
        backup.isPersistentStateBackup -> "长期状态备份"
        backup.isActiveSwitchBackup -> "当前 MAIN 返回点"
        backup.isCloneRollback -> "分身推送回滚"
        else -> "事务被动备份"
    }
    val backupPath = if (backup.isCloneRollback) {
        "$rootDir/clone_rollback/${backup.packageName}/${backup.rollbackId}"
    } else {
        "$rootDir/rollback/${backup.packageName}/${backup.rollbackId}"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onOpenDetail != null) { onOpenDetail?.invoke() },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(backup.packageName)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        app?.label ?: backup.packageName,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$retentionLabel · ${Formatters.kilobytes(backup.sizeKb)} · ${Formatters.time(backup.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(stateLabel, stateColor)
                if (onOpenDetail != null) {
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SingleLinePathText(backupPath)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    backup.reason,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactActionButton(text = "恢复", onClick = onRestore, icon = Icons.Default.RestartAlt)
            }
        }
    }
}

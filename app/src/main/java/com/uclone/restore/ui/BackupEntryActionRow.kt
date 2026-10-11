package com.uclone.restore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uclone.restore.model.BackupEntry
import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.util.Formatters

@Composable
fun BackupEntryActionRow(
    backup: BackupEntry,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    appLabel: String? = null,
    showAppIcon: Boolean = false,
    showDivider: Boolean = false,
) {
    val largeText = useStackedLayoutForLargeText()
    val horizontalPadding = if (showAppIcon) 14.dp else 0.dp
    val actionStartPadding = if (showAppIcon) 62.dp else 0.dp
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (showAppIcon) {
                AppIcon(backup.packageName, size = 38.dp, cornerRadius = 10.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                appLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        backup.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    backup.badgeLabel()?.let { StatusBadge(it, MaterialTheme.colorScheme.primary) }
                }
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
        }
        if (largeText) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = actionStartPadding, end = horizontalPadding, bottom = 8.dp),
                horizontalAlignment = Alignment.End,
            ) {
                InlineActionButton("编辑", onEdit, icon = Icons.Default.Edit)
                CompactActionButton("恢复", onRestore, primary = true, icon = Icons.Default.RestartAlt)
                InlineActionButton("删除", onDelete, enabled = backup.canDelete, danger = true, icon = Icons.Default.Delete)
            }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = actionStartPadding, end = horizontalPadding, bottom = 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InlineActionButton("编辑", onEdit, icon = Icons.Default.Edit)
                    CompactActionButton("恢复", onRestore, primary = true, icon = Icons.Default.RestartAlt)
                    InlineActionButton("删除", onDelete, enabled = backup.canDelete, danger = true, icon = Icons.Default.Delete)
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = if (showAppIcon) 64.dp else 0.dp),
                thickness = 0.5.dp,
                    color = MaterialTheme.ucloneColors.separator,
            )
        }
    }
}

private fun BackupEntry.badgeLabel(): String? = when {
    ref.kind == BackupKind.CLONE_ROLLBACK && ref.id.equals("latest", ignoreCase = true) -> "最新"
    ref.kind == BackupKind.ACTIVE -> "active"
    else -> null
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

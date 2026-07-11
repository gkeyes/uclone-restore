package com.uclone.restore.sync

import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.TaskAudit
import com.uclone.restore.model.TaskType

internal object TaskAuditParser {
    private val deletedSnapshot = Regex("^DELETED_SNAPSHOT=(.+) SIZE_KB=([0-9]+) ITEMS=[0-9]+$")
    private val deletedBackup = Regex("^DELETED_(?:RESTORE_BACKUP|CLONE_ROLLBACK)=(.+) SIZE_KB=([0-9]+) ITEMS=[0-9]+$")
    private val resetWorkspace = Regex("^RESET_WORKSPACE_DONE root=(.+) deletedTargets=[0-9]+ sizeKb=([0-9]+)$")

    fun enrich(base: TaskAudit, type: TaskType, output: String): TaskAudit {
        val pattern = when (type) {
            TaskType.DELETE_SNAPSHOT -> deletedSnapshot
            TaskType.DELETE_RESTORE_BACKUP -> deletedBackup
            TaskType.RESET_WORKSPACE -> resetWorkspace
            else -> return base
        }
        val match = output.lineSequence().map(String::trim).mapNotNull(pattern::matchEntire).lastOrNull()
            ?: return base
        return base.copy(
            backupKind = when (type) {
                TaskType.DELETE_SNAPSHOT -> BackupKind.ACTIVE_SNAPSHOT
                TaskType.DELETE_RESTORE_BACKUP -> base.backupKind ?: BackupKind.PASSIVE_BACKUP
                TaskType.RESET_WORKSPACE -> BackupKind.WORKSPACE
                else -> base.backupKind
            },
            path = match.groupValues[1],
            sizeKb = match.groupValues[2].toLong(),
        )
    }
}

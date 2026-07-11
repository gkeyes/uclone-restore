package com.uclone.restore.sync

import com.uclone.restore.model.BackupKind
import com.uclone.restore.model.TaskAudit
import com.uclone.restore.model.TaskType
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskAuditParserTest {
    @Test
    fun deletedPassiveBackupCapturesExactPathAndPreDeleteSize() {
        val audit = TaskAuditParser.enrich(
            base = TaskAudit(source = "app", backupId = "backup-1"),
            type = TaskType.DELETE_RESTORE_BACKUP,
            output = "DELETED_RESTORE_BACKUP=/data/adb/uclone/rollback/com.example.app/backup-1 SIZE_KB=2048 ITEMS=10",
        )

        assertEquals("app", audit.source)
        assertEquals(BackupKind.PASSIVE_BACKUP, audit.backupKind)
        assertEquals("backup-1", audit.backupId)
        assertEquals("/data/adb/uclone/rollback/com.example.app/backup-1", audit.path)
        assertEquals(2048L, audit.sizeKb)
    }

    @Test
    fun resetWorkspaceCapturesRootAndDeletedSize() {
        val audit = TaskAuditParser.enrich(
            base = TaskAudit(source = "app", backupKind = BackupKind.WORKSPACE),
            type = TaskType.RESET_WORKSPACE,
            output = "RESET_WORKSPACE_DONE root=/data/adb/uclone deletedTargets=8 sizeKb=4096",
        )

        assertEquals(BackupKind.WORKSPACE, audit.backupKind)
        assertEquals("/data/adb/uclone", audit.path)
        assertEquals(4096L, audit.sizeKb)
    }
}

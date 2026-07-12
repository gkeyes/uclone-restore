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

    @Test
    fun workspaceOwnershipScanCapturesPathCountsAndSize() {
        val audit = TaskAuditParser.enrich(
            base = TaskAudit(source = "app"),
            type = TaskType.SCAN_WORKSPACE_OWNERSHIP,
            output = "WORKSPACE_OWNER_SCAN root=/data/adb/uclone total=22513 nonRoot=1240 sizeKb=4751360",
        )

        assertEquals(BackupKind.WORKSPACE, audit.backupKind)
        assertEquals("/data/adb/uclone", audit.path)
        assertEquals(22_513L, audit.totalEntries)
        assertEquals(1_240L, audit.nonRootEntries)
        assertEquals(4_751_360L, audit.sizeKb)
    }

    @Test
    fun workspaceOwnershipRepairUsesFinalVerificationScan() {
        val audit = TaskAuditParser.enrich(
            base = TaskAudit(source = "app"),
            type = TaskType.REPAIR_WORKSPACE_OWNERSHIP,
            output = """
                WORKSPACE_OWNER_SCAN root=/data/adb/uclone total=22513 nonRoot=1240 sizeKb=4751360
                WORKSPACE_OWNER_SCAN root=/data/adb/uclone total=22513 nonRoot=0 sizeKb=4751360
            """.trimIndent(),
        )

        assertEquals(22_513L, audit.totalEntries)
        assertEquals(0L, audit.nonRootEntries)
        assertEquals(4_751_360L, audit.sizeKb)
    }
}

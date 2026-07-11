package com.uclone.restore.ui

import com.uclone.restore.model.PassiveBackupStateKind
import com.uclone.restore.model.RestoreBackupEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DataBackupRowsTest {
    @Test
    fun `backup labels identify the actual source system`() {
        assertEquals("来源：主系统", backup(PassiveBackupStateKind.MAIN).sourceLabel())
        assertEquals("来源：分身系统", backup(PassiveBackupStateKind.CLONE).sourceLabel())
        assertEquals("来源未标记（旧备份）", backup(null).sourceLabel())
    }

    private fun backup(stateKind: PassiveBackupStateKind?) = RestoreBackupEntry(
        packageName = "com.example.app",
        rollbackId = "latest",
        createdAt = 0L,
        sizeKb = 0L,
        reason = "test",
        isActiveSwitchBackup = false,
        stateKind = stateKind,
    )
}

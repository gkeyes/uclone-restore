package com.uclone.restore.ui

import com.uclone.restore.sync.AppDataState
import com.uclone.restore.model.RestoreBackupEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertTrue

class AppDataStatePolicyTest {
    @Test
    fun missingWorkspaceStateResolvesToMain() {
        val state = UiState()

        assertEquals(AppDataState.Main, state.dataStateFor("com.example.app"))
    }

    @Test
    fun validSwitchMarkerResolvesToClone() {
        val state = UiState(
            switchRollbackIds = mapOf("com.example.app" to "main-return-point"),
        )

        assertEquals(AppDataState.Clone("main-return-point"), state.dataStateFor("com.example.app"))
    }

    @Test
    fun unknownWorkspaceStateWinsOverConflictingSwitchMarker() {
        val state = UiState(
            switchRollbackIds = mapOf("com.example.app" to "main-return-point"),
            unknownStatePackages = setOf("com.example.app"),
        )

        assertEquals(AppDataState.Unknown, state.dataStateFor("com.example.app"))
    }

    @Test
    fun unknownHomeActionOpensDetailsInsteadOfSwitchingOrRestoring() {
        assertEquals(HomePrimaryAction.OPEN_DETAILS, AppDataState.Unknown.homePrimaryAction)
    }

    @Test
    fun unknownDetailActionIsDisabledInsteadOfSwitchingOrRestoring() {
        assertEquals(DetailPrimaryAction.DISABLED_UNKNOWN, AppDataState.Unknown.detailPrimaryAction)
    }

    @Test
    fun activeMainReturnPointDeletionIsBlockedWithRecoveryInstructions() {
        val packageName = "com.example.app"
        val rollbackId = "main-return-point"
        val state = UiState(
            restoreBackups = listOf(
                RestoreBackupEntry(
                    packageName = packageName,
                    rollbackId = rollbackId,
                    createdAt = 1L,
                    sizeKb = 1L,
                    reason = "switch",
                    isActiveSwitchBackup = true,
                ),
            ),
        )

        assertEquals(
            "当前主数据返回点不能删除。请先还原主系统，或在 App 详情将状态设为未知后再删除。",
            state.restoreBackupDeletionBlockReason(packageName, rollbackId),
        )
    }

    @Test
    fun workspaceReadFailureClearsStaleStateAndMarksKnownAppsUnknown() {
        val packageName = "com.example.app"
        val state = UiState(
            apps = listOf(
                com.uclone.restore.model.AppEntry(
                    packageName = packageName,
                    label = "Example",
                    user0Installed = true,
                    user10Installed = true,
                    user0Uid = 10123,
                    user10Uid = 1010123,
                    isSystemApp = false,
                    riskLevel = com.uclone.restore.model.RiskLevel.NORMAL,
                    lastSnapshotAt = null,
                    snapshotSizeKb = null,
                    lastRestoreAt = null,
                ),
            ),
            selectedPackage = packageName,
            switchRollbackIds = mapOf(packageName to "stale-return-point"),
        )

        val failedClosed = state.failClosedAfterWorkspaceReadFailure()

        assertEquals(AppDataState.Unknown, failedClosed.dataStateFor(packageName))
        assertTrue(failedClosed.switchRollbackIds.isEmpty())
        assertTrue(failedClosed.restoreBackups.isEmpty())
    }
}

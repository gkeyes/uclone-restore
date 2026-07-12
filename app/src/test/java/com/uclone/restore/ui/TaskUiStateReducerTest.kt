package com.uclone.restore.ui

import com.uclone.restore.external.ExternalActionContract
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.CheckResult
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.RiskLevel
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.sync.SnapshotMetadata
import com.uclone.restore.sync.InterruptedTransaction
import com.uclone.restore.sync.TransactionRecoveryState
import com.uclone.restore.sync.WorkspaceIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskUiStateReducerTest {
    @Test
    fun credentialAndFavoriteSettingsChangesDoNotRequireFullRefresh() {
        val original = UCloneSettings(favoritePackages = setOf("com.example.one"))

        val credentialDiff = SettingsDiff.between(
            original,
            original.copy(cloneUnlockCredential = "changed-credential"),
        )
        val favoriteDiff = SettingsDiff.between(
            original,
            original.copy(favoritePackages = setOf("com.example.two")),
        )

        assertFalse(credentialDiff.requiresFullRefresh)
        assertFalse(credentialDiff.requiresShortcutSync)
        assertFalse(credentialDiff.requiresRuntimeValidation)
        assertFalse(favoriteDiff.requiresFullRefresh)
        assertTrue(favoriteDiff.requiresShortcutSync)
        assertFalse(favoriteDiff.requiresRuntimeValidation)
    }

    @Test
    fun workspaceLocationAndUserChangesRequireFullRefresh() {
        val original = UCloneSettings()

        assertTrue(SettingsDiff.between(original, original.copy(rootDir = "/data/adb/other")).requiresFullRefresh)
        assertTrue(SettingsDiff.between(original, original.copy(mainUserId = 1)).requiresFullRefresh)
        assertTrue(SettingsDiff.between(original, original.copy(cloneUserId = 11)).requiresFullRefresh)
        assertTrue(SettingsDiff.between(original, original.copy(rootDir = "/data/adb/other")).requiresRuntimeValidation)
        assertTrue(SettingsDiff.between(original, original.copy(mainUserId = 1)).requiresRuntimeValidation)
        assertTrue(SettingsDiff.between(original, original.copy(cloneUserId = 11)).requiresRuntimeValidation)
    }

    @Test
    fun runtimeTargetChangesAreBlockedByTasksAndIncompleteRecovery() {
        val original = UCloneSettings()
        val targetDiff = SettingsDiff.between(original, original.copy(rootDir = "/data/adb/uclone-next"))
        val ordinaryDiff = SettingsDiff.between(original, original.copy(excludeCache = false))
        val interrupted = InterruptedTransaction(
            requestId = "request-1",
            packageName = "com.example.app",
            stage = "TARGET_MUTATING",
            rollbackReady = true,
            targetMutated = true,
            committed = false,
            gateState = "HELD",
            targetUserId = 0,
        )

        assertNull(targetDiff.runtimeTargetChangeBlockingMessage(false, TransactionRecoveryState.Ready))
        assertTrue(targetDiff.runtimeTargetChangeBlockingMessage(true, TransactionRecoveryState.Ready)!!.contains("任务"))
        assertTrue(
            targetDiff.runtimeTargetChangeBlockingMessage(
                false,
                TransactionRecoveryState.Required(listOf(interrupted)),
            )!!.contains("未完成事务"),
        )
        assertTrue(
            targetDiff.runtimeTargetChangeBlockingMessage(
                false,
                TransactionRecoveryState.Recovering(interrupted, emptyList()),
            )!!.contains("正在恢复"),
        )
        assertTrue(
            targetDiff.runtimeTargetChangeBlockingMessage(
                false,
                TransactionRecoveryState.RootTaskStillRunning(listOf(interrupted), interrupted.requestId),
            )!!.contains("仍在运行"),
        )
        assertTrue(
            targetDiff.runtimeTargetChangeBlockingMessage(
                false,
                TransactionRecoveryState.Failed("probe failed"),
            )!!.contains("probe failed"),
        )
        assertNull(ordinaryDiff.runtimeTargetChangeBlockingMessage(true, TransactionRecoveryState.Scanning))
    }

    @Test
    fun taskRefreshPoliciesSelectOnlyExpectedScopes() {
        val expected = mapOf(
            TaskType.CAPTURE_SNAPSHOT_FROM_CLONE to RefreshPolicy(environment = true, workspace = true),
            TaskType.RESTORE_SNAPSHOT_TO_MAIN to RefreshPolicy(workspace = true),
            TaskType.ROLLBACK_MAIN_DATA to RefreshPolicy(workspace = true, shortcuts = true),
            TaskType.RESTORE_FROM_CLONE_LATEST to RefreshPolicy(environment = true, workspace = true),
            TaskType.SWITCH_TO_CLONE_STATE to RefreshPolicy(environment = true, workspace = true, shortcuts = true),
            TaskType.PUSH_MAIN_TO_CLONE to RefreshPolicy(environment = true, workspace = true),
            TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE to RefreshPolicy(environment = true, workspace = true),
            TaskType.RESTORE_SWITCH_MAIN_STATE to RefreshPolicy(environment = true, workspace = true, shortcuts = true),
            TaskType.RESET_SWITCH_STATE to RefreshPolicy(workspace = true, shortcuts = true),
            TaskType.DELETE_SNAPSHOT to RefreshPolicy(workspace = true),
            TaskType.DELETE_RESTORE_BACKUP to RefreshPolicy(workspace = true, shortcuts = true),
            TaskType.DELETE_CLONE_ROLLBACK to RefreshPolicy(workspace = true),
            TaskType.PROBE_CLONE_CE to RefreshPolicy(environment = true),
            TaskType.UNLOCK_CLONE_WITH_CREDENTIAL to RefreshPolicy(environment = true),
            TaskType.DEBUG_CLONE_SYSTEM to RefreshPolicy(environment = true),
            TaskType.AUDIT_RESTORE_CONSISTENCY to RefreshPolicy(),
            TaskType.CLEAR_LOGS to RefreshPolicy(),
            TaskType.RESET_WORKSPACE to RefreshPolicy(environment = true, workspace = true, shortcuts = true),
            TaskType.START_CLONE_USER to RefreshPolicy(environment = true),
            TaskType.SWITCH_TO_CLONE_USER to RefreshPolicy(environment = true),
            TaskType.STOP_CLONE_USER to RefreshPolicy(environment = true),
            TaskType.SCAN_WORKSPACE_OWNERSHIP to RefreshPolicy(),
            TaskType.REPAIR_WORKSPACE_OWNERSHIP to RefreshPolicy(),
            TaskType.INSTALL_TO_OTHER_USER to RefreshPolicy(apps = true, shortcuts = true),
            TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER to RefreshPolicy(apps = true, shortcuts = true),
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER to
                RefreshPolicy(environment = true, workspace = true, shortcuts = true, apps = true),
            TaskType.RECOVER_INTERRUPTED_TRANSACTION to RefreshPolicy(),
        )

        assertEquals(TaskType.entries.toSet(), expected.keys)
        expected.forEach { (type, policy) ->
            assertEquals(policy, RefreshPolicy.forTask(type), type.name)
        }
    }

    @Test
    fun progressMirrorsPersistentTaskLifecycle() {
        val accepted = task(TaskType.SWITCH_TO_CLONE_STATE, TaskStatus.ACCEPTED)
        val terminal = accepted.copy(status = TaskStatus.SUCCESS, message = "完成")

        val runningState = TaskUiStateReducer.progress(UiState(), TaskProgress(accepted))
        val finishedState = TaskUiStateReducer.progress(runningState, TaskProgress(terminal))

        assertTrue(runningState.busy)
        assertFalse(finishedState.busy)
        assertEquals("完成", finishedState.message)
        assertEquals(terminal, finishedState.currentTask.task)
    }

    @Test
    fun successfulResetClearsEveryWorkspaceDerivedUiField() {
        val app = AppEntry(
            packageName = "com.example.app",
            label = "Example",
            user0Installed = true,
            user10Installed = true,
            user0Uid = 10123,
            user10Uid = 1010123,
            isSystemApp = false,
            riskLevel = RiskLevel.NORMAL,
            lastSnapshotAt = 1234,
            snapshotSizeKb = 2048,
            lastRestoreAt = 5678,
        )
        val backup = RestoreBackupEntry(
            packageName = app.packageName,
            rollbackId = "rollback-1",
            createdAt = 1234,
            sizeKb = 100,
            reason = "test",
            isActiveSwitchBackup = true,
        )
        val initial = UiState(
            apps = listOf(app),
            busy = true,
            history = listOf(task(TaskType.RESTORE_SNAPSHOT_TO_MAIN, TaskStatus.SUCCESS)),
            rollbackIds = listOf("rollback-1"),
            restoreBackups = listOf(backup),
            cloneRollbackBackups = listOf(backup.copy(isCloneRollback = true)),
            switchRollbackIds = mapOf(app.packageName to "rollback-1"),
        )
        val reset = task(TaskType.RESET_WORKSPACE, TaskStatus.SUCCESS)
        val refreshed = TaskUiStateReducer.refreshed(
            initial,
            reset,
            TaskRefreshSnapshot(
                environment = environment(),
                history = emptyList(),
                workspaceIndex = WorkspaceIndex(),
            ),
        )

        assertFalse(refreshed.busy)
        assertEquals(emptyList(), refreshed.history)
        assertEquals(emptyList(), refreshed.rollbackIds)
        assertEquals(emptyList(), refreshed.restoreBackups)
        assertEquals(emptyList(), refreshed.cloneRollbackBackups)
        assertEquals(emptyMap(), refreshed.switchRollbackIds)
        assertNull(refreshed.apps.single().lastSnapshotAt)
        assertNull(refreshed.apps.single().snapshotSizeKb)
        assertNull(refreshed.apps.single().lastRestoreAt)
    }

    @Test
    fun workspaceRefreshUpdatesSelectedPackageWithoutASecondQuery() {
        val app = AppEntry(
            packageName = "com.example.app",
            label = "Example",
            user0Installed = true,
            user10Installed = true,
            user0Uid = 10123,
            user10Uid = 1010123,
            isSystemApp = false,
            riskLevel = RiskLevel.NORMAL,
            lastSnapshotAt = null,
            snapshotSizeKb = null,
            lastRestoreAt = null,
        )
        val backup = RestoreBackupEntry(
            packageName = app.packageName,
            rollbackId = "rollback-1",
            createdAt = 1234,
            sizeKb = 100,
            reason = "test",
            isActiveSwitchBackup = true,
        )
        val workspace = WorkspaceIndex(
            snapshots = mapOf(app.packageName to SnapshotMetadata(updatedAt = 5678, sizeKb = 2048)),
            switchMarkers = mapOf(app.packageName to backup.rollbackId),
            mainRollbackBackups = listOf(backup),
        )
        val initial = UiState(apps = listOf(app), selectedPackage = app.packageName)

        val refreshed = TaskUiStateReducer.refreshed(
            initial,
            task(TaskType.RESTORE_SNAPSHOT_TO_MAIN, TaskStatus.SUCCESS),
            TaskRefreshSnapshot(history = emptyList(), workspaceIndex = workspace),
        )

        assertEquals(5678L, refreshed.selectedApp?.lastSnapshotAt)
        assertEquals(2048L, refreshed.selectedApp?.snapshotSizeKb)
        assertEquals(listOf("rollback-1"), refreshed.rollbackIds)
        assertEquals("rollback-1", refreshed.selectedSwitchRollbackId)
    }

    @Test
    fun installRefreshKeepsNewInstallationStateAndWorkspaceMetadata() {
        val before = AppEntry(
            packageName = "com.example.app",
            label = "Example",
            user0Installed = true,
            user10Installed = false,
            user0Uid = 10123,
            user10Uid = null,
            isSystemApp = false,
            riskLevel = RiskLevel.NORMAL,
            lastSnapshotAt = 100L,
            snapshotSizeKb = 200L,
            lastRestoreAt = null,
        )
        val installed = before.copy(user10Installed = true, user10Uid = 1010123)
        val workspace = WorkspaceIndex(
            snapshots = mapOf(before.packageName to SnapshotMetadata(updatedAt = 300L, sizeKb = 400L)),
        )

        val refreshed = TaskUiStateReducer.refreshed(
            state = UiState(apps = listOf(before), selectedPackage = before.packageName),
            task = task(TaskType.INSTALL_AND_SYNC_TO_OTHER_USER, TaskStatus.SUCCESS),
            snapshot = TaskRefreshSnapshot(
                history = emptyList(),
                workspaceIndex = workspace,
                apps = listOf(installed),
            ),
        )

        assertTrue(refreshed.selectedApp?.user10Installed == true)
        assertEquals(1010123, refreshed.selectedApp?.user10Uid)
        assertEquals(300L, refreshed.selectedApp?.lastSnapshotAt)
        assertEquals(400L, refreshed.selectedApp?.snapshotSizeKb)
    }

    @Test
    fun everyUiActionHasAUniqueExternalOperation() {
        assertEquals(UiTaskAction.entries.size, UiTaskAction.entries.map { it.operation }.toSet().size)
        assertTrue(UiTaskAction.entries.any { it.operation == ExternalActionContract.OPERATION_RESET_SWITCH_STATE })
        assertTrue(UiTaskAction.entries.any { it.operation == ExternalActionContract.OPERATION_RESET_WORKSPACE })
        assertTrue(UiTaskAction.entries.any { it.operation == ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST })
    }

    private fun task(type: TaskType, status: TaskStatus) = TaskRecord(
        id = 1,
        requestId = "request-1",
        packageName = "com.example.app",
        type = type,
        startedAt = 100,
        finishedAt = if (status.isTerminal) 200 else null,
        status = status,
        logPath = "/tmp/task.log",
        message = status.name,
    )

    private fun environment() = EnvironmentStatus(
        root = CheckResult(true, "root"),
        currentUser = "0",
        user0Present = true,
        user10Present = true,
        user10State = "User is not started: 10",
        dataAdbWritable = CheckResult(true, "ok"),
        snapshotDirReady = CheckResult(true, "ok"),
    )
}

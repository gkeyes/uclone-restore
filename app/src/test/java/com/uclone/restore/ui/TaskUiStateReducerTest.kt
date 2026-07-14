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
import com.uclone.restore.sync.WorkspaceIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskUiStateReducerTest {
    @Test
    fun onlyRuntimeTargetSettingsRequireFullRefresh() {
        val original = UCloneSettings(favoritePackages = setOf("com.example.one"))

        assertTrue(SettingsDiff.between(original, original.copy(rootDir = "/data/adb/other")).requiresFullRefresh)
        assertTrue(SettingsDiff.between(original, original.copy(mainUserId = 1)).requiresFullRefresh)
        assertTrue(SettingsDiff.between(original, original.copy(cloneUserId = 11)).requiresFullRefresh)
        assertFalse(SettingsDiff.between(original, original.copy(includeCe = false)).requiresFullRefresh)
        assertFalse(SettingsDiff.between(original, original.copy(cloneUnlockCredential = "changed")).requiresFullRefresh)

        val favoriteDiff = SettingsDiff.between(
            original,
            original.copy(favoritePackages = setOf("com.example.two")),
        )
        assertFalse(favoriteDiff.requiresFullRefresh)
        assertTrue(favoriteDiff.requiresShortcutSync)
    }

    @Test
    fun everyTaskHasAnExplicitRefreshPolicy() {
        val expected = mapOf(
            TaskType.CAPTURE_SNAPSHOT_FROM_CLONE to cloneWorkspacePolicy(),
            TaskType.RESTORE_SNAPSHOT_TO_MAIN to workspacePolicy(),
            TaskType.ROLLBACK_MAIN_DATA to workspacePolicy(),
            TaskType.RESTORE_FROM_CLONE_LATEST to cloneWorkspacePolicy(),
            TaskType.SWITCH_TO_CLONE_STATE to cloneWorkspacePolicy(),
            TaskType.PUSH_MAIN_TO_CLONE to cloneWorkspacePolicy(),
            TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE to cloneWorkspacePolicy(),
            TaskType.RESTORE_SWITCH_MAIN_STATE to cloneWorkspacePolicy(),
            TaskType.UPDATE_MAIN_RETURN_POINT to workspacePolicy(),
            TaskType.DELETE_SNAPSHOT to workspacePolicy(),
            TaskType.DELETE_RESTORE_BACKUP to workspacePolicy(),
            TaskType.PROBE_CLONE_CE to fullEnvironmentPolicy(),
            TaskType.UNLOCK_CLONE_WITH_CREDENTIAL to fullEnvironmentPolicy(),
            TaskType.DEBUG_CLONE_SYSTEM to fullEnvironmentPolicy(),
            TaskType.AUDIT_RESTORE_CONSISTENCY to RefreshPolicy(),
            TaskType.CLEAR_LOGS to RefreshPolicy(),
            TaskType.RESET_WORKSPACE to RefreshPolicy(
                environment = EnvironmentRefresh.FULL,
                workspace = true,
                shortcuts = true,
            ),
            TaskType.SCAN_WORKSPACE_OWNERSHIP to RefreshPolicy(),
            TaskType.REPAIR_WORKSPACE_OWNERSHIP to RefreshPolicy(),
            TaskType.INSTALL_TO_OTHER_USER to RefreshPolicy(apps = true, shortcuts = true),
            TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER to RefreshPolicy(apps = true, shortcuts = true),
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER to RefreshPolicy(
                environment = EnvironmentRefresh.CLONE_STATE_ONLY,
                workspace = true,
                shortcuts = true,
                apps = true,
            ),
            TaskType.START_CLONE_USER to fullEnvironmentPolicy(),
            TaskType.SWITCH_TO_CLONE_USER to fullEnvironmentPolicy(),
            TaskType.STOP_CLONE_USER to fullEnvironmentPolicy(),
        )

        assertEquals(TaskType.entries.toSet(), expected.keys)
        expected.forEach { (type, policy) -> assertEquals(policy, RefreshPolicy.forTask(type), type.name) }
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
    fun terminalOnlyWorkspaceDeliveryInvalidatesCacheExactlyOnce() {
        val tracker = WorkspaceCacheInvalidationTracker()
        val terminal = task(TaskType.SWITCH_TO_CLONE_STATE, TaskStatus.SUCCESS)

        assertTrue(tracker.shouldInvalidate(terminal))
        assertFalse(tracker.shouldInvalidate(terminal))
        assertTrue(
            tracker.shouldInvalidate(
                terminal.copy(id = 2, packageName = "com.example.second"),
            ),
        )
        assertFalse(
            tracker.shouldInvalidate(
                task(TaskType.CLEAR_LOGS, TaskStatus.SUCCESS).copy(id = 3, requestId = "request-2"),
            ),
        )
        assertTrue(tracker.shouldInvalidate(terminal.copy(id = 4, requestId = "request-3")))
    }

    @Test
    fun terminalRefreshDeduplicationUsesTaskIdInsteadOfRequestId() {
        val tracker = TaskInstanceDeduplicator()
        val first = task(TaskType.SWITCH_TO_CLONE_STATE, TaskStatus.SUCCESS)

        assertTrue(tracker.shouldHandle(first))
        assertFalse(tracker.shouldHandle(first))
        assertTrue(tracker.shouldHandle(first.copy(id = 2, packageName = "com.example.second")))
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
        assertEquals(emptySet(), refreshed.confirmedMainPackages)
        assertNull(refreshed.apps.single().lastSnapshotAt)
        assertNull(refreshed.apps.single().snapshotSizeKb)
        assertNull(refreshed.apps.single().lastRestoreAt)
    }

    @Test
    fun packageOnlyRefreshPreservesWorkspaceMetadataAndEnvironment() {
        val existing = AppEntry(
            packageName = "com.example.app",
            label = "Old label",
            user0Installed = true,
            user10Installed = false,
            user0Uid = 10123,
            user10Uid = null,
            isSystemApp = false,
            riskLevel = RiskLevel.NORMAL,
            lastSnapshotAt = 1234,
            snapshotSizeKb = 2048,
            lastRestoreAt = 5678,
        )
        val initialEnvironment = environment()
        val initial = UiState(apps = listOf(existing), environment = initialEnvironment)
        val reloaded = existing.copy(
            label = "New label",
            user10Installed = true,
            user10Uid = 1010123,
            lastSnapshotAt = null,
            snapshotSizeKb = null,
            lastRestoreAt = null,
        )

        val refreshed = TaskUiStateReducer.refreshed(
            initial,
            task(TaskType.INSTALL_TO_OTHER_USER, TaskStatus.SUCCESS),
            TaskRefreshSnapshot(history = emptyList(), apps = listOf(reloaded)),
        )

        assertEquals(initialEnvironment, refreshed.environment)
        assertEquals("New label", refreshed.apps.single().label)
        assertTrue(refreshed.apps.single().user10Installed)
        assertEquals(1234, refreshed.apps.single().lastSnapshotAt)
        assertEquals(2048, refreshed.apps.single().snapshotSizeKb)
        assertEquals(5678, refreshed.apps.single().lastRestoreAt)
    }

    @Test
    fun failedWorkspaceReadMarksEveryVisibleAppUnknown() {
        val apps = listOf("com.example.one", "com.example.two").mapIndexed { index, packageName ->
            AppEntry(
                packageName = packageName,
                label = "App $index",
                user0Installed = true,
                user10Installed = true,
                user0Uid = 10123 + index,
                user10Uid = 1010123 + index,
                isSystemApp = false,
                riskLevel = RiskLevel.NORMAL,
                lastSnapshotAt = null,
                snapshotSizeKb = null,
                lastRestoreAt = null,
            )
        }
        val refreshed = TaskUiStateReducer.refreshed(
            UiState(
                apps = apps,
                confirmedMainPackages = apps.mapTo(linkedSetOf()) { it.packageName },
            ),
            task(TaskType.SWITCH_TO_CLONE_STATE, TaskStatus.FAILED),
            TaskRefreshSnapshot(
                history = emptyList(),
                workspaceIndex = WorkspaceIndex(readSucceeded = false),
            ),
        )

        assertEquals(apps.mapTo(linkedSetOf()) { it.packageName }, refreshed.unknownSwitchPackages)
        assertEquals(emptySet(), refreshed.confirmedMainPackages)
    }

    @Test
    fun everyUiActionHasAUniqueExternalOperation() {
        val expectedOperations = setOf(
            ExternalActionContract.OPERATION_BACKUP_DEFAULT,
            ExternalActionContract.OPERATION_RESTORE_LATEST_BACKUP,
            ExternalActionContract.OPERATION_RESTORE_FROM_CLONE_LATEST,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE,
            ExternalActionContract.OPERATION_SWITCH_OR_RESTORE,
            ExternalActionContract.OPERATION_RESTORE_MAIN,
            ExternalActionContract.OPERATION_UPDATE_MAIN_RETURN_POINT,
            ExternalActionContract.OPERATION_PUSH_MAIN_TO_CLONE,
            ExternalActionContract.OPERATION_RESTORE_LATEST_CLONE_ROLLBACK,
            ExternalActionContract.OPERATION_RESTORE_ROLLBACK,
            ExternalActionContract.OPERATION_DELETE_SNAPSHOT,
            ExternalActionContract.OPERATION_DELETE_RESTORE_BACKUP,
            ExternalActionContract.OPERATION_PROBE_CLONE_CE,
            ExternalActionContract.OPERATION_UNLOCK_CLONE,
            ExternalActionContract.OPERATION_DEBUG_CLONE_SYSTEM,
            ExternalActionContract.OPERATION_AUDIT_RESTORE,
            ExternalActionContract.OPERATION_CLEAR_LOGS,
            ExternalActionContract.OPERATION_RESET_WORKSPACE,
            ExternalActionContract.OPERATION_SCAN_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_REPAIR_WORKSPACE_OWNERSHIP,
            ExternalActionContract.OPERATION_INSTALL_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            ExternalActionContract.OPERATION_INSTALL_AND_SYNC_TO_OTHER_USER,
            ExternalActionContract.OPERATION_START_CLONE_USER,
            ExternalActionContract.OPERATION_SWITCH_TO_CLONE_USER,
            ExternalActionContract.OPERATION_STOP_CLONE_USER,
        )
        val actualOperations = UiTaskAction.entries.map { it.operation }

        assertEquals(expectedOperations, actualOperations.toSet())
        assertEquals(actualOperations.size, actualOperations.toSet().size)
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

    private fun workspacePolicy() = RefreshPolicy(workspace = true, shortcuts = true)

    private fun cloneWorkspacePolicy() = RefreshPolicy(
        environment = EnvironmentRefresh.CLONE_STATE_ONLY,
        workspace = true,
        shortcuts = true,
    )

    private fun fullEnvironmentPolicy() = RefreshPolicy(environment = EnvironmentRefresh.FULL)
}

package com.uclone.restore.ui

import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.User10CeState
import com.uclone.restore.sync.WorkspaceIndex

internal data class TaskRefreshSnapshot(
    val history: List<TaskRecord>,
    val environment: EnvironmentStatus? = null,
    val workspaceIndex: WorkspaceIndex? = null,
    val apps: List<AppEntry>? = null,
)

internal data class SettingsDiff(
    val requiresFullRefresh: Boolean,
    val requiresShortcutSync: Boolean,
) {
    companion object {
        fun between(previous: UCloneSettings, current: UCloneSettings): SettingsDiff = SettingsDiff(
            requiresFullRefresh = previous.mainUserId != current.mainUserId ||
                previous.cloneUserId != current.cloneUserId ||
                previous.rootDir != current.rootDir,
            requiresShortcutSync = previous.favoritePackages != current.favoritePackages,
        )
    }
}

internal enum class EnvironmentRefresh {
    NONE,
    CLONE_STATE_ONLY,
    FULL,
}

internal data class RefreshPolicy(
    val environment: EnvironmentRefresh = EnvironmentRefresh.NONE,
    val workspace: Boolean = false,
    val shortcuts: Boolean = false,
    val apps: Boolean = false,
) {
    companion object {
        fun forTask(type: TaskType): RefreshPolicy = when (type) {
            TaskType.CAPTURE_SNAPSHOT_FROM_CLONE,
            TaskType.RESTORE_FROM_CLONE_LATEST,
            TaskType.SWITCH_TO_CLONE_STATE,
            TaskType.PUSH_MAIN_TO_CLONE,
            TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
            TaskType.RESTORE_SWITCH_MAIN_STATE,
            -> RefreshPolicy(
                environment = EnvironmentRefresh.CLONE_STATE_ONLY,
                workspace = true,
                shortcuts = true,
            )
            TaskType.RESTORE_SNAPSHOT_TO_MAIN,
            TaskType.ROLLBACK_MAIN_DATA,
            TaskType.UPDATE_MAIN_RETURN_POINT,
            TaskType.DELETE_SNAPSHOT,
            TaskType.DELETE_RESTORE_BACKUP,
            -> RefreshPolicy(workspace = true, shortcuts = true)
            TaskType.PROBE_CLONE_CE,
            TaskType.UNLOCK_CLONE_WITH_CREDENTIAL,
            TaskType.DEBUG_CLONE_SYSTEM,
            TaskType.START_CLONE_USER,
            TaskType.SWITCH_TO_CLONE_USER,
            TaskType.STOP_CLONE_USER,
            -> RefreshPolicy(environment = EnvironmentRefresh.FULL)
            TaskType.RESET_WORKSPACE -> RefreshPolicy(
                environment = EnvironmentRefresh.FULL,
                workspace = true,
                shortcuts = true,
            )
            TaskType.INSTALL_TO_OTHER_USER,
            TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            -> RefreshPolicy(apps = true, shortcuts = true)
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER -> RefreshPolicy(
                environment = EnvironmentRefresh.CLONE_STATE_ONLY,
                workspace = true,
                shortcuts = true,
                apps = true,
            )
            TaskType.AUDIT_RESTORE_CONSISTENCY,
            TaskType.CLEAR_LOGS,
            TaskType.SCAN_WORKSPACE_OWNERSHIP,
            TaskType.REPAIR_WORKSPACE_OWNERSHIP,
            -> RefreshPolicy()
        }
    }
}

internal class TaskInstanceDeduplicator {
    private var handledTaskId: Long? = null

    fun shouldHandle(task: TaskRecord?): Boolean {
        val taskId = task?.id ?: return false
        if (handledTaskId == taskId) return false
        handledTaskId = taskId
        return true
    }
}

internal class WorkspaceCacheInvalidationTracker {
    private val taskInstances = TaskInstanceDeduplicator()

    fun shouldInvalidate(task: TaskRecord?): Boolean {
        if (task == null || !RefreshPolicy.forTask(task.type).workspace) return false
        return taskInstances.shouldHandle(task)
    }
}

internal object TaskUiStateReducer {
    fun progress(state: UiState, progress: TaskProgress): UiState {
        val task = progress.task
        return state.copy(
            currentTask = progress,
            busy = task?.status?.isTerminal == false,
            message = task?.message ?: state.message,
        )
    }

    fun refreshed(state: UiState, task: TaskRecord, snapshot: TaskRefreshSnapshot): UiState {
        val reset = task.type == TaskType.RESET_WORKSPACE && task.status.isSuccessful
        val workspace = snapshot.workspaceIndex
        val previousApps = state.apps.associateBy { it.packageName }
        val refreshedApps = snapshot.apps ?: state.apps
        val resolvedApps = when {
            reset -> refreshedApps.map {
                it.copy(lastSnapshotAt = null, snapshotSizeKb = null, lastRestoreAt = null)
            }
            workspace != null -> refreshedApps.map { app ->
                val metadata = workspace.snapshots[app.packageName]
                val previous = previousApps[app.packageName]
                app.copy(
                    lastSnapshotAt = metadata?.updatedAt,
                    snapshotSizeKb = metadata?.sizeKb,
                    lastRestoreAt = previous?.lastRestoreAt ?: app.lastRestoreAt,
                )
            }
            snapshot.apps != null -> refreshedApps.map { app ->
                val previous = previousApps[app.packageName]
                app.copy(
                    lastSnapshotAt = previous?.lastSnapshotAt,
                    snapshotSizeKb = previous?.snapshotSizeKb,
                    lastRestoreAt = previous?.lastRestoreAt,
                )
            }
            else -> state.apps
        }
        val message = if (
            task.status.isSuccessful && snapshot.environment?.user10CeState is User10CeState.NotStarted
        ) {
            "${task.message}，分身已关闭"
        } else {
            task.message
        }
        return state.copy(
            busy = false,
            environment = snapshot.environment ?: state.environment,
            apps = resolvedApps,
            history = snapshot.history,
            rollbackIds = when {
                reset -> emptyList()
                workspace != null && state.selectedPackage != null -> workspace.rollbackIds(state.selectedPackage)
                else -> state.rollbackIds
            },
            restoreBackups = when {
                reset -> emptyList()
                workspace != null -> workspace.restoreBackups
                else -> state.restoreBackups
            },
            cloneRollbackBackups = when {
                reset -> emptyList()
                workspace != null -> workspace.cloneRollbackBackups
                else -> state.cloneRollbackBackups
            },
            switchRollbackIds = when {
                reset -> emptyMap()
                workspace != null -> workspace.switchMarkers
                else -> state.switchRollbackIds
            },
            confirmedMainPackages = when {
                reset -> emptySet()
                workspace != null -> workspace.confirmedMainPackages
                else -> state.confirmedMainPackages
            },
            unknownSwitchPackages = when {
                reset -> emptySet()
                workspace?.readSucceeded == false -> resolvedApps.mapTo(linkedSetOf()) { it.packageName }
                workspace != null -> workspace.unknownSwitchPackages
                else -> state.unknownSwitchPackages
            },
            message = message,
        )
    }
}

package com.uclone.restore.ui

import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.AppEntry
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.User10CeState
import com.uclone.restore.sync.TransactionRecoveryState
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
    val requiresRuntimeValidation: Boolean,
) {
    companion object {
        fun between(previous: UCloneSettings, current: UCloneSettings): SettingsDiff {
            val runtimeTargetChanged = previous.mainUserId != current.mainUserId ||
                previous.cloneUserId != current.cloneUserId ||
                previous.rootDir != current.rootDir
            return SettingsDiff(
                requiresFullRefresh = runtimeTargetChanged,
                requiresShortcutSync = previous.favoritePackages != current.favoritePackages,
                requiresRuntimeValidation = runtimeTargetChanged,
            )
        }
    }
}

internal fun SettingsDiff.runtimeTargetChangeBlockingMessage(
    taskBusy: Boolean,
    recoveryState: TransactionRecoveryState,
): String? {
    if (!requiresRuntimeValidation) return null
    if (taskBusy) return "数据任务正在执行，完成前不能修改用户 ID 或工作目录"
    return when (recoveryState) {
        TransactionRecoveryState.Ready -> null
        TransactionRecoveryState.Scanning -> "正在检查上次事务，完成前不能修改用户 ID 或工作目录"
        is TransactionRecoveryState.RootTaskStillRunning ->
            "上次 Root 数据任务仍在运行，完成前不能修改用户 ID 或工作目录"
        is TransactionRecoveryState.Required ->
            "存在未完成事务，安全恢复前不能修改用户 ID 或工作目录"
        is TransactionRecoveryState.Recovering ->
            "正在恢复未完成事务，完成前不能修改用户 ID 或工作目录"
        is TransactionRecoveryState.Failed ->
            "事务状态尚未确认，不能修改用户 ID 或工作目录：${recoveryState.message}"
    }
}

internal data class RefreshPolicy(
    val environment: Boolean = false,
    val workspace: Boolean = false,
    val shortcuts: Boolean = false,
    val apps: Boolean = false,
) {
    companion object {
        fun forTask(type: TaskType): RefreshPolicy = when (type) {
            TaskType.CAPTURE_SNAPSHOT_FROM_CLONE -> RefreshPolicy(environment = true, workspace = true)
            TaskType.RESTORE_SNAPSHOT_TO_MAIN,
            TaskType.DELETE_SNAPSHOT,
            -> RefreshPolicy(workspace = true)
            TaskType.ROLLBACK_MAIN_DATA -> RefreshPolicy(workspace = true, shortcuts = true)
            TaskType.RESTORE_FROM_CLONE_LATEST,
            TaskType.PUSH_MAIN_TO_CLONE,
            TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE,
            -> RefreshPolicy(environment = true, workspace = true)
            TaskType.SWITCH_TO_CLONE_STATE,
            TaskType.RESTORE_SWITCH_MAIN_STATE,
            -> RefreshPolicy(environment = true, workspace = true, shortcuts = true)
            TaskType.RESET_SWITCH_STATE -> RefreshPolicy(workspace = true, shortcuts = true)
            TaskType.DELETE_RESTORE_BACKUP -> RefreshPolicy(workspace = true, shortcuts = true)
            TaskType.DELETE_CLONE_ROLLBACK -> RefreshPolicy(workspace = true)
            TaskType.PROBE_CLONE_CE,
            TaskType.UNLOCK_CLONE_WITH_CREDENTIAL,
            TaskType.DEBUG_CLONE_SYSTEM,
            TaskType.START_CLONE_USER,
            TaskType.SWITCH_TO_CLONE_USER,
            TaskType.STOP_CLONE_USER,
            -> RefreshPolicy(environment = true)
            TaskType.RESET_WORKSPACE -> RefreshPolicy(environment = true, workspace = true, shortcuts = true)
            TaskType.SCAN_WORKSPACE_OWNERSHIP,
            TaskType.REPAIR_WORKSPACE_OWNERSHIP -> RefreshPolicy()
            TaskType.INSTALL_TO_OTHER_USER,
            TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER,
            -> RefreshPolicy(apps = true, shortcuts = true)
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER ->
                RefreshPolicy(environment = true, workspace = true, shortcuts = true, apps = true)
            TaskType.AUDIT_RESTORE_CONSISTENCY,
            TaskType.CLEAR_LOGS,
            TaskType.RECOVER_INTERRUPTED_TRANSACTION,
            -> RefreshPolicy()
        }
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
        val refreshedApps = snapshot.apps ?: state.apps
        val resolvedApps = when {
            reset -> refreshedApps.map { it.copy(lastSnapshotAt = null, snapshotSizeKb = null, lastRestoreAt = null) }
            workspace != null -> refreshedApps.map { app ->
                val metadata = workspace.snapshots[app.packageName]
                app.copy(lastSnapshotAt = metadata?.updatedAt, snapshotSizeKb = metadata?.sizeKb)
            }
            else -> refreshedApps
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
            message = message,
        )
    }
}

package com.uclone.restore.ui

import com.uclone.restore.model.EnvironmentStatus
import com.uclone.restore.model.RestoreBackupEntry
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.User10CeState

internal data class TaskRefreshSnapshot(
    val environment: EnvironmentStatus?,
    val history: List<TaskRecord>,
    val restoreBackups: List<RestoreBackupEntry>,
    val cloneRollbackBackups: List<RestoreBackupEntry>,
    val switchRollbackIds: Map<String, String>,
)

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
        val message = if (
            task.status.isSuccessful && snapshot.environment?.user10CeState is User10CeState.NotStarted
        ) {
            "${task.message}，分身已关闭"
        } else {
            task.message
        }
        return state.copy(
            busy = false,
            environment = snapshot.environment,
            apps = if (reset) {
                state.apps.map { it.copy(lastSnapshotAt = null, snapshotSizeKb = null, lastRestoreAt = null) }
            } else {
                state.apps
            },
            history = snapshot.history,
            rollbackIds = if (reset) emptyList() else state.rollbackIds,
            restoreBackups = snapshot.restoreBackups,
            cloneRollbackBackups = snapshot.cloneRollbackBackups,
            switchRollbackIds = snapshot.switchRollbackIds,
            message = message,
        )
    }
}

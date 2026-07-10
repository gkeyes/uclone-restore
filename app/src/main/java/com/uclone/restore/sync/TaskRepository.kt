package com.uclone.restore.sync

import com.uclone.restore.model.TaskMetrics
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.ShellResult
import kotlinx.coroutines.flow.StateFlow

interface TaskRepository {
    val history: StateFlow<List<TaskRecord>>
    val progress: StateFlow<TaskProgress>

    fun all(): List<TaskRecord>
    fun clear()
    fun clearHistoryPreservingProgress()
    fun accepted(type: TaskType, packageName: String, requestId: String): TaskRecord
    fun running(type: TaskType, packageName: String, logPath: String, requestId: String): TaskRecord
    fun finish(task: TaskRecord, status: TaskStatus, message: String, metrics: TaskMetrics = task.metrics): TaskRecord
    fun find(requestId: String): TaskRecord?
    fun publish(progress: TaskProgress)
    suspend fun append(logPath: String, text: String): ShellResult
}

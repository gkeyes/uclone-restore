package com.uclone.restore.sync

import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskAudit
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType

class TaskCoordinator(private val repository: TaskRepository) {
    private val monitor = Any()
    private var active: ActiveTask? = null

    fun accept(
        requestId: String,
        type: TaskType,
        packageName: String,
        audit: TaskAudit = TaskAudit(),
    ): TaskSubmissionResult = synchronized(monitor) {
        val current = active
        if (current != null) {
            return@synchronized if (current.requestId == requestId) {
                TaskSubmissionResult.AlreadyRunning(repository.find(requestId) ?: current.record)
            } else {
                TaskSubmissionResult.Busy(repository.find(current.requestId) ?: current.record)
            }
        }
        repository.find(requestId)?.takeIf { it.status.isTerminal }?.let {
            return@synchronized TaskSubmissionResult.AlreadyCompleted(it)
        }
        val record = repository.accepted(type, packageName, requestId, audit)
        active = ActiveTask(requestId, record)
        repository.publish(TaskProgress(record))
        TaskSubmissionResult.Accepted(record)
    }

    fun fail(requestId: String, message: String): TaskRecord? = synchronized(monitor) {
        val current = active?.takeIf { it.requestId == requestId } ?: return@synchronized null
        val record = repository.find(requestId) ?: current.record
        if (record.status.isTerminal) return@synchronized record
        repository.finish(record, TaskStatus.FAILED, message).also {
            repository.publish(TaskProgress(it))
        }
    }

    fun interrupt(requestId: String, message: String = "任务已中断"): TaskRecord? = synchronized(monitor) {
        val current = active?.takeIf { it.requestId == requestId } ?: return@synchronized null
        val record = repository.find(requestId) ?: current.record
        if (record.status.isTerminal) return@synchronized record
        repository.finish(record, TaskStatus.INTERRUPTED, message).also {
            repository.publish(TaskProgress(it))
        }
    }

    fun complete(requestId: String) {
        synchronized(monitor) {
            if (active?.requestId == requestId) active = null
        }
    }

    fun failAndComplete(requestId: String, message: String): TaskRecord? =
        fail(requestId, message).also { complete(requestId) }

    fun isBusy(): Boolean = synchronized(monitor) { active != null }

    private data class ActiveTask(val requestId: String, val record: TaskRecord)
}

sealed interface TaskSubmissionResult {
    val record: TaskRecord

    data class Accepted(override val record: TaskRecord) : TaskSubmissionResult
    data class AlreadyRunning(override val record: TaskRecord) : TaskSubmissionResult
    data class AlreadyCompleted(override val record: TaskRecord) : TaskSubmissionResult
    data class Busy(override val record: TaskRecord) : TaskSubmissionResult
}

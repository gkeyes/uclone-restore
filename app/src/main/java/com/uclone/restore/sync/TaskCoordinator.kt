package com.uclone.restore.sync

import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.crossuser.session.CrossUserGateLease
import com.uclone.restore.crossuser.session.CrossUserGateResult
import com.uclone.restore.crossuser.session.CrossUserResourceGate
import com.uclone.restore.crossuser.session.CrossUserResourceKey
import com.uclone.restore.crossuser.session.CrossUserResourceKind

class TaskCoordinator(
    private val repository: TaskRepository,
    private val resourceGate: CrossUserResourceGate? = null,
) {
    private val monitor = Any()
    private var active: ActiveTask? = null

    fun accept(requestId: String, type: TaskType, packageName: String): TaskSubmissionResult = synchronized(monitor) {
        val current = active
        if (current != null) {
            return@synchronized if (current.requestId == requestId) {
                TaskSubmissionResult.AlreadyRunning(repository.find(requestId) ?: current.record)
            } else {
                TaskSubmissionResult.Busy(repository.find(current.requestId) ?: current.record)
            }
        }
        val lease = when (val result = resourceGate?.tryAcquire(
            owner = "data-task:$requestId",
            keys = setOf(CrossUserResourceKey(CrossUserResourceKind.GLOBAL_DATA_TRANSACTION)),
        )) {
            null -> null
            is CrossUserGateResult.Acquired -> result.lease
            is CrossUserGateResult.Busy -> return@synchronized TaskSubmissionResult.ResourceBusy(
                result.conflicts.map { it.owner },
            )
        }
        val record = repository.accepted(type, packageName, requestId)
        active = ActiveTask(requestId, record, lease)
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

    fun complete(requestId: String) {
        synchronized(monitor) {
            val completed = active?.takeIf { it.requestId == requestId } ?: return@synchronized
            active = null
            completed.resourceLease?.let { resourceGate?.release(it.leaseId) }
        }
    }

    fun failAndComplete(requestId: String, message: String): TaskRecord? =
        fail(requestId, message).also { complete(requestId) }

    fun isBusy(): Boolean = synchronized(monitor) { active != null }

    private data class ActiveTask(
        val requestId: String,
        val record: TaskRecord,
        val resourceLease: CrossUserGateLease?,
    )
}

sealed interface TaskSubmissionResult {
    data class Accepted(val record: TaskRecord) : TaskSubmissionResult
    data class AlreadyRunning(val record: TaskRecord) : TaskSubmissionResult
    data class Busy(val record: TaskRecord) : TaskSubmissionResult
    data class ResourceBusy(val conflictingOwners: List<String>) : TaskSubmissionResult
}

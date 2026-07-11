package com.uclone.restore.external

import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus

internal object ExternalRequestRecovery {
    fun recordProcessDeaths(
        tasks: List<TaskRecord>,
        store: ExternalRequestStore,
        occurredAt: Long = System.currentTimeMillis(),
        interruptedAfter: Long? = null,
        liveRequestIds: Set<String> = emptySet(),
    ): List<ExternalRequestEvent> {
        val allEvents = store.all()
        val existing = allEvents
            .asSequence()
            .filter { it.stage == ExternalRequestStage.FAILED_PROCESS_DIED || it.stage == ExternalRequestStage.STILL_RUNNING }
            .map(ExternalRequestEvent::requestId)
            .toSet()
        val recovered = tasks.asSequence()
            .filter { it.status == TaskStatus.INTERRUPTED }
            .filter { interruptedAfter == null || (it.finishedAt ?: 0L) >= interruptedAfter }
            .filter { it.audit.source.isExternalRequestSource() }
            .filterNot { it.requestId in existing }
            .map { task ->
                ExternalRequestEvent(
                    requestId = task.requestId,
                    operation = allEvents.firstOrNull { it.requestId == task.requestId }?.operation
                        ?: task.type.name,
                    packageName = task.packageName,
                    source = task.audit.source,
                    stage = if (task.requestId in liveRequestIds) {
                        ExternalRequestStage.STILL_RUNNING
                    } else {
                        ExternalRequestStage.FAILED_PROCESS_DIED
                    },
                    occurredAt = occurredAt,
                    message = if (task.requestId in liveRequestIds) {
                        "UClone 界面进程已重启，Root 数据任务仍在运行"
                    } else {
                        "UClone 进程在任务结束前退出，未发现仍存活的 Root 数据任务"
                    },
                    taskStage = task.currentStage,
                )
            }
            .toList()
        recovered.forEach(store::record)
        return recovered
    }

    private fun String.isExternalRequestSource(): Boolean =
        this == ExternalActionContract.SOURCE_MODULE ||
            this == ExternalActionContract.SOURCE_LAUNCHER_MODULE ||
            this == ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT
}

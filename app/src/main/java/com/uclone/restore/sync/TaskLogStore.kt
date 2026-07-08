package com.uclone.restore.sync

import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.shellQuote
import java.util.concurrent.atomic.AtomicLong

class TaskLogStore(private val shell: RootShellExecutor) {
    private val ids = AtomicLong(System.currentTimeMillis())
    private val records = mutableListOf<TaskRecord>()

    fun all(): List<TaskRecord> = records.sortedByDescending { it.startedAt }

    fun running(type: TaskType, packageName: String, logPath: String): TaskRecord =
        TaskRecord(
            id = ids.incrementAndGet(),
            packageName = packageName,
            type = type,
            startedAt = System.currentTimeMillis(),
            finishedAt = null,
            status = TaskStatus.RUNNING,
            logPath = logPath,
            message = "运行中",
        ).also { records += it }

    fun finish(task: TaskRecord, status: TaskStatus, message: String): TaskRecord {
        val finished = task.copy(
            finishedAt = System.currentTimeMillis(),
            status = status,
            message = message,
        )
        records.replaceAll { if (it.id == task.id) finished else it }
        return finished
    }

    suspend fun append(logPath: String, text: String) {
        val command = "mkdir -p ${shellQuote(logPath.substringBeforeLast('/'))} && printf %s ${shellQuote(text)} >> ${shellQuote(logPath)}"
        shell.exec(command, timeoutSeconds = 20)
    }
}

package com.uclone.restore.sync

import com.uclone.restore.model.TaskMetrics
import com.uclone.restore.model.TaskProgress
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.root.shellQuote
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TaskLogStore(
    private val shell: RootShellExecutor,
    private val historyFile: File? = null,
    private val legacyHistoryFile: File? = null,
) : TaskRepository {
    private val records = loadInitialRecords().toMutableList()
    private val recordsFlow = MutableStateFlow(records.sortedByDescending(TaskRecord::startedAt))
    private val progressFlow = MutableStateFlow(TaskProgress(null))
    override val history: StateFlow<List<TaskRecord>> = recordsFlow.asStateFlow()
    override val progress: StateFlow<TaskProgress> = progressFlow.asStateFlow()
    private val ids = AtomicLong(
        maxOf(
            System.currentTimeMillis(),
            records.maxOfOrNull { it.id } ?: 0L,
        ),
    )

    init {
        if (records.isNotEmpty()) {
            synchronized(records) { persist(records) }
        }
    }

    override fun all(): List<TaskRecord> = synchronized(records) {
        records.toList().sortedByDescending { it.startedAt }
    }

    override fun clear() {
        synchronized(records) {
            clearHistoryLocked()
            progressFlow.value = TaskProgress(null)
        }
    }

    override fun clearHistoryPreservingProgress() {
        synchronized(records) { clearHistoryLocked() }
    }

    override fun accepted(type: TaskType, packageName: String, requestId: String): TaskRecord =
        store(
            TaskRecord(
                id = ids.incrementAndGet(),
                requestId = requestId,
                packageName = packageName,
                type = type,
                startedAt = System.currentTimeMillis(),
                finishedAt = null,
                status = TaskStatus.ACCEPTED,
                logPath = "",
                message = "任务已接收",
            ),
        )

    override fun running(
        type: TaskType,
        packageName: String,
        logPath: String,
        requestId: String,
    ): TaskRecord {
        val accepted = find(requestId)
        return TaskRecord(
            id = accepted?.id ?: ids.incrementAndGet(),
            requestId = requestId,
            packageName = packageName,
            type = type,
            startedAt = accepted?.startedAt ?: System.currentTimeMillis(),
            finishedAt = null,
            status = TaskStatus.RUNNING,
            logPath = logPath,
            message = "运行中",
        ).let(::store)
    }

    override fun finish(
        task: TaskRecord,
        status: TaskStatus,
        message: String,
        metrics: TaskMetrics,
    ): TaskRecord {
        val finished = task.copy(
            finishedAt = System.currentTimeMillis(),
            status = status,
            message = message,
            currentStage = null,
            metrics = metrics,
        )
        synchronized(records) {
            val next = records.toMutableList()
            val index = next.indexOfFirst { it.id == task.id }
            if (index >= 0) {
                next[index] = finished
            } else {
                next += finished
            }
            val retained = next.sortedByDescending(TaskRecord::startedAt).take(MAX_RECORDS)
            persist(retained)
            records.clear()
            records += retained
            recordsFlow.value = retained
        }
        return finished
    }

    override fun find(requestId: String): TaskRecord? = synchronized(records) {
        records.firstOrNull { it.requestId == requestId }
    }

    override fun publish(progress: TaskProgress) {
        progressFlow.value = progress
    }

    override suspend fun append(logPath: String, text: String): ShellResult {
        val command = "mkdir -p ${shellQuote(logPath.substringBeforeLast('/'))} && printf %s ${shellQuote(text)} >> ${shellQuote(logPath)}"
        return shell.exec(command, timeoutSeconds = 20)
    }

    private fun store(record: TaskRecord): TaskRecord = synchronized(records) {
        val next = records.filterNot { it.requestId == record.requestId } + record
        val retained = next.sortedByDescending(TaskRecord::startedAt).take(MAX_RECORDS)
        persist(retained)
        records.clear()
        records += retained
        recordsFlow.value = retained
        record
    }

    private fun clearHistoryLocked() {
        persist(emptyList())
        records.clear()
        recordsFlow.value = emptyList()
    }

    private fun loadInitialRecords(): List<TaskRecord> {
        val current = historyFile
        if (current?.isFile == true) return loadJsonRecords(current)
        val legacy = legacyHistoryFile
        if (legacy?.isFile == true) return loadLegacyRecords(legacy)
        return emptyList()
    }

    private fun loadJsonRecords(file: File): List<TaskRecord> = runCatching {
            file.readLines()
                .mapNotNull(TaskRecordJsonCodec::decode)
                .sortedByDescending { it.startedAt }
                .take(MAX_RECORDS)
        }.getOrDefault(emptyList())

    private fun loadLegacyRecords(file: File): List<TaskRecord> = runCatching {
        file.readLines()
            .mapNotNull(TaskRecordJsonCodec::decodeLegacy)
            .sortedByDescending { it.startedAt }
            .take(MAX_RECORDS)
    }.getOrDefault(emptyList())

    private fun persist(recordsToPersist: List<TaskRecord>) {
        val file = historyFile ?: return
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("无法创建任务历史目录：${parent.absolutePath}")
        }
        val tempFile = file.resolveSibling("${file.name}.tmp")
        try {
            val content = recordsToPersist
                .sortedByDescending { it.startedAt }
                .take(MAX_RECORDS)
                .joinToString("\n", transform = TaskRecordJsonCodec::encode)
            FileOutputStream(tempFile).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    tempFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            tempFile.delete()
            throw IllegalStateException("无法持久化任务历史：${file.absolutePath}", error)
        }
    }

    private companion object {
        const val MAX_RECORDS = 200
    }
}

package com.uclone.restore.sync

import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.shellQuote
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

class TaskLogStore(
    private val shell: RootShellExecutor,
    private val historyFile: File? = null,
) {
    private val records = loadPersistedRecords().toMutableList()
    private val ids = AtomicLong(
        maxOf(
            System.currentTimeMillis(),
            records.maxOfOrNull { it.id } ?: 0L,
        ),
    )

    fun all(): List<TaskRecord> = synchronized(records) {
        records.toList().sortedByDescending { it.startedAt }
    }

    fun clear() {
        synchronized(records) {
            records.clear()
            persistLocked()
        }
    }

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
        ).also {
            synchronized(records) {
                records += it
                trimLocked()
                persistLocked()
            }
        }

    fun finish(task: TaskRecord, status: TaskStatus, message: String): TaskRecord {
        val finished = task.copy(
            finishedAt = System.currentTimeMillis(),
            status = status,
            message = message,
        )
        synchronized(records) {
            val index = records.indexOfFirst { it.id == task.id }
            if (index >= 0) {
                records[index] = finished
            } else {
                records += finished
            }
            trimLocked()
            persistLocked()
        }
        return finished
    }

    suspend fun append(logPath: String, text: String) {
        val command = "mkdir -p ${shellQuote(logPath.substringBeforeLast('/'))} && printf %s ${shellQuote(text)} >> ${shellQuote(logPath)}"
        shell.exec(command, timeoutSeconds = 20)
    }

    private fun loadPersistedRecords(): List<TaskRecord> {
        val file = historyFile ?: return emptyList()
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull(::decodeRecord)
                .sortedByDescending { it.startedAt }
                .take(MAX_RECORDS)
        }.getOrDefault(emptyList())
    }

    private fun persistLocked() {
        val file = historyFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(records.sortedByDescending { it.startedAt }.take(MAX_RECORDS).joinToString("\n", transform = ::encodeRecord))
        }
    }

    private fun trimLocked() {
        if (records.size <= MAX_RECORDS) return
        val retained = records.sortedByDescending { it.startedAt }.take(MAX_RECORDS)
        records.clear()
        records += retained
    }

    private fun encodeRecord(record: TaskRecord): String = listOf(
        record.id.toString(),
        encode(record.packageName),
        record.type.name,
        record.startedAt.toString(),
        record.finishedAt?.toString().orEmpty(),
        record.status.name,
        encode(record.logPath),
        encode(record.message),
    ).joinToString("\t")

    private fun decodeRecord(line: String): TaskRecord? {
        val parts = line.split("\t")
        if (parts.size != FIELD_COUNT) return null
        return runCatching {
            val status = TaskStatus.valueOf(parts[5])
            val interrupted = status == TaskStatus.RUNNING
            TaskRecord(
                id = parts[0].toLong(),
                packageName = decode(parts[1]),
                type = TaskType.valueOf(parts[2]),
                startedAt = parts[3].toLong(),
                finishedAt = parts[4].takeIf(String::isNotBlank)?.toLong()
                    ?: if (interrupted) System.currentTimeMillis() else null,
                status = if (interrupted) TaskStatus.FAILED else status,
                logPath = decode(parts[6]),
                message = if (interrupted) "任务中断" else decode(parts[7]),
            )
        }.getOrNull()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, UTF_8)

    private fun decode(value: String): String = URLDecoder.decode(value, UTF_8)

    private companion object {
        const val MAX_RECORDS = 200
        const val FIELD_COUNT = 8
        const val UTF_8 = "UTF-8"
    }
}

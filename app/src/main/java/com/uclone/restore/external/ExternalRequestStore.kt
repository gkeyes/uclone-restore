package com.uclone.restore.external

import com.uclone.restore.model.TaskStage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class ExternalRequestStage {
    MENU_READY,
    SENT,
    SERVICE_RECEIVED,
    ACCEPTED,
    RUNNING,
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    BUSY,
    REJECTED,
    FAILED,
    INTERRUPTED,
    STILL_RUNNING,
    ORPHANED,
    FAILED_PROCESS_DIED,
}

data class ExternalRequestEvent(
    val requestId: String,
    val operation: String,
    val packageName: String,
    val source: String,
    val stage: ExternalRequestStage,
    val occurredAt: Long,
    val message: String,
    val taskStage: TaskStage? = null,
)

class ExternalRequestStore(private val file: File) {
    private val monitor = Any()
    private val records = load().toMutableList()
    private val recordsFlow = MutableStateFlow(records.sortedByDescending(ExternalRequestEvent::occurredAt))
    val events: StateFlow<List<ExternalRequestEvent>> = recordsFlow.asStateFlow()

    fun all(): List<ExternalRequestEvent> = synchronized(monitor) {
        records.sortedByDescending(ExternalRequestEvent::occurredAt)
    }

    fun record(event: ExternalRequestEvent) {
        synchronized(monitor) {
            val retained = (records + event).takeLast(MAX_EVENTS)
            persist(retained)
            records.clear()
            records += retained
            recordsFlow.value = retained.sortedByDescending(ExternalRequestEvent::occurredAt)
        }
    }

    private fun load(): List<ExternalRequestEvent> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        file.readLines().mapNotNull(::decode).takeLast(MAX_EVENTS)
    }.getOrDefault(emptyList())

    private fun persist(events: List<ExternalRequestEvent>) {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("无法创建外部请求诊断目录：${parent.absolutePath}")
        }
        val temp = file.resolveSibling("${file.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(events.joinToString("\n", transform = ::encode).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException("无法持久化外部请求诊断：${file.absolutePath}", error)
        }
    }

    private fun encode(event: ExternalRequestEvent): String = JSONObject()
        .put("requestId", event.requestId)
        .put("operation", event.operation)
        .put("packageName", event.packageName)
        .put("source", event.source)
        .put("stage", event.stage.name)
        .put("occurredAt", event.occurredAt)
        .put("message", event.message)
        .put("taskStage", event.taskStage?.name ?: JSONObject.NULL)
        .toString()

    private fun decode(line: String): ExternalRequestEvent? = runCatching {
        val json = JSONObject(line)
        ExternalRequestEvent(
            requestId = json.getString("requestId"),
            operation = json.optString("operation"),
            packageName = json.optString("packageName"),
            source = json.optString("source"),
            stage = ExternalRequestStage.valueOf(json.getString("stage")),
            occurredAt = json.getLong("occurredAt"),
            message = json.optString("message"),
            taskStage = json.optString("taskStage")
                .takeIf(String::isNotBlank)
                ?.let { value -> enumValues<TaskStage>().firstOrNull { it.name == value } },
        )
    }.getOrNull()

    private companion object {
        const val MAX_EVENTS = 500
    }
}

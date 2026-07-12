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
    RECOVERY_REQUIRED,
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

class ExternalRequestStore(
    private val file: File,
    private val terminalFile: File = file.resolveSibling("${file.nameWithoutExtension}_terminals.jsonl"),
    private val maxEvents: Int = MAX_EVENTS,
) {
    private val monitor = Any()
    private val records = load(file, maxEvents).toMutableList()
    private val loadedTerminalRecords = terminalIndex(load(terminalFile))
    private val terminalRecords = loadedTerminalRecords.toMutableMap().apply {
        records.asSequence()
            .filter { it.stage.isTerminalProtocolStage }
            .sortedBy(ExternalRequestEvent::occurredAt)
            .forEach { event -> putIfAbsent(event.requestId, event) }
    }
    private val recordsFlow = MutableStateFlow(newestFirst(records))
    val events: StateFlow<List<ExternalRequestEvent>> = recordsFlow.asStateFlow()

    init {
        require(maxEvents > 0)
        if (terminalRecords.size != loadedTerminalRecords.size) {
            persist(terminalFile, terminalRecords.values.toList(), "外部请求终态索引")
        }
    }

    fun all(): List<ExternalRequestEvent> = synchronized(monitor) {
        newestFirst(records)
    }

    fun terminal(requestId: String): ExternalRequestEvent? = synchronized(monitor) {
        terminalRecords[requestId]
    }

    fun record(event: ExternalRequestEvent) {
        synchronized(monitor) {
            if (event.stage.isTerminalProtocolStage && event.requestId !in terminalRecords) {
                val nextTerminals = terminalRecords + (event.requestId to event)
                persist(terminalFile, nextTerminals.values.toList(), "外部请求终态索引")
                terminalRecords[event.requestId] = event
            }
            val retained = (records + event).takeLast(maxEvents)
            persist(file, retained, "外部请求诊断")
            records.clear()
            records += retained
            recordsFlow.value = newestFirst(retained)
        }
    }

    fun recordReconciledTerminal(event: ExternalRequestEvent): Boolean {
        require(event.stage.isTerminalProtocolStage)
        return synchronized(monitor) {
            val existing = terminalRecords[event.requestId]
            if (existing != null && existing.stage !in RECONCILABLE_TERMINAL_STAGES) {
                return@synchronized false
            }
            val nextTerminals = terminalRecords + (event.requestId to event)
            persist(terminalFile, nextTerminals.values.toList(), "外部请求终态索引")
            terminalRecords[event.requestId] = event
            val retained = (records + event).takeLast(maxEvents)
            persist(file, retained, "外部请求诊断")
            records.clear()
            records += retained
            recordsFlow.value = newestFirst(retained)
            true
        }
    }

    private fun load(source: File, limit: Int? = null): List<ExternalRequestEvent> = runCatching {
        if (!source.isFile) return@runCatching emptyList()
        val decoded = source.readLines().mapNotNull(::decode)
        if (limit == null) decoded else decoded.takeLast(limit)
    }.getOrDefault(emptyList())

    private fun persist(target: File, events: List<ExternalRequestEvent>, label: String) {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("无法创建${label}目录：${parent.absolutePath}")
        }
        val temp = target.resolveSibling("${target.name}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(events.joinToString("\n", transform = ::encode).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: Exception) {
            temp.delete()
            throw IllegalStateException("无法持久化$label：${target.absolutePath}", error)
        }
    }

    private fun terminalIndex(events: List<ExternalRequestEvent>): Map<String, ExternalRequestEvent> =
        buildMap {
            events.asSequence()
                .filter { it.stage.isTerminalProtocolStage }
                .sortedBy(ExternalRequestEvent::occurredAt)
                .forEach { event -> putIfAbsent(event.requestId, event) }
        }

    private fun newestFirst(events: List<ExternalRequestEvent>): List<ExternalRequestEvent> = events
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<ExternalRequestEvent>> { it.value.occurredAt }
                .thenByDescending(IndexedValue<ExternalRequestEvent>::index),
        )
        .map(IndexedValue<ExternalRequestEvent>::value)

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
        val RECONCILABLE_TERMINAL_STAGES = setOf(
            ExternalRequestStage.INTERRUPTED,
            ExternalRequestStage.FAILED_PROCESS_DIED,
        )
    }
}

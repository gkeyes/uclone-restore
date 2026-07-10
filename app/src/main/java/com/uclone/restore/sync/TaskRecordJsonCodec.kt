package com.uclone.restore.sync

import com.uclone.restore.model.TaskMetrics
import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskStageMetric
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONObject

internal object TaskRecordJsonCodec {
    fun encode(record: TaskRecord): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("id", record.id)
        .put("requestId", record.requestId)
        .put("packageName", record.packageName)
        .put("type", record.type.name)
        .put("startedAt", record.startedAt)
        .put("finishedAt", record.finishedAt ?: JSONObject.NULL)
        .put("status", record.status.name)
        .put("logPath", record.logPath)
        .put("message", record.message)
        .put("currentStage", record.currentStage?.name ?: JSONObject.NULL)
        .put("metrics", encodeMetrics(record.metrics))
        .toString()

    fun decode(line: String, interruptedAt: Long = System.currentTimeMillis()): TaskRecord? = runCatching {
        val json = JSONObject(line)
        val rawStatus = TaskStatus.valueOf(json.getString("status"))
        val interrupted = rawStatus in ACTIVE_STATUSES
        TaskRecord(
            id = json.getLong("id"),
            requestId = json.optString("requestId").ifBlank { "legacy-${json.getLong("id")}" },
            packageName = json.getString("packageName"),
            type = TaskType.valueOf(json.getString("type")),
            startedAt = json.getLong("startedAt"),
            finishedAt = if (interrupted) interruptedAt else json.optLongOrNull("finishedAt"),
            status = if (interrupted) TaskStatus.INTERRUPTED else rawStatus,
            logPath = json.getString("logPath"),
            message = if (interrupted) "任务中断" else json.getString("message"),
            currentStage = json.optString("currentStage").toEnumOrNull<TaskStage>(),
            metrics = json.optJSONObject("metrics")?.let(::decodeMetrics) ?: TaskMetrics(),
        )
    }.getOrNull()

    fun decodeLegacy(line: String, interruptedAt: Long = System.currentTimeMillis()): TaskRecord? {
        val parts = line.split("\t")
        if (parts.size != LEGACY_FIELD_COUNT) return null
        return runCatching {
            val id = parts[0].toLong()
            val status = TaskStatus.valueOf(parts[5])
            val interrupted = status == TaskStatus.RUNNING
            TaskRecord(
                id = id,
                requestId = "legacy-$id",
                packageName = decodeUrl(parts[1]),
                type = TaskType.valueOf(parts[2]),
                startedAt = parts[3].toLong(),
                finishedAt = parts[4].takeIf(String::isNotBlank)?.toLong()
                    ?: if (interrupted) interruptedAt else null,
                status = if (interrupted) TaskStatus.INTERRUPTED else status,
                logPath = decodeUrl(parts[6]),
                message = if (interrupted) "任务中断" else decodeUrl(parts[7]),
            )
        }.getOrNull()
    }

    private fun encodeMetrics(metrics: TaskMetrics): JSONObject = JSONObject()
        .put("rootProcessStarts", metrics.rootProcessStarts)
        .put("rootCommandCount", metrics.rootCommandCount)
        .put("scannedFiles", metrics.scannedFiles)
        .put("copiedFiles", metrics.copiedFiles)
        .put("copiedBytes", metrics.copiedBytes)
        .put("peakTemporaryBytes", metrics.peakTemporaryBytes)
        .put("targetDowntimeMs", metrics.targetDowntimeMs ?: JSONObject.NULL)
        .put(
            "stages",
            JSONArray().also { stages ->
                metrics.stages.forEach { metric ->
                    stages.put(
                        JSONObject()
                            .put("stage", metric.stage.name)
                            .put("startedAt", metric.startedAt)
                            .put("finishedAt", metric.finishedAt),
                    )
                }
            },
        )

    private fun decodeMetrics(json: JSONObject): TaskMetrics {
        val encodedStages = json.optJSONArray("stages") ?: JSONArray()
        val stages = buildList {
            repeat(encodedStages.length()) { index ->
                val encoded = encodedStages.optJSONObject(index) ?: return@repeat
                val stage = encoded.optString("stage").toEnumOrNull<TaskStage>() ?: return@repeat
                val startedAt = encoded.optLongOrNull("startedAt") ?: return@repeat
                val finishedAt = encoded.optLongOrNull("finishedAt") ?: return@repeat
                add(TaskStageMetric(stage, startedAt, finishedAt))
            }
        }
        return TaskMetrics(
            rootProcessStarts = json.optInt("rootProcessStarts"),
            rootCommandCount = json.optInt("rootCommandCount"),
            scannedFiles = json.optLong("scannedFiles"),
            copiedFiles = json.optLong("copiedFiles"),
            copiedBytes = json.optLong("copiedBytes"),
            peakTemporaryBytes = json.optLong("peakTemporaryBytes"),
            targetDowntimeMs = json.optLongOrNull("targetDowntimeMs"),
            stages = stages,
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private inline fun <reified T : Enum<T>> String.toEnumOrNull(): T? =
        takeIf(String::isNotBlank)?.let { value -> enumValues<T>().firstOrNull { it.name == value } }

    private fun decodeUrl(value: String): String = URLDecoder.decode(value, UTF_8)

    private val ACTIVE_STATUSES = setOf(
        TaskStatus.ACCEPTED,
        TaskStatus.RUNNING,
        TaskStatus.AUTO_ROLLING_BACK,
    )
    private const val SCHEMA_VERSION = 2
    private const val LEGACY_FIELD_COUNT = 8
    private const val UTF_8 = "UTF-8"
}

package com.uclone.restore.sync

import com.uclone.restore.model.TaskMetrics
import com.uclone.restore.model.TaskStage
import com.uclone.restore.model.TaskStageMetric

object TaskMetricsParser {
    private val stagePattern = Regex(
        "^UCLONE_METRIC:stage=([A-Z_]+) started_at=([0-9]+) finished_at=([0-9]+)$",
    )
    private val keyValuePattern = Regex("([a-z_]+)=([0-9]+)")

    fun parse(
        output: String,
        rootProcessStarts: Int,
        rootCommandCount: Int,
    ): TaskMetrics {
        val values = mutableMapOf<String, Long>()
        val stages = buildList {
            output.lineSequence().map(String::trim).forEach { line ->
                val stageMatch = stagePattern.matchEntire(line)
                if (stageMatch != null) {
                    val stage = runCatching { TaskStage.valueOf(stageMatch.groupValues[1]) }.getOrNull()
                    if (stage != null) {
                        add(
                            TaskStageMetric(
                                stage = stage,
                                startedAt = stageMatch.groupValues[2].toLong(),
                                finishedAt = stageMatch.groupValues[3].toLong(),
                            ),
                        )
                    }
                } else if (line.startsWith("UCLONE_METRIC:")) {
                    keyValuePattern.findAll(line).forEach { match ->
                        val key = match.groupValues[1]
                        val value = match.groupValues[2].toLong()
                        values[key] = when (key) {
                            "peak_temporary_bytes", "target_downtime_ms" -> maxOf(values[key] ?: 0, value)
                            else -> (values[key] ?: 0) + value
                        }
                    }
                }
            }
        }
        return TaskMetrics(
            rootProcessStarts = rootProcessStarts,
            rootCommandCount = rootCommandCount,
            scannedFiles = values["scanned_files"] ?: 0,
            copiedFiles = values["copied_files"] ?: 0,
            copiedBytes = values["copied_bytes"] ?: 0,
            peakTemporaryBytes = values["peak_temporary_bytes"] ?: 0,
            targetDowntimeMs = values["target_downtime_ms"],
            stages = stages,
        )
    }
}

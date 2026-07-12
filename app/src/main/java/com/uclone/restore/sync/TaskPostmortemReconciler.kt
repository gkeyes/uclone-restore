package com.uclone.restore.sync

import com.uclone.restore.model.TaskRecord
import com.uclone.restore.model.TaskStatus
import com.uclone.restore.root.RootShellExecutor
import com.uclone.restore.root.ShellResult
import com.uclone.restore.root.shellQuote

internal data class CompletedTaskLog(
    val output: String,
    val exitCode: Int,
    val endedAt: Long,
    val durationMs: Long,
    val truncated: Boolean,
)

internal object TaskTerminalLogReader {
    fun script(rootDir: String, task: TaskRecord): String = """
        ${WorkspacePathGuard.inspect(rootDir)}
        [ "${'$'}UCLONE_WORKSPACE_MISSING" = "0" ] || exit 0
        LOG_PATH=${shellQuote(task.logPath)}
        LOG_PREFIX="${'$'}ROOT/logs/"
        case "${'$'}LOG_PATH" in
          "${'$'}LOG_PREFIX"*) ;;
          *) echo "ERR_POSTMORTEM_LOG_OUTSIDE_WORKSPACE:${'$'}LOG_PATH" >&2; exit 75 ;;
        esac
        [ -f "${'$'}LOG_PATH" ] && [ ! -L "${'$'}LOG_PATH" ] || exit 0
        LOG_REAL=${'$'}(readlink -f "${'$'}LOG_PATH" 2>/dev/null || true)
        [ "${'$'}LOG_REAL" = "${'$'}LOG_PATH" ] || {
          echo "ERR_POSTMORTEM_LOG_NOT_CANONICAL:${'$'}LOG_PATH:${'$'}LOG_REAL" >&2
          exit 75
        }
        grep -F -x ${shellQuote("REQUEST_ID=${task.requestId}")} "${'$'}LOG_PATH" >/dev/null 2>&1 || {
          echo "ERR_POSTMORTEM_REQUEST_MISMATCH:${'$'}LOG_PATH" >&2
          exit 75
        }
        grep -F -x ${shellQuote("TASK=${task.type}")} "${'$'}LOG_PATH" >/dev/null 2>&1 || {
          echo "ERR_POSTMORTEM_TYPE_MISMATCH:${'$'}LOG_PATH" >&2
          exit 75
        }
        LOG_BYTES=${'$'}(wc -c < "${'$'}LOG_PATH" 2>/dev/null | tr -d ' ')
        case "${'$'}LOG_BYTES" in ''|*[!0-9]*) exit 0 ;; esac
        LOG_TRUNCATED=0
        [ "${'$'}LOG_BYTES" -le $MAX_LOG_BYTES ] || LOG_TRUNCATED=1
        echo "UCLONE_POSTMORTEM_TRUNCATED=${'$'}LOG_TRUNCATED"
        echo "$LOG_MARKER"
        if [ "${'$'}LOG_TRUNCATED" = "1" ]; then
          tail -c $MAX_LOG_BYTES "${'$'}LOG_PATH"
        else
          cat "${'$'}LOG_PATH"
        fi
    """.trimIndent()

    fun parse(raw: String): CompletedTaskLog? {
        val markerIndex = raw.indexOf("$LOG_MARKER\n")
        if (markerIndex < 0) return null
        val prefix = raw.substring(0, markerIndex)
        val output = raw.substring(markerIndex + LOG_MARKER.length + 1)
        val lines = output.lineSequence().toList()
        val lastContent = lines.indexOfLast(String::isNotBlank)
        if (lastContent < 3) return null
        val exitCode = lines[lastContent].parseLongValue("EXIT=")?.toIntExact() ?: return null
        val durationMs = lines[lastContent - 1].parseLongValue("DURATION_MS=") ?: return null
        if (!lines[lastContent - 2].startsWith("END_LOCAL=")) return null
        val endedAt = lines[lastContent - 3].parseLongValue("END=") ?: return null
        if (exitCode !in 0..255 || endedAt <= 0L || durationMs < 0L) return null
        return CompletedTaskLog(
            output = output,
            exitCode = exitCode,
            endedAt = endedAt,
            durationMs = durationMs,
            truncated = prefix.lineSequence().any { it.trim() == "UCLONE_POSTMORTEM_TRUNCATED=1" },
        )
    }

    private fun String.parseLongValue(prefix: String): Long? =
        takeIf { it.startsWith(prefix) }?.substring(prefix.length)?.trim()?.toLongOrNull()

    private fun Long.toIntExact(): Int? =
        takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

    private const val MAX_LOG_BYTES = 2_097_152
    private const val LOG_MARKER = "UCLONE_POSTMORTEM_LOG_BEGIN"
}

internal class TaskPostmortemReconciler(
    private val shell: RootShellExecutor,
    private val repository: TaskRepository,
) {
    suspend fun reconcile(rootDir: String, task: TaskRecord): TaskRecord? {
        if (task.status != TaskStatus.INTERRUPTED || task.logPath.isBlank()) return null
        val read = shell.exec(TaskTerminalLogReader.script(rootDir, task), timeoutSeconds = 20)
        if (!read.isSuccess) return null
        val completed = TaskTerminalLogReader.parse(read.stdout) ?: return null
        val result = ShellResult(
            exitCode = completed.exitCode,
            stdout = completed.output,
            stderr = "",
            processStarts = 1,
            durationMs = completed.durationMs,
            outputTruncated = completed.truncated,
        )
        val baseStatus = TaskOutcome.status(result)
        val status = if (completed.truncated && baseStatus == TaskStatus.SUCCESS) {
            TaskStatus.SUCCESS_WITH_WARNINGS
        } else {
            baseStatus
        }
        val message = when (status) {
            TaskStatus.SUCCESS,
            TaskStatus.SUCCESS_WITH_WARNINGS,
            -> TaskResultMessages.successMessage(completed.output) +
                if (completed.truncated) "；仅恢复了日志末尾，部分性能指标可能不完整" else ""
            else -> TaskResultMessages.fatalInstallMessage(completed.output)
                ?: TaskOutcome.failureMessage(status, result)
                ?: "Root 任务失败（exit=${completed.exitCode}），请查看日志"
        }
        val metrics = TaskMetricsParser.parse(
            output = completed.output,
            rootProcessStarts = 1,
            rootCommandCount = 1,
        )
        val audited = task.copy(
            audit = TaskAuditParser.enrich(task.audit, task.type, completed.output),
            outcomeCode = TaskOutcome.code(result, status),
        )
        return repository.finish(
            task = audited,
            status = status,
            message = message,
            metrics = metrics,
            finishedAt = completed.endedAt,
        )
    }
}

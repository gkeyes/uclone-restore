package com.uclone.restore.root

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

interface RootShellExecutor {
    suspend fun exec(command: String, timeoutSeconds: Long = 120): ShellResult

    suspend fun execStreaming(
        command: String,
        timeoutSeconds: Long = 120,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult {
        val result = exec(command, timeoutSeconds)
        result.stdout.lineSequence().forEach { onOutput(ShellOutput(ShellStream.STDOUT, it)) }
        result.stderr.lineSequence().forEach { onOutput(ShellOutput(ShellStream.STDERR, it)) }
        return result
    }

    suspend fun execStreamingWithInput(
        command: String,
        standardInput: String,
        timeoutSeconds: Long = 120,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult = execStreaming(command, timeoutSeconds, onOutput)
}

class ProcessRootShellExecutor internal constructor(
    private val runner: SuProcessRunner,
) : RootShellExecutor {
    constructor(scriptDirectory: File? = null) : this(RootSuProcessRunner(scriptDirectory = scriptDirectory))

    private val modeLock = Any()
    @Volatile private var invocationMode: SuInvocationMode? = null

    override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult =
        execStreaming(command, timeoutSeconds) {}

    override suspend fun execStreaming(
        command: String,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult = execStreamingInternal(command, null, timeoutSeconds, onOutput)

    override suspend fun execStreamingWithInput(
        command: String,
        standardInput: String,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult = execStreamingInternal(command, standardInput, timeoutSeconds, onOutput)

    private suspend fun execStreamingInternal(
        command: String,
        standardInput: String?,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult =
        runInterruptible(Dispatchers.IO) {
            val knownMode = invocationMode
            if (knownMode != null) {
                return@runInterruptible runner.run(knownMode.args(command), standardInput, timeoutSeconds, onOutput)
            }
            val resolution = synchronized(modeLock) {
                val resolvedMode = invocationMode
                if (resolvedMode != null) {
                    return@synchronized ModeResolution(resolvedMode, null)
                }
                val probe = runner.run(SuInvocationMode.MOUNT_MASTER.args("exit 0"), null, 15, {})
                if (Thread.currentThread().isInterrupted) {
                    return@synchronized ModeResolution(SuInvocationMode.MOUNT_MASTER, probe, interrupted = true)
                }
                val selectedMode = if (probe.isMountMasterUnsupported()) {
                    SuInvocationMode.PLAIN
                } else {
                    SuInvocationMode.MOUNT_MASTER
                }
                invocationMode = selectedMode
                ModeResolution(selectedMode, probe)
            }
            if (resolution.interrupted) {
                return@runInterruptible checkNotNull(resolution.probe)
            }
            val result = runner.run(resolution.mode.args(command), standardInput, timeoutSeconds, onOutput)
            resolution.probe?.let { probe ->
                result.copy(
                    processStarts = probe.processStarts + result.processStarts,
                    durationMs = probe.durationMs + result.durationMs,
                )
            } ?: result
        }

    private data class ModeResolution(
        val mode: SuInvocationMode,
        val probe: ShellResult?,
        val interrupted: Boolean = false,
    )

    private enum class SuInvocationMode {
        MOUNT_MASTER,
        PLAIN,
        ;

        fun args(command: String): List<String> = when (this) {
            MOUNT_MASTER -> listOf("-mm", "-c", command)
            PLAIN -> listOf("-c", command)
        }
    }
}

internal fun interface SuProcessRunner {
    fun run(
        args: List<String>,
        standardInput: String?,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult
}

private fun ShellResult.isMountMasterUnsupported(): Boolean {
    if (exitCode == 0) return false
    val firstLines = (stderr + "\n" + stdout)
        .lineSequence()
        .take(4)
        .joinToString("\n")
        .lowercase()
    val optionError = listOf("invalid option", "unknown option", "illegal option", "unrecognized option")
        .any(firstLines::contains)
    return optionError && firstLines.contains("su")
}

enum class ShellStream {
    STDOUT,
    STDERR,
}

data class ShellOutput(
    val stream: ShellStream,
    val line: String,
)

fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

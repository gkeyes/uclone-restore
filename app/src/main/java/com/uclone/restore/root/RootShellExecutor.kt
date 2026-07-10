package com.uclone.restore.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

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
}

class ProcessRootShellExecutor internal constructor(
    private val runner: SuProcessRunner,
) : RootShellExecutor {
    constructor() : this(SystemSuProcessRunner)

    private val modeLock = Any()
    @Volatile private var invocationMode: SuInvocationMode? = null

    override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult =
        execStreaming(command, timeoutSeconds) {}

    override suspend fun execStreaming(
        command: String,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult =
        withContext(Dispatchers.IO) {
            val knownMode = invocationMode
            if (knownMode != null) {
                return@withContext runner.run(knownMode.args(command), timeoutSeconds, onOutput)
            }
            val resolution = synchronized(modeLock) {
                val resolvedMode = invocationMode
                if (resolvedMode != null) {
                    return@synchronized ModeResolution(resolvedMode, null)
                }
                val probe = runner.run(SuInvocationMode.MOUNT_MASTER.args("exit 0"), 15, {})
                val selectedMode = if (probe.isMountMasterUnsupported()) {
                    SuInvocationMode.PLAIN
                } else {
                    SuInvocationMode.MOUNT_MASTER
                }
                invocationMode = selectedMode
                ModeResolution(selectedMode, probe)
            }
            val result = runner.run(resolution.mode.args(command), timeoutSeconds, onOutput)
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
    fun run(args: List<String>, timeoutSeconds: Long, onOutput: (ShellOutput) -> Unit): ShellResult
}

private val SystemSuProcessRunner: SuProcessRunner = ProcessCommandRunner(listOf("su"))

internal class ProcessCommandRunner(
    private val executablePrefix: List<String>,
    private val timeoutGraceSeconds: Long = DEFAULT_TIMEOUT_GRACE_SECONDS,
) : SuProcessRunner {
    override fun run(
        args: List<String>,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult {
        val startedAt = System.nanoTime()
        val process = try {
            ProcessBuilder(executablePrefix + args).redirectErrorStream(false).start()
        } catch (error: java.io.IOException) {
            return ShellResult(
                exitCode = 127,
                stdout = "",
                stderr = error.message ?: "Unable to start su",
                processStarts = 0,
                durationMs = elapsedMs(startedAt),
            )
        }
        val stdoutCapture = streamReader(process.inputStream, ShellStream.STDOUT, onOutput)
        val stderrCapture = streamReader(process.errorStream, ShellStream.STDERR, onOutput)
        val finished = if (timeoutSeconds <= 0) {
            process.waitFor()
            true
        } else {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        }
        if (!finished) {
            process.destroy()
            val terminatedGracefully = process.waitFor(timeoutGraceSeconds, TimeUnit.SECONDS)
            if (!terminatedGracefully) {
                process.destroyForcibly()
                process.waitFor(FORCED_TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)
            }
            return ShellResult(
                exitCode = 124,
                stdout = stdoutCapture.result(),
                stderr = mergeTimeoutError(
                    capturedStderr = stderrCapture.result(),
                    timeoutSeconds = timeoutSeconds,
                    forced = !terminatedGracefully,
                ),
                durationMs = elapsedMs(startedAt),
                outputTruncated = stdoutCapture.truncated() || stderrCapture.truncated(),
            )
        }
        return ShellResult(
            exitCode = process.exitValue(),
            stdout = stdoutCapture.result(),
            stderr = stderrCapture.result(),
            durationMs = elapsedMs(startedAt),
            outputTruncated = stdoutCapture.truncated() || stderrCapture.truncated(),
        )
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private fun streamReader(
        input: java.io.InputStream,
        stream: ShellStream,
        onOutput: (ShellOutput) -> Unit,
    ): StreamCapture {
        val buffer = BoundedLineBuffer(MAX_CAPTURE_CHARS)
        val thread = Thread {
            readBoundedStream(input, MAX_STREAM_FRAGMENT_CHARS) { fragment ->
                buffer.append(fragment)
                runCatching { onOutput(ShellOutput(stream, fragment)) }
            }
        }
        thread.start()
        return StreamCapture(thread, buffer)
    }

    private data class StreamCapture(
        val thread: Thread,
        val buffer: BoundedLineBuffer,
    ) {
        fun result(): String {
            thread.join(5_000)
            return buffer.value()
        }

        fun truncated(): Boolean = buffer.isTruncated
    }

    private class BoundedLineBuffer(private val maxChars: Int) {
        private val lines = java.util.ArrayDeque<String>()
        private var size = 0
        @Volatile var isTruncated: Boolean = false
            private set

        @Synchronized
        fun append(line: String) {
            val value = "$line\n"
            lines.addLast(value)
            size += value.length
            while (size > maxChars && lines.isNotEmpty()) {
                size -= lines.removeFirst().length
                isTruncated = true
            }
        }

        @Synchronized
        fun value(): String = buildString {
            if (isTruncated) append("[earlier output truncated]\n")
            lines.forEach(::append)
        }
    }

}

internal fun readBoundedStream(
    input: java.io.InputStream,
    maxFragmentChars: Int = MAX_STREAM_FRAGMENT_CHARS,
    onFragment: (String) -> Unit,
) {
    require(maxFragmentChars > 0)
    input.bufferedReader().use { reader ->
        val chunk = CharArray(4096)
        val pending = StringBuilder(maxFragmentChars)
        while (true) {
            val read = reader.read(chunk)
            if (read < 0) break
            for (index in 0 until read) {
                val char = chunk[index]
                if (char == '\n') {
                    onFragment(pending.toString().trimEnd('\r'))
                    pending.setLength(0)
                } else {
                    pending.append(char)
                    if (pending.length >= maxFragmentChars) {
                        onFragment(pending.toString())
                        pending.setLength(0)
                    }
                }
            }
        }
        if (pending.isNotEmpty()) onFragment(pending.toString())
    }
}

internal fun mergeTimeoutError(capturedStderr: String, timeoutSeconds: Long, forced: Boolean = false): String =
    listOf(
        capturedStderr.trimEnd(),
        "Command timed out after ${timeoutSeconds}s",
        if (forced) "Timeout termination required SIGKILL after rollback grace period" else "Timeout termination completed during rollback grace period",
    )
        .filter(String::isNotBlank)
        .joinToString("\n")

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

private const val MAX_CAPTURE_CHARS = 512 * 1024
internal const val MAX_STREAM_FRAGMENT_CHARS = 8 * 1024
private const val DEFAULT_TIMEOUT_GRACE_SECONDS = 30L
private const val FORCED_TERMINATION_WAIT_SECONDS = 5L

package com.uclone.restore.root

import java.io.IOException
import java.util.concurrent.TimeUnit

internal class ProcessCommandRunner(
    private val executablePrefix: List<String>,
    private val timeoutGraceSeconds: Long = DEFAULT_TIMEOUT_GRACE_SECONDS,
) : SuProcessRunner {
    override fun run(
        args: List<String>,
        standardInput: String?,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult = runManaged(args, standardInput, timeoutSeconds, onOutput, processTreeTerminator = null)

    fun runManaged(
        args: List<String>,
        standardInput: String?,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
        processTreeTerminator: ((force: Boolean) -> ProcessTreeTerminationAttempt)?,
    ): ShellResult {
        val startedAt = System.nanoTime()
        val process = try {
            ProcessBuilder(executablePrefix + args).redirectErrorStream(false).start()
        } catch (error: IOException) {
            return ShellResult(
                exitCode = 127,
                stdout = "",
                stderr = error.message ?: "Unable to start su",
                processStarts = 0,
                durationMs = elapsedMs(startedAt),
            )
        }
        val stdoutCapture = ProcessStreamCapture.start(process.inputStream, ShellStream.STDOUT, onOutput)
        val stderrCapture = ProcessStreamCapture.start(process.errorStream, ShellStream.STDERR, onOutput)
        try {
            process.outputStream.bufferedWriter().use { writer ->
                if (standardInput != null) writer.write(standardInput)
            }
        } catch (_: IOException) {
            val termination = terminateProcess(
                process,
                INTERRUPTION_GRACE_MILLIS,
                TimeUnit.MILLISECONDS,
                processTreeTerminator,
            )
            val captured = drainStreams(stdoutCapture, stderrCapture)
            if (termination.interrupted || captured.interrupted || Thread.currentThread().isInterrupted) {
                Thread.currentThread().interrupt()
                return interruptedResult(startedAt, captured, termination)
            }
            return ShellResult(
                exitCode = 74,
                stdout = captured.stdout,
                stderr = "Unable to send protected standard input",
                durationMs = elapsedMs(startedAt),
                outputTruncated = captured.truncated,
            )
        }
        val finished = try {
            waitForCommand(process, timeoutSeconds)
        } catch (_: InterruptedException) {
            val termination = terminateProcess(
                process,
                INTERRUPTION_GRACE_MILLIS,
                TimeUnit.MILLISECONDS,
                processTreeTerminator,
            )
            val captured = drainStreams(stdoutCapture, stderrCapture)
            Thread.currentThread().interrupt()
            return interruptedResult(startedAt, captured, termination)
        }
        if (!finished) {
            val termination = terminateProcess(
                process,
                timeoutGraceSeconds,
                TimeUnit.SECONDS,
                processTreeTerminator,
            )
            val captured = drainStreams(stdoutCapture, stderrCapture)
            if (termination.interrupted || captured.interrupted) {
                Thread.currentThread().interrupt()
                return interruptedResult(startedAt, captured, termination)
            }
            return ShellResult(
                exitCode = 124,
                stdout = captured.stdout,
                stderr = mergeTimeoutError(
                    capturedStderr = captured.stderr,
                    timeoutSeconds = timeoutSeconds,
                    forced = !termination.gracefully,
                    treeVerified = termination.treeVerified,
                ),
                durationMs = elapsedMs(startedAt),
                outputTruncated = captured.truncated,
            )
        }
        val exitCode = process.exitValue()
        val captured = drainStreams(stdoutCapture, stderrCapture)
        if (captured.interrupted) {
            Thread.currentThread().interrupt()
            return interruptedResult(
                startedAt,
                captured,
                ProcessTermination(gracefully = true, terminated = true, interrupted = true),
            )
        }
        return ShellResult(
            exitCode = exitCode,
            stdout = captured.stdout,
            stderr = captured.stderr,
            durationMs = elapsedMs(startedAt),
            outputTruncated = captured.truncated,
        )
    }

    private fun waitForCommand(process: Process, timeoutSeconds: Long): Boolean {
        if (timeoutSeconds > 0) return process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        while (!process.waitFor(PROCESS_WAIT_POLL_MILLIS, TimeUnit.MILLISECONDS)) Unit
        return true
    }

    private fun terminateProcess(
        process: Process,
        gracefulTimeout: Long,
        unit: TimeUnit,
        processTreeTerminator: ((force: Boolean) -> ProcessTreeTerminationAttempt)?,
    ): ProcessTermination {
        val gracefulTree = runCatching { processTreeTerminator?.invoke(false) }
            .getOrNull()
            ?: ProcessTreeTerminationAttempt.NOT_APPLICABLE
        if (processTreeTerminator == null || !gracefulTree.signaled) {
            process.destroy()
        }
        val gracefulWait = waitForTermination(process, gracefulTimeout, unit)
        if (gracefulWait.terminated) {
            val finalTree = runCatching { processTreeTerminator?.invoke(true) }
                .getOrNull()
                ?: ProcessTreeTerminationAttempt.NOT_APPLICABLE
            return ProcessTermination(
                gracefully = !finalTree.signaled,
                terminated = true,
                interrupted = gracefulWait.interrupted,
                treeVerified = processTreeTerminator == null || finalTree.verified,
            )
        }
        process.destroy()
        val forcedTree = runCatching { processTreeTerminator?.invoke(true) }
            .getOrNull()
            ?: ProcessTreeTerminationAttempt.NOT_APPLICABLE
        process.destroyForcibly()
        val forcedWait = waitForTermination(process, FORCED_TERMINATION_WAIT_SECONDS, TimeUnit.SECONDS)
        return ProcessTermination(
            gracefully = false,
            terminated = forcedWait.terminated || !process.isAlive,
            interrupted = gracefulWait.interrupted || forcedWait.interrupted,
            treeVerified = processTreeTerminator == null || forcedTree.verified,
        )
    }

    private fun waitForTermination(process: Process, timeout: Long, unit: TimeUnit): TerminationWait {
        if (!process.isAlive) return TerminationWait(terminated = true, interrupted = false)
        return try {
            TerminationWait(process.waitFor(timeout.coerceAtLeast(0), unit), interrupted = false)
        } catch (_: InterruptedException) {
            TerminationWait(terminated = !process.isAlive, interrupted = true)
        }
    }

    private fun interruptedResult(
        startedAt: Long,
        captured: CapturedStreams,
        termination: ProcessTermination,
    ): ShellResult = ShellResult(
        exitCode = INTERRUPTED_EXIT_CODE,
        stdout = captured.stdout,
        stderr = mergeInterruptionError(captured.stderr, termination),
        durationMs = elapsedMs(startedAt),
        outputTruncated = captured.truncated,
    )

    private fun drainStreams(stdout: ProcessStreamCapture, stderr: ProcessStreamCapture): CapturedStreams {
        var interrupted = false
        val drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STREAM_DRAIN_WAIT_MILLIS)
        listOf(stdout, stderr).forEach { capture ->
            try {
                capture.awaitUntil(drainDeadline)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        listOf(stdout, stderr).forEach(ProcessStreamCapture::closeIfReading)
        val closeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STREAM_CLOSE_WAIT_MILLIS)
        listOf(stdout, stderr).forEach { capture ->
            try {
                capture.awaitUntil(closeDeadline)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return CapturedStreams(
            stdout = stdout.value(),
            stderr = stderr.value(),
            truncated = stdout.truncated() || stderr.truncated() || stdout.isStillReading() || stderr.isStillReading(),
            interrupted = interrupted,
        )
    }

    private fun elapsedMs(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    private data class CapturedStreams(
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
        val interrupted: Boolean,
    )

    private data class ProcessTermination(
        val gracefully: Boolean,
        val terminated: Boolean,
        val interrupted: Boolean,
        val treeVerified: Boolean = true,
    )

    private data class TerminationWait(
        val terminated: Boolean,
        val interrupted: Boolean,
    )

    private fun mergeInterruptionError(
        capturedStderr: String,
        termination: ProcessTermination,
    ): String = listOf(
        capturedStderr.trimEnd(),
        "Command interrupted",
        when {
            !termination.terminated -> "Process remained alive after forced interruption wait"
            !termination.treeVerified -> ROOT_TREE_TERMINATION_UNVERIFIED
            termination.gracefully -> "Interruption termination completed during grace period"
            else -> "Interruption termination required forced process destruction"
        },
    )
        .filter(String::isNotBlank)
        .joinToString("\n")

}

internal fun mergeTimeoutError(
    capturedStderr: String,
    timeoutSeconds: Long,
    forced: Boolean = false,
    treeVerified: Boolean = true,
): String =
    listOf(
        capturedStderr.trimEnd(),
        "Command timed out after ${timeoutSeconds}s",
        if (forced) "Timeout termination required SIGKILL after rollback grace period" else "Timeout termination completed during rollback grace period",
        if (treeVerified) "" else ROOT_TREE_TERMINATION_UNVERIFIED,
    )
        .filter(String::isNotBlank)
        .joinToString("\n")

internal const val DEFAULT_TIMEOUT_GRACE_SECONDS = 30L
private const val FORCED_TERMINATION_WAIT_SECONDS = 5L
private const val PROCESS_WAIT_POLL_MILLIS = 1_000L
private const val INTERRUPTION_GRACE_MILLIS = 250L
private const val STREAM_DRAIN_WAIT_MILLIS = 1_000L
private const val STREAM_CLOSE_WAIT_MILLIS = 100L
internal const val INTERRUPTED_EXIT_CODE = 130
internal const val ROOT_TREE_TERMINATION_UNVERIFIED = "ROOT_TREE_TERMINATION_UNVERIFIED"

internal data class ProcessTreeTerminationAttempt(
    val verified: Boolean,
    val signaled: Boolean,
) {
    companion object {
        val NOT_APPLICABLE = ProcessTreeTerminationAttempt(verified = false, signaled = false)
    }
}

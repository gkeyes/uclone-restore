package com.uclone.restore.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

interface RootShellExecutor {
    suspend fun exec(command: String, timeoutSeconds: Long = 120): ShellResult
}

class ProcessRootShellExecutor : RootShellExecutor {
    override suspend fun exec(command: String, timeoutSeconds: Long): ShellResult =
        withContext(Dispatchers.IO) {
            val mountMasterResult = runSu(listOf("-mm", "-c", command), timeoutSeconds)
            if (mountMasterResult.isMountMasterUnsupported()) {
                runSu(listOf("-c", command), timeoutSeconds)
            } else {
                mountMasterResult
            }
        }

    private fun runSu(args: List<String>, timeoutSeconds: Long): ShellResult {
        val process = try {
            ProcessBuilder(listOf("su") + args).redirectErrorStream(false).start()
        } catch (error: java.io.IOException) {
            return ShellResult(127, "", error.message ?: "Unable to start su")
        }
        val stdoutFuture = streamReader(process.inputStream)
        val stderrFuture = streamReader(process.errorStream)
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ShellResult(124, stdoutFuture(), "Command timed out after ${timeoutSeconds}s")
        }
        return ShellResult(process.exitValue(), stdoutFuture(), stderrFuture())
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

    private fun streamReader(input: java.io.InputStream): () -> String {
        var result = ""
        val thread = Thread {
            result = input.bufferedReader().use { it.readText() }
        }
        thread.start()
        return {
            thread.join(1_000)
            result
        }
    }
}

fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

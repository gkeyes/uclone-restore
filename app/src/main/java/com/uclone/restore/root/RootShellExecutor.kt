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
            val process = try {
                ProcessBuilder("su", "-c", command).redirectErrorStream(false).start()
            } catch (error: java.io.IOException) {
                return@withContext ShellResult(127, "", error.message ?: "Unable to start su")
            }
            val stdoutFuture = streamReader(process.inputStream)
            val stderrFuture = streamReader(process.errorStream)
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ShellResult(124, stdoutFuture(), "Command timed out after ${timeoutSeconds}s")
            }
            ShellResult(process.exitValue(), stdoutFuture(), stderrFuture())
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

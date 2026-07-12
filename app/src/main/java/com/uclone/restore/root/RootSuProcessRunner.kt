package com.uclone.restore.root

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class RootSuProcessRunner(
    private val executablePrefix: List<String> = listOf("su"),
    private val shellPath: String = "/system/bin/sh",
    timeoutGraceSeconds: Long = DEFAULT_TIMEOUT_GRACE_SECONDS,
) : SuProcessRunner {
    private val commandRunner = ProcessCommandRunner(executablePrefix, timeoutGraceSeconds)

    override fun run(
        args: List<String>,
        standardInput: String?,
        timeoutSeconds: Long,
        onOutput: (ShellOutput) -> Unit,
    ): ShellResult {
        require(args.size >= 2 && args[args.lastIndex - 1] == "-c")
        val invocationArgs = args.dropLast(1)
        val rootPid = AtomicLong(-1L)
        val pidReady = CountDownLatch(1)
        val terminator = RootProcessTreeTerminator(executablePrefix, invocationArgs, rootPid, pidReady)
        val wrappedArgs = invocationArgs + wrapRootCommand(args.last())
        val result = commandRunner.runManaged(
            args = wrappedArgs,
            standardInput = standardInput,
            timeoutSeconds = timeoutSeconds,
            onOutput = { output ->
                val pid = output.controlPidOrNull()
                if (pid != null) {
                    rootPid.compareAndSet(-1L, pid)
                    pidReady.countDown()
                } else {
                    onOutput(output)
                }
            },
            processTreeTerminator = terminator::signal,
        )
        val diagnostics = terminator.diagnostics()
        return result.copy(
            stdout = result.stdout.withoutRootPidControlLine(),
            stderr = listOf(result.stderr.withoutRootPidControlLine(), diagnostics)
                .filter(String::isNotBlank)
                .joinToString("\n"),
        )
    }

    private fun wrapRootCommand(command: String): String =
        "printf '${ROOT_PID_CONTROL_PREFIX}%s\\n' \"\$\$\" >&2; exec ${shellQuote(shellPath)} -c ${shellQuote(command)}"
}

private class RootProcessTreeTerminator(
    private val executablePrefix: List<String>,
    private val invocationArgs: List<String>,
    private val rootPid: AtomicLong,
    private val pidReady: CountDownLatch,
) {
    private val trackedProcesses = linkedSetOf<String>()
    private val trace = mutableListOf<String>()

    @Synchronized
    fun signal(force: Boolean): ProcessTreeTerminationAttempt {
        if (rootPid.get() <= 1L) {
            runCatching { pidReady.await(ROOT_PID_WAIT_MILLIS, TimeUnit.MILLISECONDS) }
        }
        val pid = rootPid.get().takeIf { it > 1L }
            ?: return ProcessTreeTerminationAttempt(verified = false, signaled = false)
        val signal = if (force) 9 else 15
        val script = rootTreeSignalScript(pid, trackedProcesses.joinToString(" "), signal)
        val process = runCatching { ProcessBuilder(executablePrefix + invocationArgs + script).start() }.getOrNull()
            ?: return ProcessTreeTerminationAttempt(verified = false, signaled = false)
        runCatching { process.outputStream.close() }
        if (!runCatching { process.waitFor(ROOT_KILLER_WAIT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(ROOT_KILLER_FORCE_WAIT_SECONDS, TimeUnit.SECONDS) }
        }
        val lines = process.inputStream.bufferedReader().readLines()
        trace += "ROOT_TREE_SIGNAL_${if (force) "KILL" else "TERM"}:${lines.joinToString("|")}"
        if (!force) {
            lines.firstOrNull { it.startsWith("TRACKED=") }
                ?.removePrefix("TRACKED=")
                ?.split(' ')
                ?.filterTo(trackedProcesses) { PROCESS_TOKEN.matches(it) }
        }
        return ProcessTreeTerminationAttempt(
            verified = lines.any { it == "TREE_VERIFIED=1" },
            signaled = lines.any { it == "SIGNALED=1" },
        )
    }

    @Synchronized
    fun diagnostics(): String = trace.joinToString("\n")
}

private fun rootTreeSignalScript(rootPid: Long, knownPids: String, signal: Int): String = """
    ROOT_PID=$rootPid
    printf 'ROOT_RESOLVED=1\n'
    KNOWN_PIDS=${shellQuote(knownPids)}
    process_token() {
      TOKEN_PID="${'$'}1"
      TOKEN_START=0
      if [ -r "/proc/${'$'}TOKEN_PID/stat" ]; then
        TOKEN_START=${'$'}(awk '{ sub(/^[^)]*[)] /, ""); print ${'$'}20 }' "/proc/${'$'}TOKEN_PID/stat" 2>/dev/null)
        [ -n "${'$'}TOKEN_START" ] || TOKEN_START=0
      fi
      printf '%s:%s' "${'$'}TOKEN_PID" "${'$'}TOKEN_START"
    }
    collect_descendants() {
      kill -0 "${'$'}1" 2>/dev/null || return 0
      if [ -r "/proc/${'$'}1/task/${'$'}1/children" ]; then
        CHILDREN=${'$'}(cat "/proc/${'$'}1/task/${'$'}1/children" 2>/dev/null || true)
      else
        CHILDREN=${'$'}(ps -Ao pid=,ppid= 2>/dev/null | awk -v PARENT="${'$'}1" '${'$'}2 == PARENT { print ${'$'}1 }')
      fi
      for CHILD in ${'$'}CHILDREN; do
        collect_descendants "${'$'}CHILD"
      done
      process_token "${'$'}1"
      printf ' '
    }
    CURRENT_PIDS=${'$'}(collect_descendants "${'$'}ROOT_PID")
    for KNOWN_TOKEN in ${'$'}KNOWN_PIDS; do
      KNOWN_PID=${'$'}{KNOWN_TOKEN%%:*}
      case "${'$'}KNOWN_PID" in ''|*[!0-9]*) continue ;; esac
      CURRENT_PIDS="${'$'}CURRENT_PIDS ${'$'}(collect_descendants "${'$'}KNOWN_PID")"
    done
    ALL_PIDS="${'$'}KNOWN_PIDS ${'$'}CURRENT_PIDS"
    printf 'TRACKED=%s\\n' "${'$'}ALL_PIDS"
    SIGNALED=0
    for TOKEN in ${'$'}ALL_PIDS; do
      TARGET=${'$'}{TOKEN%%:*}
      EXPECTED_START=${'$'}{TOKEN#*:}
      case "${'$'}TARGET" in ''|*[!0-9]*) continue ;; esac
      [ "${'$'}TARGET" -gt 1 ] || continue
      if [ "${'$'}EXPECTED_START" != "0" ] && [ -r "/proc/${'$'}TARGET/stat" ]; then
        CURRENT_START=${'$'}(awk '{ sub(/^[^)]*[)] /, ""); print ${'$'}20 }' "/proc/${'$'}TARGET/stat" 2>/dev/null)
        [ "${'$'}CURRENT_START" = "${'$'}EXPECTED_START" ] || continue
      fi
      if kill -$signal "${'$'}TARGET" 2>/dev/null; then
        SIGNALED=1
      fi
    done
    printf 'SIGNALED=%s\\n' "${'$'}SIGNALED"
    TREE_VERIFIED=0
    VERIFY_ATTEMPT=0
    while [ "${'$'}VERIFY_ATTEMPT" -lt 20 ]; do
      TREE_ALIVE=0
      for TOKEN in ${'$'}ALL_PIDS; do
        TARGET=${'$'}{TOKEN%%:*}
        EXPECTED_START=${'$'}{TOKEN#*:}
        case "${'$'}TARGET" in ''|*[!0-9]*) continue ;; esac
        [ "${'$'}TARGET" -gt 1 ] || continue
        kill -0 "${'$'}TARGET" 2>/dev/null || continue
        if [ "${'$'}EXPECTED_START" != "0" ] && [ -r "/proc/${'$'}TARGET/stat" ]; then
          CURRENT_START=${'$'}(awk '{ sub(/^[^)]*[)] /, ""); print ${'$'}20 }' "/proc/${'$'}TARGET/stat" 2>/dev/null)
          [ "${'$'}CURRENT_START" = "${'$'}EXPECTED_START" ] || continue
        fi
        TREE_ALIVE=1
        break
      done
      if [ "${'$'}TREE_ALIVE" = "0" ]; then
        TREE_VERIFIED=1
        break
      fi
      sleep 0.05
      VERIFY_ATTEMPT=${'$'}((VERIFY_ATTEMPT + 1))
    done
    printf 'TREE_VERIFIED=%s\\n' "${'$'}TREE_VERIFIED"
""".trimIndent()

private fun ShellOutput.controlPidOrNull(): Long? =
    line.takeIf { stream == ShellStream.STDERR && it.startsWith(ROOT_PID_CONTROL_PREFIX) }
        ?.removePrefix(ROOT_PID_CONTROL_PREFIX)
        ?.trim()
        ?.toLongOrNull()

private fun String.withoutRootPidControlLine(): String {
    if (ROOT_PID_CONTROL_PREFIX !in this) return this
    return lineSequence().filterNot { it.startsWith(ROOT_PID_CONTROL_PREFIX) }.joinToString("\n")
}

private const val ROOT_PID_CONTROL_PREFIX = "__UCLONE_ROOT_SHELL_PID__="
private const val ROOT_PID_WAIT_MILLIS = 250L
private const val ROOT_KILLER_WAIT_SECONDS = 2L
private const val ROOT_KILLER_FORCE_WAIT_SECONDS = 1L
private val PROCESS_TOKEN = Regex("[0-9]+:[0-9]+")

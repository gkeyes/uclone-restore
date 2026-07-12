package com.uclone.restore.root

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessRootShellExecutorTest {
    @Test
    fun unsupportedMountMasterIsProbedOnceThenPlainModeIsCached() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val runner = SuProcessRunner { args, _, _, onOutput ->
            calls += args
            when {
                args.take(2) == listOf("-mm", "-c") -> ShellResult(
                    exitCode = 1,
                    stdout = "",
                    stderr = "su: invalid option -- m",
                )
                else -> {
                    onOutput(ShellOutput(ShellStream.STDOUT, args.last()))
                    ShellResult(0, args.last(), "")
                }
            }
        }
        val executor = ProcessRootShellExecutor(runner)
        val output = mutableListOf<String>()

        val first = executor.execStreaming("first", 10) { output += it.line }
        val second = executor.execStreaming("second", 10) { output += it.line }

        assertEquals(
            listOf(
                listOf("-mm", "-c", "exit 0"),
                listOf("-c", "first"),
                listOf("-c", "second"),
            ),
            calls,
        )
        assertEquals(2, first.processStarts)
        assertEquals(1, second.processStarts)
        assertEquals(listOf("first", "second"), output)
    }

    @Test
    fun supportedMountMasterModeIsCached() = runBlocking {
        val calls = mutableListOf<List<String>>()
        val runner = SuProcessRunner { args, _, _, _ ->
            calls += args
            ShellResult(0, "", "")
        }
        val executor = ProcessRootShellExecutor(runner)

        executor.exec("first", 10)
        executor.exec("second", 10)

        assertEquals(
            listOf(
                listOf("-mm", "-c", "exit 0"),
                listOf("-mm", "-c", "first"),
                listOf("-mm", "-c", "second"),
            ),
            calls,
        )
    }

    @Test
    fun firstRealCommandDoesNotHoldCapabilityLock() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val runner = SuProcessRunner { args, _, _, _ ->
            when (args.last()) {
                "first" -> {
                    firstStarted.countDown()
                    releaseFirst.await(5, TimeUnit.SECONDS)
                }
                "second" -> secondStarted.countDown()
            }
            ShellResult(0, "", "")
        }
        val executor = ProcessRootShellExecutor(runner)

        val first = async(Dispatchers.Default) { executor.exec("first", 10) }
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { executor.exec("second", 10) }
        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        releaseFirst.countDown()
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
    }

    @Test
    fun streamReaderSplitsHugeUnbrokenOutputIntoBoundedFragments() {
        val fragments = mutableListOf<String>()
        val source = "x".repeat(MAX_STREAM_FRAGMENT_CHARS * 4 + 17)

        readBoundedStream(ByteArrayInputStream(source.toByteArray())) { fragments += it }

        assertEquals(source.length, fragments.sumOf(String::length))
        assertTrue(fragments.all { it.length <= MAX_STREAM_FRAGMENT_CHARS })
        assertTrue(fragments.size > 1)
    }

    @Test
    fun processTimeoutPreservesCapturedStderr() {
        val runner = ProcessCommandRunner(listOf("/bin/sh"), timeoutGraceSeconds = 2)

        val result = runner.run(
            listOf("-c", "echo before-timeout >&2; sleep 2"),
            standardInput = null,
            timeoutSeconds = 1,
            onOutput = {},
        )

        assertEquals(124, result.exitCode)
        assertTrue("before-timeout" in result.stderr)
        assertTrue("timed out" in result.stderr)
        assertTrue("rollback grace period" in result.stderr)
        assertFalse(result.isSuccess)
    }

    @Test
    fun timeoutReportsUnverifiedRootTreeWhenRootPidWasNeverObserved() {
        val runner = ProcessCommandRunner(listOf("/bin/sh"), timeoutGraceSeconds = 0)

        val result = runner.runManaged(
            args = listOf("-c", "trap '' TERM; while :; do :; done"),
            standardInput = null,
            timeoutSeconds = 1,
            onOutput = {},
            processTreeTerminator = {
                ProcessTreeTerminationAttempt(verified = false, signaled = false)
            },
        )

        assertEquals(124, result.exitCode)
        assertTrue(ROOT_TREE_TERMINATION_UNVERIFIED in result.stderr)
    }

    @Test
    fun cancellingCoroutineTerminatesRealProcessPromptly() = runBlocking {
        val processStarted = CountDownLatch(1)
        val processId = AtomicLong(-1)
        val processRunner = ProcessCommandRunner(listOf("/bin/sh"), timeoutGraceSeconds = 0)
        val runner = SuProcessRunner { args, standardInput, timeoutSeconds, onOutput ->
            if (args.take(2) == listOf("-mm", "-c")) {
                ShellResult(1, "", "su: invalid option -- m")
            } else {
                processRunner.run(args, standardInput, timeoutSeconds) { output ->
                    output.line.toLongOrNull()?.let { pid ->
                        processId.set(pid)
                        processStarted.countDown()
                    }
                    onOutput(output)
                }
            }
        }
        val executor = ProcessRootShellExecutor(runner)
        val command = "echo \$\$; trap '' TERM; while :; do :; done"
        val execution = async(Dispatchers.Default) {
            executor.execStreaming(command, timeoutSeconds = 3) {}
        }

        try {
            assertTrue(processStarted.await(2, TimeUnit.SECONDS))
            execution.cancel()

            val completedPromptly = withTimeoutOrNull(2_000) {
                execution.join()
                true
            } ?: false

            assertTrue(completedPromptly, "Cancelled execution did not return promptly")
            assertFalse(isProcessAlive(processId.get()))
        } finally {
            val pid = processId.get()
            if (isProcessAlive(pid)) {
                ProcessBuilder("/bin/kill", "-KILL", pid.toString()).start().waitFor()
            }
            execution.cancel()
            withTimeoutOrNull(5_000) { execution.join() }
        }
    }

    @Test
    fun cancellingRootRunnerTerminatesShellAndItsChildProcess() = runBlocking {
        val fakeSu = createFakeSu()
        val rootPid = AtomicLong(-1)
        val childPid = AtomicLong(-1)
        val childReady = CountDownLatch(1)
        val executor = ProcessRootShellExecutor(
            RootSuProcessRunner(
                executablePrefix = listOf(fakeSu.absolutePath),
                shellPath = "/bin/sh",
                timeoutGraceSeconds = 1,
            ),
        )
        val execution = async(Dispatchers.Default) {
            executor.execStreaming(
                "echo root=${'$'}${'$'}; /bin/sh -c 'trap \"\" TERM; while :; do sleep 1; done' & CHILD=${'$'}!; echo child=${'$'}CHILD; wait \"${'$'}CHILD\"",
                timeoutSeconds = 60,
            ) { output ->
                when {
                    output.line.startsWith("root=") -> rootPid.set(output.line.substringAfter('=').toLong())
                    output.line.startsWith("child=") -> {
                        childPid.set(output.line.substringAfter('=').toLong())
                        childReady.countDown()
                    }
                }
            }
        }

        try {
            assertTrue(childReady.await(3, TimeUnit.SECONDS))
            execution.cancel()
            assertTrue(withTimeoutOrNull(4_000) { execution.join(); true } == true)
            assertTrue(waitUntilProcessExits(rootPid.get()), "Root shell survived cancellation")
            assertTrue(waitUntilProcessExits(childPid.get()), "Root shell child survived cancellation")
        } finally {
            listOf(childPid.get(), rootPid.get()).filter(::isProcessAlive).forEach { pid ->
                ProcessBuilder("/bin/kill", "-KILL", pid.toString()).start().waitFor()
            }
            execution.cancel()
            withTimeoutOrNull(5_000) { execution.join() }
            fakeSu.delete()
        }
    }

    @Test
    fun rootRunnerTimeoutTerminatesShellAndItsChildProcess() = runBlocking {
        val fakeSu = createFakeSu()
        val rootPid = AtomicLong(-1)
        val childPid = AtomicLong(-1)
        val executor = ProcessRootShellExecutor(
            RootSuProcessRunner(
                executablePrefix = listOf(fakeSu.absolutePath),
                shellPath = "/bin/sh",
                timeoutGraceSeconds = 1,
            ),
        )

        try {
            val result = executor.execStreaming(
                "echo root=${'$'}${'$'}; /bin/sh -c 'trap \"\" TERM; while :; do sleep 1; done' & CHILD=${'$'}!; echo child=${'$'}CHILD; wait \"${'$'}CHILD\"",
                timeoutSeconds = 1,
            ) { output ->
                if (output.line.startsWith("root=")) rootPid.set(output.line.substringAfter('=').toLong())
                if (output.line.startsWith("child=")) childPid.set(output.line.substringAfter('=').toLong())
            }

            assertEquals(124, result.exitCode)
            assertTrue(waitUntilProcessExits(rootPid.get()), "Timed-out root shell survived")
            assertTrue(waitUntilProcessExits(childPid.get()), "Timed-out root child survived: ${result.stderr}")
        } finally {
            listOf(childPid.get(), rootPid.get()).filter(::isProcessAlive).forEach { pid ->
                ProcessBuilder("/bin/kill", "-KILL", pid.toString()).start().waitFor()
            }
            fakeSu.delete()
        }
    }

    @Test
    fun interruptedRunnerReturnsNonSuccessAndTerminatesRealProcess() {
        val processReady = CountDownLatch(2)
        val processId = AtomicLong(-1)
        val result = AtomicReference<ShellResult?>()
        val runner = ProcessCommandRunner(listOf("/bin/sh"), timeoutGraceSeconds = 0)
        val worker = Thread {
            result.set(
                runner.run(
                    listOf("-c", "echo \$\$; echo before-interrupt >&2; echo ready; trap '' TERM; while :; do :; done"),
                    standardInput = null,
                    timeoutSeconds = 0,
                ) { output ->
                    output.line.toLongOrNull()?.let(processId::set)
                    if (output.line == "ready") processReady.countDown()
                    if (output.line == "before-interrupt") processReady.countDown()
                },
            )
        }.apply { isDaemon = true }
        worker.start()

        try {
            assertTrue(processReady.await(2, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(2_000)

            assertFalse(worker.isAlive)
            val interruptedResult = result.get()
            assertEquals(130, interruptedResult?.exitCode)
            assertTrue("before-interrupt" in interruptedResult?.stderr.orEmpty())
            assertTrue("Command interrupted" in interruptedResult?.stderr.orEmpty())
            assertFalse(isProcessAlive(processId.get()))
        } finally {
            val pid = processId.get()
            if (isProcessAlive(pid)) {
                ProcessBuilder("/bin/kill", "-KILL", pid.toString()).start().waitFor()
            }
            worker.interrupt()
            worker.join(5_000)
        }
    }

    @Test
    fun protectedInputIsWrittenToStdinAndNeverAddedToCommandArguments() = runBlocking {
        val calls = mutableListOf<Pair<List<String>, String?>>()
        val runner = SuProcessRunner { args, standardInput, _, _ ->
            calls += args to standardInput
            ShellResult(0, "", "")
        }
        val executor = ProcessRootShellExecutor(runner)

        executor.execStreamingWithInput("read secret", "123456\n", 10) {}

        val commandCall = calls.last()
        assertEquals("123456\n", commandCall.second)
        assertFalse(commandCall.first.any { "123456" in it })
    }

    private fun isProcessAlive(pid: Long): Boolean {
        if (pid <= 0 || ProcessBuilder("/bin/kill", "-0", pid.toString()).start().waitFor() != 0) return false
        val stateProcess = ProcessBuilder("/bin/ps", "-o", "stat=", "-p", pid.toString()).start()
        val state = stateProcess.inputStream.bufferedReader().readText().trim()
        stateProcess.waitFor()
        return !state.startsWith("Z")
    }

    private fun createFakeSu() = Files.createTempFile("uclone-fake-su-", ".sh").toFile().apply {
        writeText(
            """
                #!/bin/sh
                if [ "${'$'}1" = "-mm" ]; then shift; fi
                [ "${'$'}1" = "-c" ] || exit 64
                shift
                exec /bin/sh -c "${'$'}1"
            """.trimIndent(),
        )
        check(setExecutable(true))
        deleteOnExit()
    }

    private fun waitUntilProcessExits(pid: Long): Boolean {
        repeat(20) {
            if (!isProcessAlive(pid)) return true
            Thread.sleep(50)
        }
        return !isProcessAlive(pid)
    }
}

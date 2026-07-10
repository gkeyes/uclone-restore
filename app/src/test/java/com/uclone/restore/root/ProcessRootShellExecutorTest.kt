package com.uclone.restore.root

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

}

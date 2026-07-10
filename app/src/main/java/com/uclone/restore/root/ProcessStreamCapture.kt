package com.uclone.restore.root

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

internal class ProcessStreamCapture private constructor(
    private val input: InputStream,
    private val thread: Thread,
    private val buffer: BoundedLineBuffer,
) {
    fun awaitUntil(deadlineNanos: Long) {
        if (!thread.isAlive) return
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) return
        val millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
        val nanos = (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
        thread.join(millis, nanos)
    }

    fun closeIfReading() {
        if (thread.isAlive) runCatching(input::close)
    }

    fun isStillReading(): Boolean = thread.isAlive

    fun value(): String = buffer.value()

    fun truncated(): Boolean = buffer.isTruncated

    companion object {
        fun start(
            input: InputStream,
            stream: ShellStream,
            onOutput: (ShellOutput) -> Unit,
        ): ProcessStreamCapture {
            val buffer = BoundedLineBuffer(MAX_CAPTURE_CHARS)
            val thread = Thread({
                try {
                    readBoundedStream(input, MAX_STREAM_FRAGMENT_CHARS) { fragment ->
                        buffer.append(fragment)
                        runCatching { onOutput(ShellOutput(stream, fragment)) }
                    }
                } catch (_: IOException) {}
            }, "uclone-shell-${stream.name.lowercase()}").apply {
                isDaemon = true
                start()
            }
            return ProcessStreamCapture(input, thread, buffer)
        }
    }
}

internal fun readBoundedStream(
    input: InputStream,
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

private const val MAX_CAPTURE_CHARS = 512 * 1024
internal const val MAX_STREAM_FRAGMENT_CHARS = 8 * 1024

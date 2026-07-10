package com.uclone.restore.sync

internal class TaskProgressThrottle(
    private val intervalMs: Long = 350,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var lastEmissionAt = Long.MIN_VALUE

    @Synchronized
    fun shouldEmit(force: Boolean = false): Boolean {
        val now = nowMs()
        if (force || lastEmissionAt == Long.MIN_VALUE || now - lastEmissionAt >= intervalMs) {
            lastEmissionAt = now
            return true
        }
        return false
    }
}

internal class LiveLogTail(
    private val maxLines: Int = 50,
    private val maxChars: Int = 16 * 1024,
) {
    private val lines = ArrayDeque<String>()
    private var chars = 0

    @Synchronized
    fun append(line: String) {
        lines.addLast(line)
        chars += line.length
        while ((lines.size > maxLines || chars > maxChars) && lines.isNotEmpty()) {
            chars -= lines.removeFirst().length
        }
    }

    @Synchronized
    fun value(): String = lines.joinToString("\n")
}

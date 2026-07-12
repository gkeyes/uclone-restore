package com.uclone.restore.sync

import com.uclone.restore.root.ShellResult

internal class RootTaskOutput private constructor(
    private val lines: List<String>,
) {
    fun has(code: String): Boolean = lines.any { it.matchesCode(code) }

    fun hasPrefix(prefix: String): Boolean = lines.any { it.startsWith(prefix) }

    fun countPrefix(prefix: String): Int = lines.count { it.startsWith(prefix) }

    fun first(code: String): String? = lines.firstOrNull { it.matchesCode(code) }

    fun firstPrefix(prefix: String): String? = lines.firstOrNull { it.startsWith(prefix) }

    companion object {
        fun parse(output: String): RootTaskOutput = RootTaskOutput(
            output.lineSequence()
                .map(String::trim)
                .map { it.removePrefix(STDERR_PREFIX).trimStart() }
                .filter(String::isNotBlank)
                .toList(),
        )

        fun from(result: ShellResult): RootTaskOutput = parse(result.stderr + "\n" + result.stdout)

        private const val STDERR_PREFIX = "STDERR: "
    }
}

private fun String.matchesCode(code: String): Boolean {
    if (!startsWith(code)) return false
    if (length == code.length) return true
    return this[code.length] in CODE_DELIMITERS
}

private val CODE_DELIMITERS = charArrayOf(':', '=', ' ', '\t')

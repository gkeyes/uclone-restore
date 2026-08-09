package com.uclone.restore.sync

internal fun shellFunction(script: String, name: String): String {
    val lines = script.lines()
    val header = Regex("^\\s*${Regex.escape(name)}\\(\\) \\{\\s*$")
    val start = lines.indexOfFirst { header.matches(it) }
    check(start >= 0) { "Missing shell function: $name" }
    var depth = 0
    for (index in start until lines.size) {
        val trimmed = lines[index].trim()
        if (trimmed.endsWith("{") && !trimmed.contains("}")) depth += 1
        if (trimmed == "}" || trimmed == "};") {
            depth -= 1
            if (depth == 0) return lines.subList(start, index + 1).joinToString("\n")
        }
    }
    error("Unterminated shell function: $name")
}

package com.uclone.restore.root

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val processStarts: Int = 1,
    val durationMs: Long = 0,
    val outputTruncated: Boolean = false,
) {
    val isSuccess: Boolean = exitCode == 0
}

package com.uclone.restore.root

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean = exitCode == 0
}

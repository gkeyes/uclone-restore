package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.root.INTERRUPTED_EXIT_CODE
import com.uclone.restore.root.ShellResult

internal object TaskOutcome {
    fun status(result: ShellResult): TaskStatus {
        val output = result.stderr + "\n" + result.stdout
        return when {
            "AUTO_ROLLBACK_FAILED" in output -> TaskStatus.FAILED_FATAL
            "Timeout termination required SIGKILL" in output -> TaskStatus.FAILED_FATAL
            "AUTO_ROLLBACK_SUCCESS" in output -> TaskStatus.ROLLED_BACK
            result.exitCode == INTERRUPTED_EXIT_CODE || "Command interrupted" in output -> TaskStatus.INTERRUPTED
            result.isSuccess && PERMISSION_WARNING_MARKERS.any(output::contains) -> TaskStatus.SUCCESS_WITH_WARNINGS
            result.isSuccess -> TaskStatus.SUCCESS
            else -> TaskStatus.FAILED
        }
    }

    fun failureMessage(status: TaskStatus): String? = when (status) {
        TaskStatus.ROLLED_BACK -> "操作失败，已自动恢复操作前数据"
        TaskStatus.FAILED_FATAL -> "操作失败且自动回滚失败，请勿启动目标 App，并查看日志"
        TaskStatus.INTERRUPTED -> "任务已中断"
        else -> null
    }

    private val PERMISSION_WARNING_MARKERS = listOf(
        "WARN_REVOKE_FAILED:",
        "WARN_GRANT_FAILED:",
        "WARN_APPOPS_",
        "WARN_STOP_CLONE_",
    )
}

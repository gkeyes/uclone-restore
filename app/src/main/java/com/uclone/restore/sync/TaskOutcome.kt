package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.root.ShellResult

internal object TaskOutcome {
    fun status(result: ShellResult): TaskStatus {
        val output = result.stderr + "\n" + result.stdout
        return when {
            "RECOVERY_REQUIRED:" in output -> TaskStatus.FAILED_FATAL
            "AUTO_ROLLBACK_FAILED" in output -> TaskStatus.FAILED_FATAL
            "Timeout termination required SIGKILL" in output -> TaskStatus.FAILED_FATAL
            "INSTALL_PARTIAL_SUCCESS" in output -> TaskStatus.SUCCESS_WITH_WARNINGS
            "AUTO_ROLLBACK_SUCCESS" in output -> TaskStatus.ROLLED_BACK
            result.isSuccess && PERMISSION_WARNING_MARKERS.any(output::contains) -> TaskStatus.SUCCESS_WITH_WARNINGS
            result.isSuccess -> TaskStatus.SUCCESS
            result.exitCode in UNIX_SIGNAL_EXIT_CODES -> TaskStatus.INTERRUPTED
            else -> TaskStatus.FAILED
        }
    }

    fun failureMessage(status: TaskStatus, output: String = ""): String? = when {
        status == TaskStatus.FAILED_FATAL && "INSTALL_PACKAGE_PRESERVED" in output ->
            "App 已安装到另一侧，但数据同步和自动回滚未完成；安装结果已保留，请勿启动目标 App，并查看日志"
        status == TaskStatus.FAILED_FATAL &&
            "RECOVERY_REQUIRED:" in output &&
            "target=user10" in output &&
            "reason=partial_sync" in output ->
            "分数据同步中断，user10 目标可能不完整；user0 仍保留当前 CLONE 数据，MAIN 还原尚未开始，请重新同步并查看日志"
        status == TaskStatus.FAILED_FATAL &&
            "RECOVERY_REQUIRED:mode=DANGEROUS_FAST" in output &&
            "rollback=unavailable" in output ->
            "危险快速返回在恢复 MAIN 时失败；本次没有本地 CLONE 检查点，user0 状态已标记为未知，请勿启动目标 App，并查看日志"
        status == TaskStatus.ROLLED_BACK -> "操作失败，已自动恢复操作前数据"
        status == TaskStatus.FAILED_FATAL -> "操作失败且自动回滚失败，请勿启动目标 App，并查看日志"
        else -> null
    }

    private val PERMISSION_WARNING_MARKERS = listOf(
        "WARN_REVOKE_FAILED:",
        "WARN_GRANT_FAILED:",
        "WARN_APPOPS_",
        "WARN_PERMISSION_",
        "WARN_INSTALL_",
        "WARN_STOP_CLONE_",
        "WARN_STATE_BACKUP_",
        "WARN_DATA_STATE_",
        "WARN_CLONE_ROLLBACK_",
        "WARN_TRANSACTION_UNDO_",
    )

    private val UNIX_SIGNAL_EXIT_CODES = 129..192
}

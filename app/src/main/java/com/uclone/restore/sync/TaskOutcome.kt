package com.uclone.restore.sync

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskOutcomeCode
import com.uclone.restore.root.INTERRUPTED_EXIT_CODE
import com.uclone.restore.root.ROOT_TREE_TERMINATION_UNVERIFIED
import com.uclone.restore.root.ShellResult

internal object TaskOutcome {
    fun status(result: ShellResult): TaskStatus {
        val output = result.stderr + "\n" + result.stdout
        val events = RootTaskOutput.parse(output)
        return when {
            events.has("RECOVERY_REQUIRED") -> TaskStatus.RECOVERY_REQUIRED
            events.has("INSTALL_PARTIAL_FATAL") -> TaskStatus.FAILED_FATAL
            events.has("AUTO_ROLLBACK_FAILED") -> TaskStatus.FAILED_FATAL
            events.has(ROOT_TREE_TERMINATION_UNVERIFIED) -> TaskStatus.FAILED_FATAL
            events.has("Timeout termination required SIGKILL") -> TaskStatus.FAILED_FATAL
            result.isSuccess && events.has("WARN_INSTALL_SYNC_FAILED") -> TaskStatus.SUCCESS_WITH_WARNINGS
            events.has("AUTO_ROLLBACK_SUCCESS") -> TaskStatus.ROLLED_BACK
            events.has("UCLONE_CANCEL_ACCEPTED") -> TaskStatus.INTERRUPTED
            result.exitCode == INTERRUPTED_EXIT_CODE || events.has("Command interrupted") -> TaskStatus.INTERRUPTED
            result.isSuccess && events.has("UCLONE_RECOVERY") -> TaskStatus.SUCCESS_WITH_WARNINGS
            result.isSuccess && PERMISSION_WARNING_CODES.any(events::has) -> TaskStatus.SUCCESS_WITH_WARNINGS
            result.isSuccess && PERMISSION_WARNING_PREFIXES.any(events::hasPrefix) -> TaskStatus.SUCCESS_WITH_WARNINGS
            result.isSuccess -> TaskStatus.SUCCESS
            else -> TaskStatus.FAILED
        }
    }

    fun failureMessage(status: TaskStatus, result: ShellResult? = null): String? {
        val output = result?.let { it.stderr + "\n" + it.stdout }.orEmpty()
        val events = RootTaskOutput.parse(output)
        return when {
            events.has(ROOT_TREE_TERMINATION_UNVERIFIED) ->
                "Root 子进程是否终止无法确认；请勿启动相关 App，并先执行事务恢复检查"
            status == TaskStatus.ROLLED_BACK && events.has("ERR_FORCE_UPDATE_CLONE_DATA") ->
                "分数据更新失败，已回滚分系统；主数据未还原，当前仍保持分身态"
            status == TaskStatus.ROLLED_BACK && events.has("ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE") ->
                "分数据已更新，但主数据还原失败；主系统已回滚到还原前的分身态"
            status == TaskStatus.FAILED_FATAL && events.has("ERR_FORCE_UPDATE_CLONE_DATA") ->
                "分数据更新与自动回滚均失败；分身目标 App 保持冻结，主数据未还原"
            status == TaskStatus.FAILED_FATAL && events.has("ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE") ->
                "分数据已更新，但主数据还原与自动回滚均失败；主系统 App 保持冻结"
            status == TaskStatus.RECOVERY_REQUIRED && events.has("ERR_FORCE_UPDATE_CLONE_DATA") ->
                "分数据更新事务未完成；分身目标 App 保持冻结，主数据未还原"
            status == TaskStatus.RECOVERY_REQUIRED && events.has("ERR_RESTORE_MAIN_AFTER_CLONE_UPDATE") ->
                "分数据已更新，但主数据还原事务未完成；主系统 App 保持冻结"
            status == TaskStatus.ROLLED_BACK -> "操作失败，已自动恢复操作前数据"
            status == TaskStatus.FAILED_FATAL -> "操作失败且自动回滚失败，请勿启动目标 App，并查看日志"
            status == TaskStatus.RECOVERY_REQUIRED -> "数据事务未完成，目标 App 已保持冻结，请先执行安全恢复"
            status == TaskStatus.INTERRUPTED -> "任务已中断"
            else -> null
        }
    }

    fun code(result: ShellResult, status: TaskStatus): TaskOutcomeCode {
        val events = RootTaskOutput.from(result)
        return when {
            events.has("ERR_ACTIVE_ROOT_TASK") || events.has("ERR_ACTIVE_ROOT_TASK_INITIALIZING") ->
                TaskOutcomeCode.ACTIVE_ROOT_TASK
            events.first("UCLONE_RECOVERY")?.startsWith("UCLONE_RECOVERY:ORPHANED") == true ->
                TaskOutcomeCode.ORPHANED_LOCK_RECOVERED
            events.has("AUTO_ROLLBACK_FAILED") -> TaskOutcomeCode.AUTO_ROLLBACK_FAILED
            events.has(ROOT_TREE_TERMINATION_UNVERIFIED) -> TaskOutcomeCode.ROOT_TERMINATION_UNVERIFIED
            events.has("AUTO_ROLLBACK_SUCCESS") -> TaskOutcomeCode.AUTO_ROLLBACK_SUCCESS
            events.has("RECOVERY_REQUIRED") || status == TaskStatus.RECOVERY_REQUIRED -> TaskOutcomeCode.RECOVERY_REQUIRED
            status == TaskStatus.INTERRUPTED -> TaskOutcomeCode.INTERRUPTED
            status == TaskStatus.SUCCESS -> TaskOutcomeCode.SUCCESS
            status == TaskStatus.SUCCESS_WITH_WARNINGS -> TaskOutcomeCode.SUCCESS_WITH_WARNINGS
            else -> TaskOutcomeCode.FAILED
        }
    }

    private val PERMISSION_WARNING_CODES = listOf(
        "WARN_REVOKE_FAILED",
        "WARN_GRANT_FAILED",
        "WARN_PERMISSION_RESTORE_SKIPPED_INVALID_CAPTURE",
        "WARN_VERSION_MISMATCH_ALLOWED",
        "WARN_LEGACY_PACKAGE_IDENTITY_ALLOWED",
    )

    private val PERMISSION_WARNING_PREFIXES = listOf(
        "WARN_APPOPS_",
        "WARN_STOP_CLONE_",
        "WARN_INSTALL_",
    )
}

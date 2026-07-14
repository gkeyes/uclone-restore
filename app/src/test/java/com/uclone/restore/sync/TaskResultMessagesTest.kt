package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskResultMessagesTest {
    @Test
    fun successMessageReportsPermissionWarnings() {
        val output = """
            STDOUT:
            RESTORED:/data/user/0/com.example.app ITEMS=10
            WARN_GRANT_FAILED:android.permission.CAMERA
            WARN_GRANT_FAILED:android.permission.POST_NOTIFICATIONS
            WARN_REVOKE_FAILED:android.permission.RECORD_AUDIO
            WARN_APPOPS_FAILED:CAMERA:allow
            WARN_APPOPS_FAILED:POST_NOTIFICATION:allow
            WARN_APPOPS_FAILED:MIUIOP(10001):allow
            WARN_APPOPS_RESET_FAILED
            WARN_APPOPS_WRITE_SETTINGS_FAILED
            STDERR:
            EXIT=0
        """.trimIndent()

        val message = TaskResultMessages.successMessage(output)

        assertEquals("完成，权限部分未完全恢复（权限授予 2 项，权限撤销 1 项，AppOps 5 项）", message)
    }

    @Test
    fun successMessageKeepsPlainCompletionWithoutPermissionWarnings() {
        val output = """
            STDOUT:
            RESTORED:/data/user/0/com.example.app ITEMS=10
            STDERR:
            EXIT=0
        """.trimIndent()

        val message = TaskResultMessages.successMessage(output)

        assertEquals("完成", message)
    }

    @Test
    fun successMessageReportsCloneAutoStopFailure() {
        val message = TaskResultMessages.successMessage("WARN_STOP_CLONE_PENDING:RUNNING_UNLOCKED")

        assertEquals("完成，但分身自动关闭失败，分身可能仍在运行", message)
    }

    @Test
    fun successMessageExplainsPreservedInstallAfterSyncFailure() {
        val output = "WARN_INSTALL_SYNC_FAILED:targetUser=10:exit=54\nINSTALL_PARTIAL_SUCCESS targetUser=10"

        assertEquals(
            "App 已安装到另一侧，但数据同步失败；安装结果已保留，请查看任务日志",
            TaskResultMessages.successMessage(output),
        )
    }

    @Test
    fun successMessageDoesNotHideStateOrRollbackWarnings() {
        val output = "WARN_CLONE_ROLLBACK_COMMIT_FAILED:exactRollback=/data/adb/uclone/clone_rollback/pkg/latest.tmp_1"

        assertEquals(
            "完成，状态记录或回滚保护存在警告（状态/回滚 1 项）",
            TaskResultMessages.successMessage(output),
        )
    }

    @Test
    fun skippedAutomaticMainRefreshIsVisibleAsAStateWarning() {
        val output = "WARN_MAIN_RETURN_REFRESH_SKIPPED:reason=main_state_not_confirmed"

        assertEquals(
            "完成，状态记录或回滚保护存在警告（状态/回滚 1 项）",
            TaskResultMessages.successMessage(output),
        )
    }

    @Test
    fun returnPlanIsVisibleInFinalNotificationAndHistoryMessage() {
        val messages = mapOf(
            "SYNC_SAFE" to Pair(3, "完成（安全同步，3 次完整写入）：分数据已同步到分身，MAIN 已恢复"),
            "SYNC_FAST" to Pair(2, "完成（危险快速同步，2 次完整写入）：分数据已同步到分身，MAIN 已恢复"),
            "DISCARD_SAFE" to Pair(2, "完成（安全丢弃，2 次完整写入）：分身数据未更新，MAIN 已恢复"),
            "DISCARD_FAST" to Pair(1, "完成（危险快速丢弃，1 次完整写入）：当前分数据已丢弃，MAIN 已恢复"),
        )

        messages.forEach { (plan, contract) ->
            assertEquals(contract.second, TaskResultMessages.successMessage("UCLONE_COPY_PASSES=${contract.first} plan=$plan"))
        }
    }
}

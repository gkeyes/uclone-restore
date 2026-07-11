package com.uclone.restore.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskResultMessagesTest {
    @Test
    fun failedInstallSyncRollbackReportsInstalledButUnsafeTarget() {
        val message = TaskResultMessages.fatalInstallMessage(
            """
                INSTALL_VERIFIED:user=10 package:com.example.app uid:1010001
                WARN_INSTALL_SYNC_FAILED:targetUser=10:exit=91
                AUTO_ROLLBACK_FAILED originalExit=58
                INSTALL_PARTIAL_FATAL targetUser=10
            """.trimIndent(),
        )

        assertEquals(
            "App 已安装到 user10，但数据同步与自动回滚均失败；请勿启动目标 App，并查看任务日志",
            message,
        )
    }

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
    fun installSyncFailureIsReportedAsPartialSuccess() {
        val output = """
            INSTALL_VERIFIED:user=10 package:com.example.app uid:1012345
            WARN_INSTALL_SYNC_FAILED:targetUser=10:exit=75
            INSTALL_PARTIAL_SUCCESS targetUser=10
        """.trimIndent()

        assertEquals(
            "App 已安装到 user10，但数据同步失败；目标 App 保持安装，不会自动卸载",
            TaskResultMessages.successMessage(output),
        )
    }

    @Test
    fun installPermissionWarningsStaySuccessfulButVisible() {
        val output = """
            INSTALL_VERIFIED:user=0 package:com.example.app uid:12345
            WARN_GRANT_FAILED:android.permission.CAMERA
            INSTALL_PERMISSIONS_DONE targetUser=0 grants=1 appops=0
        """.trimIndent()

        assertEquals(
            "App 已安装到 user0，但部分权限/AppOps 未能迁移",
            TaskResultMessages.successMessage(output),
        )
    }
}

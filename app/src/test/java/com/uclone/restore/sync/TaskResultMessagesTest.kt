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
}

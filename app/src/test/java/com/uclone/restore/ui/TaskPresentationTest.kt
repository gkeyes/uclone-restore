package com.uclone.restore.ui

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TaskPresentationTest {
    @Test
    fun everyTaskTypeHasAChineseUserFacingLabel() {
        val expected = mapOf(
            TaskType.CAPTURE_SNAPSHOT_FROM_CLONE to "建立分身主动备份",
            TaskType.RESTORE_SNAPSHOT_TO_MAIN to "恢复主动备份到主系统",
            TaskType.ROLLBACK_MAIN_DATA to "恢复主系统被动备份",
            TaskType.RESTORE_FROM_CLONE_LATEST to "备份分身并恢复到主系统",
            TaskType.SWITCH_TO_CLONE_STATE to "切换到分身态",
            TaskType.PUSH_MAIN_TO_CLONE to "推送主系统数据到分身",
            TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE to "恢复分身回滚备份",
            TaskType.RESTORE_SWITCH_MAIN_STATE to "还原主系统态",
            TaskType.RESET_SWITCH_STATE to "重置切换状态",
            TaskType.DELETE_SNAPSHOT to "删除主动备份",
            TaskType.DELETE_RESTORE_BACKUP to "删除被动备份",
            TaskType.PROBE_CLONE_CE to "检测分身 CE",
            TaskType.UNLOCK_CLONE_WITH_CREDENTIAL to "解锁分身",
            TaskType.DEBUG_CLONE_SYSTEM to "诊断分身系统",
            TaskType.AUDIT_RESTORE_CONSISTENCY to "生成恢复审计包",
            TaskType.CLEAR_LOGS to "清理任务日志",
            TaskType.RESET_WORKSPACE to "重置工作区",
            TaskType.START_CLONE_USER to "启动分身用户",
            TaskType.SWITCH_TO_CLONE_USER to "进入分身用户",
            TaskType.STOP_CLONE_USER to "关闭分身用户",
            TaskType.REPAIR_WORKSPACE_OWNERSHIP to "修复备份容量归属",
            TaskType.INSTALL_TO_OTHER_USER to "安装到另一用户",
            TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER to "安装并迁移权限",
            TaskType.INSTALL_AND_SYNC_TO_OTHER_USER to "安装并同步数据",
        )

        assertEquals(TaskType.entries.toSet(), expected.keys)
        expected.forEach { (type, label) ->
            assertEquals(label, type.userFacingLabel)
            assertNotEquals(type.name, type.userFacingLabel)
        }
    }

    @Test
    fun everyTaskStatusHasAChineseUserFacingLabel() {
        val expected = mapOf(
            TaskStatus.ACCEPTED to "已接受",
            TaskStatus.RUNNING to "进行中",
            TaskStatus.AUTO_ROLLING_BACK to "正在自动回滚",
            TaskStatus.ROLLED_BACK to "已回滚",
            TaskStatus.SUCCESS to "成功",
            TaskStatus.SUCCESS_WITH_WARNINGS to "成功（有警告）",
            TaskStatus.FAILED to "失败",
            TaskStatus.FAILED_FATAL to "严重失败",
            TaskStatus.INTERRUPTED to "已中断",
        )

        assertEquals(TaskStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, label) ->
            assertEquals(label, status.userFacingLabel)
            assertNotEquals(status.name, status.userFacingLabel)
        }
    }
}

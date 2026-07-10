package com.uclone.restore.ui

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType

val TaskType.userFacingLabel: String
    get() = when (this) {
        TaskType.CAPTURE_SNAPSHOT_FROM_CLONE -> "建立分身主动备份"
        TaskType.RESTORE_SNAPSHOT_TO_MAIN -> "恢复主动备份到主系统"
        TaskType.ROLLBACK_MAIN_DATA -> "恢复主系统被动备份"
        TaskType.RESTORE_FROM_CLONE_LATEST -> "备份分身并恢复到主系统"
        TaskType.SWITCH_TO_CLONE_STATE -> "切换到分身态"
        TaskType.PUSH_MAIN_TO_CLONE -> "推送主系统数据到分身"
        TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE -> "恢复分身回滚备份"
        TaskType.RESTORE_SWITCH_MAIN_STATE -> "还原主系统态"
        TaskType.DELETE_SNAPSHOT -> "删除主动备份"
        TaskType.DELETE_RESTORE_BACKUP -> "删除被动备份"
        TaskType.PROBE_CLONE_CE -> "检测分身 CE"
        TaskType.UNLOCK_CLONE_WITH_CREDENTIAL -> "解锁分身"
        TaskType.DEBUG_CLONE_SYSTEM -> "诊断分身系统"
        TaskType.AUDIT_RESTORE_CONSISTENCY -> "生成恢复审计包"
        TaskType.CLEAR_LOGS -> "清理任务日志"
        TaskType.RESET_WORKSPACE -> "重置工作区"
        TaskType.START_CLONE_USER -> "启动分身用户"
        TaskType.SWITCH_TO_CLONE_USER -> "进入分身用户"
        TaskType.STOP_CLONE_USER -> "关闭分身用户"
    }

val TaskStatus.userFacingLabel: String
    get() = when (this) {
        TaskStatus.ACCEPTED -> "已接受"
        TaskStatus.RUNNING -> "进行中"
        TaskStatus.AUTO_ROLLING_BACK -> "正在自动回滚"
        TaskStatus.ROLLED_BACK -> "已回滚"
        TaskStatus.SUCCESS -> "成功"
        TaskStatus.SUCCESS_WITH_WARNINGS -> "成功（有警告）"
        TaskStatus.FAILED -> "失败"
        TaskStatus.FAILED_FATAL -> "严重失败"
        TaskStatus.INTERRUPTED -> "已中断"
    }

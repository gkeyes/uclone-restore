package com.uclone.restore.ui

import com.uclone.restore.model.TaskStatus
import com.uclone.restore.model.TaskType
import com.uclone.restore.model.User10CeState

internal val TaskType.displayName: String
    get() = when (this) {
        TaskType.CAPTURE_SNAPSHOT_FROM_CLONE -> "建立主动备份"
        TaskType.RESTORE_SNAPSHOT_TO_MAIN -> "恢复主动快照"
        TaskType.ROLLBACK_MAIN_DATA -> "恢复主系统回滚"
        TaskType.RESTORE_FROM_CLONE_LATEST -> "备份并恢复分身数据"
        TaskType.SWITCH_TO_CLONE_STATE -> "切换到分身态"
        TaskType.PUSH_MAIN_TO_CLONE -> "推送到分身"
        TaskType.RESTORE_CLONE_ROLLBACK_TO_CLONE -> "恢复分身回滚"
        TaskType.RESTORE_SWITCH_MAIN_STATE -> "还原主系统态"
        TaskType.UPDATE_MAIN_RETURN_POINT -> "更新 MAIN 返回点"
        TaskType.DELETE_SNAPSHOT -> "删除主动快照"
        TaskType.DELETE_RESTORE_BACKUP -> "删除被动备份"
        TaskType.PROBE_CLONE_CE -> "检测分身数据状态"
        TaskType.UNLOCK_CLONE_WITH_CREDENTIAL -> "无感启动分身"
        TaskType.DEBUG_CLONE_SYSTEM -> "分身系统调试"
        TaskType.AUDIT_RESTORE_CONSISTENCY -> "生成恢复审计包"
        TaskType.CLEAR_LOGS -> "清理任务日志"
        TaskType.RESET_WORKSPACE -> "重置工作区"
        TaskType.SCAN_WORKSPACE_OWNERSHIP -> "扫描备份容量归属"
        TaskType.REPAIR_WORKSPACE_OWNERSHIP -> "修复备份容量归属"
        TaskType.INSTALL_TO_OTHER_USER -> "安装到另一侧"
        TaskType.INSTALL_WITH_PERMISSIONS_TO_OTHER_USER -> "安装并迁移权限"
        TaskType.INSTALL_AND_SYNC_TO_OTHER_USER -> "安装并同步数据"
        TaskType.START_CLONE_USER -> "启动分身系统"
        TaskType.SWITCH_TO_CLONE_USER -> "切换到分身系统"
        TaskType.STOP_CLONE_USER -> "关闭分身系统"
    }

internal val TaskStatus.displayName: String
    get() = when (this) {
        TaskStatus.ACCEPTED -> "等待执行"
        TaskStatus.RUNNING -> "执行中"
        TaskStatus.AUTO_ROLLING_BACK -> "正在自动回滚"
        TaskStatus.ROLLED_BACK -> "已回滚"
        TaskStatus.SUCCESS -> "已完成"
        TaskStatus.SUCCESS_WITH_WARNINGS -> "完成但有警告"
        TaskStatus.FAILED -> "失败"
        TaskStatus.FAILED_FATAL -> "严重失败"
        TaskStatus.INTERRUPTED -> "已中断"
    }

internal val User10CeState.userFacingLabel: String
    get() = when (this) {
        User10CeState.Unavailable -> "分身用户不可用"
        User10CeState.StartedLocked -> "已启动，数据未解锁"
        User10CeState.RunningUnlocked -> "已启动，数据已解锁"
        User10CeState.NotStarted -> "未启动"
        is User10CeState.Unknown -> "状态未知"
    }

internal val User10CeState.cloneLifecycleLabel: String
    get() = when (this) {
        User10CeState.StartedLocked,
        User10CeState.RunningUnlocked,
        -> "已启动"
        User10CeState.NotStarted -> "未启动"
        User10CeState.Unavailable -> "不可用"
        is User10CeState.Unknown -> "状态未知"
    }

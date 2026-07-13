package com.uclone.restore.sync

internal object TaskResultMessages {
    fun successMessage(output: String): String {
        if ("INSTALL_PARTIAL_SUCCESS" in output) {
            return "App 已安装到另一侧，但数据同步失败；安装结果已保留，请查看任务日志"
        }
        val grantWarnings = output.lineSequence().count { it.startsWith("WARN_GRANT_FAILED:") }
        val revokeWarnings = output.lineSequence().count { it.startsWith("WARN_REVOKE_FAILED:") }
        val appOpsWarnings = output.lineSequence().count { it.startsWith("WARN_APPOPS_") }
        val captureWarnings = output.lineSequence().count {
            it.startsWith("WARN_PERMISSION_") || it.startsWith("WARN_INSTALL_PERMISSION")
        }
        val cloneStopWarnings = output.lineSequence().count { it.startsWith("WARN_STOP_CLONE_") }
        val stateWarnings = output.lineSequence().count {
            it.startsWith("WARN_STATE_BACKUP_") ||
                it.startsWith("WARN_DATA_STATE_") ||
                it.startsWith("WARN_CLONE_ROLLBACK_") ||
                it.startsWith("WARN_TRANSACTION_UNDO_")
        }
        if (
            grantWarnings == 0 && revokeWarnings == 0 && appOpsWarnings == 0 &&
            captureWarnings == 0 && cloneStopWarnings == 0 && stateWarnings == 0
        ) {
            return "完成"
        }
        if (
            grantWarnings == 0 && revokeWarnings == 0 && appOpsWarnings == 0 &&
            captureWarnings == 0 && stateWarnings == 0
        ) {
            return "完成，但分身自动关闭失败，分身可能仍在运行"
        }

        val parts = buildList {
            if (grantWarnings > 0) add("权限授予 $grantWarnings 项")
            if (revokeWarnings > 0) add("权限撤销 $revokeWarnings 项")
            if (appOpsWarnings > 0) add("AppOps $appOpsWarnings 项")
            if (captureWarnings > 0) add("权限采集或迁移 $captureWarnings 项")
            if (cloneStopWarnings > 0) add("分身自动关闭失败")
            if (stateWarnings > 0) add("状态/回滚 $stateWarnings 项")
        }
        val summary = when {
            stateWarnings > 0 -> "状态记录或回滚保护存在警告"
            cloneStopWarnings > 0 -> "部分状态未完全恢复"
            else -> "权限部分未完全恢复"
        }
        return "完成，$summary（${parts.joinToString("，")}）"
    }
}

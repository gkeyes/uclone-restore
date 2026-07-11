package com.uclone.restore.sync

internal object TaskResultMessages {
    fun fatalInstallMessage(output: String): String? {
        if ("INSTALL_PARTIAL_FATAL " !in output || "AUTO_ROLLBACK_FAILED" !in output) return null
        val target = installTarget(output)
        return "App 已安装到 user${target.orEmpty()}，但数据同步与自动回滚均失败；请勿启动目标 App，并查看任务日志"
    }

    fun successMessage(output: String): String {
        val installTarget = installTarget(output)
        if ("WARN_INSTALL_SYNC_FAILED:" in output) {
            return if ("AUTO_ROLLBACK_FAILED" in output) {
                "App 已安装到 user${installTarget.orEmpty()}，但数据同步与自动回滚均失败；目标 App 保持安装，请先查看日志"
            } else {
                "App 已安装到 user${installTarget.orEmpty()}，但数据同步失败；目标 App 保持安装，不会自动卸载"
            }
        }
        if ("WARN_INSTALL_UID_UNKNOWN:" in output) {
            return "App 已安装到 user${installTarget.orEmpty()}，但暂时无法确认目标 UID；请刷新 App 列表后检查"
        }
        if ("INSTALL_SYNC_DONE " in output) return "App 已安装到 user${installTarget.orEmpty()}，数据同步完成"
        if ("INSTALL_PERMISSIONS_DONE " in output) {
            val permissionWarning = listOf(
                "WARN_INSTALL_PERMISSIONS_CAPTURE_FAILED:",
                "WARN_GRANT_FAILED:",
                "WARN_APPOPS_",
            ).any(output::contains)
            return if (permissionWarning) {
                "App 已安装到 user${installTarget.orEmpty()}，但部分权限/AppOps 未能迁移"
            } else {
                "App 已安装到 user${installTarget.orEmpty()}，权限/AppOps 迁移完成"
            }
        }
        if ("INSTALL_ONLY_DONE " in output) return "App 已安装到 user${installTarget.orEmpty()}，未迁移权限或数据"
        if ("UCLONE_RECOVERY:ORPHANED" in output) {
            return "完成；已隔离上次进程遗留的孤儿任务标记"
        }
        val ownershipChanged = output.lineSequence()
            .firstOrNull { it.startsWith("WORKSPACE_OWNER_REPAIR_DONE ") }
            ?.substringAfter("changed=")
            ?.trim()
            ?.toLongOrNull()
        if (ownershipChanged != null) return "备份容量归属修复完成，共处理 $ownershipChanged 个文件或目录"
        val grantWarnings = output.lineSequence().count { it.startsWith("WARN_GRANT_FAILED:") }
        val revokeWarnings = output.lineSequence().count { it.startsWith("WARN_REVOKE_FAILED:") }
        val appOpsWarnings = output.lineSequence().count { it.startsWith("WARN_APPOPS_") }
        val cloneStopWarnings = output.lineSequence().count { it.startsWith("WARN_STOP_CLONE_") }
        if (grantWarnings == 0 && revokeWarnings == 0 && appOpsWarnings == 0 && cloneStopWarnings == 0) return "完成"
        if (grantWarnings == 0 && revokeWarnings == 0 && appOpsWarnings == 0) {
            return "完成，但分身自动关闭失败，分身可能仍在运行"
        }

        val parts = buildList {
            if (grantWarnings > 0) add("权限授予 $grantWarnings 项")
            if (revokeWarnings > 0) add("权限撤销 $revokeWarnings 项")
            if (appOpsWarnings > 0) add("AppOps $appOpsWarnings 项")
            if (cloneStopWarnings > 0) add("分身自动关闭失败")
        }
        val summary = if (cloneStopWarnings > 0) "部分状态未完全恢复" else "权限部分未完全恢复"
        return "完成，$summary（${parts.joinToString("，")}）"
    }

    private fun installTarget(output: String): String? = output.lineSequence()
        .firstOrNull { it.startsWith("INSTALL_VERIFIED:") }
        ?.substringAfter("user=")
        ?.substringBefore(' ')
}

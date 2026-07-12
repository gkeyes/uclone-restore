package com.uclone.restore.sync

internal object TaskResultMessages {
    fun fatalInstallMessage(output: String): String? {
        val events = RootTaskOutput.parse(output)
        if (!events.has("INSTALL_PARTIAL_FATAL")) return null
        val target = installTarget(events)
        return "App 已安装到 user${target.orEmpty()}，但数据同步与自动回滚均失败；请勿启动目标 App，并查看任务日志"
    }

    fun successMessage(output: String): String {
        val events = RootTaskOutput.parse(output)
        val installTarget = installTarget(events)
        if (events.has("INSTALL_PARTIAL_FATAL")) {
            return "App 已安装到 user${installTarget.orEmpty()}，但数据同步与自动回滚均失败；请勿启动目标 App，并查看任务日志"
        }
        if (events.has("WARN_INSTALL_SYNC_FAILED")) {
            return if (events.has("AUTO_ROLLBACK_FAILED")) {
                "App 已安装到 user${installTarget.orEmpty()}，但数据同步与自动回滚均失败；目标 App 保持安装，请先查看日志"
            } else {
                "App 已安装到 user${installTarget.orEmpty()}，但数据同步失败；目标 App 保持安装，不会自动卸载"
            }
        }
        if (events.has("WARN_INSTALL_UID_UNKNOWN")) {
            return "App 已安装到 user${installTarget.orEmpty()}，但暂时无法确认目标 UID；请刷新 App 列表后检查"
        }
        if (events.has("INSTALL_SYNC_DONE")) return "App 已安装到 user${installTarget.orEmpty()}，数据同步完成"
        if (events.has("INSTALL_PERMISSIONS_DONE")) {
            val permissionWarning = events.has("WARN_INSTALL_PERMISSIONS_CAPTURE_FAILED") ||
                events.has("WARN_GRANT_FAILED") || events.hasPrefix("WARN_APPOPS_")
            return if (permissionWarning) {
                "App 已安装到 user${installTarget.orEmpty()}，但部分权限/AppOps 未能迁移"
            } else {
                "App 已安装到 user${installTarget.orEmpty()}，权限/AppOps 迁移完成"
            }
        }
        if (events.has("INSTALL_ONLY_DONE")) return "App 已安装到 user${installTarget.orEmpty()}，未迁移权限或数据"
        if (events.first("UCLONE_RECOVERY")?.startsWith("UCLONE_RECOVERY:ORPHANED") == true) {
            return "完成；已隔离上次进程遗留的孤儿任务标记"
        }
        if (events.has("WARN_LEGACY_PACKAGE_IDENTITY_ALLOWED")) {
            return "完成，但使用了缺少签名证书信息的旧版备份；请立即验证 App 数据和登录状态"
        }
        if (events.has("WARN_VERSION_MISMATCH_ALLOWED")) {
            return "完成，但备份与当前 App 版本不同；请立即验证 App 数据和登录状态"
        }
        val ownershipChanged = events.first("WORKSPACE_OWNER_REPAIR_DONE")
            ?.substringAfter("changed=")
            ?.trim()
            ?.toLongOrNull()
        if (ownershipChanged != null) return "备份容量归属修复完成，共处理 $ownershipChanged 个文件或目录"
        val grantWarnings = events.countPrefix("WARN_GRANT_FAILED:")
        val revokeWarnings = events.countPrefix("WARN_REVOKE_FAILED:")
        val appOpsWarnings = events.countPrefix("WARN_APPOPS_")
        val cloneStopWarnings = events.countPrefix("WARN_STOP_CLONE_")
        val completion = if (events.has("FORCE_UPDATE_CLONE_DATA_AND_MAIN_RESTORE_DONE")) {
            "分数据已更新，主数据已还原"
        } else {
            "完成"
        }
        if (grantWarnings == 0 && revokeWarnings == 0 && appOpsWarnings == 0 && cloneStopWarnings == 0) return completion
        if (grantWarnings == 0 && revokeWarnings == 0 && appOpsWarnings == 0) {
            return "$completion，但分身自动关闭失败，分身可能仍在运行"
        }

        val parts = buildList {
            if (grantWarnings > 0) add("权限授予 $grantWarnings 项")
            if (revokeWarnings > 0) add("权限撤销 $revokeWarnings 项")
            if (appOpsWarnings > 0) add("AppOps $appOpsWarnings 项")
            if (cloneStopWarnings > 0) add("分身自动关闭失败")
        }
        val summary = if (cloneStopWarnings > 0) "部分状态未完全恢复" else "权限部分未完全恢复"
        return if (completion == "完成") {
            "完成，$summary（${parts.joinToString("，")}）"
        } else {
            "$completion，但$summary（${parts.joinToString("，")}）"
        }
    }

    private fun installTarget(events: RootTaskOutput): String? = events.first("INSTALL_VERIFIED")
        ?.substringAfter("user=")
        ?.substringBefore(' ')
}

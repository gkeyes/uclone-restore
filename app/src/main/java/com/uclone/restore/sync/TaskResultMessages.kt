package com.uclone.restore.sync

internal object TaskResultMessages {
    fun successMessage(output: String): String {
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
}

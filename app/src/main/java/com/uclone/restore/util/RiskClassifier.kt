package com.uclone.restore.util

import com.uclone.restore.model.RiskLevel

object RiskClassifier {
    private val highRiskWords = listOf(
        "bank", "pay", "wallet", "alipay", "wechat", "qq", "tencent",
        "password", "authenticator", "securities", "broker", "enterprise",
    )

    fun classify(packageName: String, label: String, isSystemApp: Boolean): RiskLevel {
        if (isSystemApp) return RiskLevel.SYSTEM
        val haystack = "$packageName $label".lowercase()
        return if (highRiskWords.any(haystack::contains)) RiskLevel.HIGH else RiskLevel.NORMAL
    }
}

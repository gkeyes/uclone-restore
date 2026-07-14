package com.uclone.restore.ui

import com.uclone.restore.model.CloneReturnPlan
import com.uclone.restore.model.CloneSessionPolicy
import com.uclone.restore.model.MainReturnPointPolicy
import com.uclone.restore.model.SwitchSafetyMode
import com.uclone.restore.model.UCloneSettings
import com.uclone.restore.model.cloneReturnPlan

internal object SwitchPolicyText {
    fun mainReturnLabel(policy: MainReturnPointPolicy): String = when (policy) {
        MainReturnPointPolicy.FIXED -> "固定保存"
        MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT -> "每次离开 MAIN 时更新"
    }

    fun mainReturnDescription(policy: MainReturnPointPolicy): String = when (policy) {
        MainReturnPointPolicy.FIXED -> "首次切换时建立，之后保持不变；可在 App 详情中手动更新。"
        MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT ->
            "切换前的 MAIN 状态已明确确认时，用本次精确回滚更新返回点；状态未确认时保留旧返回点并给出警告。"
    }

    fun cloneSessionLabel(policy: CloneSessionPolicy): String = when (policy) {
        CloneSessionPolicy.SYNC_TO_CLONE_USER -> "同步到分身系统"
        CloneSessionPolicy.DISCARD_ON_MAIN_RETURN -> "不更新分身系统"
    }

    fun cloneSessionDescription(policy: CloneSessionPolicy, cloneUserId: Int): String = when (policy) {
        CloneSessionPolicy.SYNC_TO_CLONE_USER -> "还原 MAIN 前，把 user0 当前分数据同步到 user$cloneUserId。"
        CloneSessionPolicy.DISCARD_ON_MAIN_RETURN -> "还原 MAIN 时不读取或写入 user$cloneUserId；user0 当前分数据不会保留到分身系统。"
    }

    fun safetyLabel(mode: SwitchSafetyMode): String = when (mode) {
        SwitchSafetyMode.SAFE -> "安全保护"
        SwitchSafetyMode.DANGEROUS_FAST -> "危险快速"
    }

    fun safetyDescription(mode: SwitchSafetyMode): String = when (mode) {
        SwitchSafetyMode.SAFE -> "覆盖 user0 前保留本次操作专属检查点；失败时可自动回滚。"
        SwitchSafetyMode.DANGEROUS_FAST -> "省去本地检查点；恢复失败时 user0 会进入需要人工处理的未知状态。"
    }

    fun planLabel(settings: UCloneSettings): String = when (settings.cloneReturnPlan()) {
        CloneReturnPlan.SYNC_SAFE -> "同步分数据 + 安全保护"
        CloneReturnPlan.SYNC_FAST -> "同步分数据 + 危险快速"
        CloneReturnPlan.DISCARD_SAFE -> "不更新分身 + 安全保护"
        CloneReturnPlan.DISCARD_FAST -> "丢弃当前分数据 + 危险快速"
    }

    fun planSummary(settings: UCloneSettings): String = when (settings.cloneReturnPlan()) {
        CloneReturnPlan.SYNC_SAFE ->
            "CLONE → MAIN 共 3 次完整写入：user0 当前分数据保存为检查点、同步到 user${settings.cloneUserId}、恢复 MAIN。"
        CloneReturnPlan.SYNC_FAST ->
            "CLONE → MAIN 共 2 次完整写入：同步 user0 当前分数据到 user${settings.cloneUserId}，再恢复 MAIN；没有本地检查点。"
        CloneReturnPlan.DISCARD_SAFE ->
            "CLONE → MAIN 共 2 次完整写入：只在 user0 保存临时检查点并恢复 MAIN；不更新 user${settings.cloneUserId}，成功后删除检查点。"
        CloneReturnPlan.DISCARD_FAST ->
            "CLONE → MAIN 仅 1 次完整写入：直接用 MAIN 返回点覆盖 user0；不更新 user${settings.cloneUserId}，当前分数据会被丢弃且无法自动回滚。"
    }

    fun restoreConfirmation(settings: UCloneSettings): String = when (settings.cloneReturnPlan()) {
        CloneReturnPlan.SYNC_SAFE ->
            "安全同步，共 3 次完整写入：\n1. 保存 user${settings.mainUserId} 当前分数据检查点；\n2. 同步到 user${settings.cloneUserId}；\n3. 恢复 MAIN。\n同步失败不会开始恢复 MAIN；恢复失败可用本次检查点回滚。"
        CloneReturnPlan.SYNC_FAST ->
            "危险快速同步，共 2 次完整写入：\n1. 同步 user${settings.mainUserId} 当前分数据到 user${settings.cloneUserId}；\n2. 恢复 MAIN。\n没有本地检查点；MAIN 恢复失败时 user${settings.mainUserId} 会进入未知状态。"
        CloneReturnPlan.DISCARD_SAFE ->
            "安全丢弃，共 2 次完整写入：\n1. 在 user${settings.mainUserId} 本地保存临时分数据检查点；\n2. 恢复 MAIN。\n不会读取或修改 user${settings.cloneUserId}；恢复成功后删除临时检查点，失败时可自动回滚。"
        CloneReturnPlan.DISCARD_FAST ->
            "危险快速丢弃，仅 1 次完整写入：直接用 MAIN 返回点覆盖 user${settings.mainUserId}。\n不会读取或修改 user${settings.cloneUserId}；当前分数据不会保留，也没有本地检查点可回滚。"
    }

    fun switchToCloneConfirmation(settings: UCloneSettings, hasMainReturnPoint: Boolean): String {
        val returnPoint = when {
            !hasMainReturnPoint -> "会先用 user${settings.mainUserId} 当前 MAIN 数据建立返回点。"
            settings.mainReturnPointPolicy == MainReturnPointPolicy.FIXED -> "会保留现有固定 MAIN 返回点。"
            else -> "若当前 MAIN 状态已明确确认，会用切换前数据更新 MAIN 返回点；否则保留旧返回点并警告。"
        }
        return "$returnPoint\n随后从 user${settings.cloneUserId} 读取当前分数据覆盖 user${settings.mainUserId}。完成后进入 CLONE 状态。"
    }
}

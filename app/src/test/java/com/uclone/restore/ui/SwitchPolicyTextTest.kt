package com.uclone.restore.ui

import com.uclone.restore.model.CloneSessionPolicy
import com.uclone.restore.model.MainReturnPointPolicy
import com.uclone.restore.model.SwitchSafetyMode
import com.uclone.restore.model.UCloneSettings
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SwitchPolicyTextTest {
    @Test
    fun everyReturnCombinationExplainsItsCopyCountAndUser10Effect() {
        val cases = listOf(
            UCloneSettings() to Pair("3 次完整写入", "同步到 user10"),
            UCloneSettings(switchSafetyMode = SwitchSafetyMode.DANGEROUS_FAST) to
                Pair("2 次完整写入", "同步 user0 当前分数据到 user10"),
            UCloneSettings(cloneSessionPolicy = CloneSessionPolicy.DISCARD_ON_MAIN_RETURN) to
                Pair("2 次完整写入", "不更新 user10"),
            UCloneSettings(
                cloneSessionPolicy = CloneSessionPolicy.DISCARD_ON_MAIN_RETURN,
                switchSafetyMode = SwitchSafetyMode.DANGEROUS_FAST,
            ) to Pair("仅 1 次完整写入", "不更新 user10"),
        )

        cases.forEach { (settings, expected) ->
            val summary = SwitchPolicyText.planSummary(settings.copy(cloneUserId = 10))
            assertContains(summary, expected.first)
            assertContains(summary, expected.second)
        }
    }

    @Test
    fun switchConfirmationReflectsFixedAndRefreshPolicies() {
        val fixed = SwitchPolicyText.switchToCloneConfirmation(UCloneSettings(), hasMainReturnPoint = true)
        val refresh = SwitchPolicyText.switchToCloneConfirmation(
            UCloneSettings(mainReturnPointPolicy = MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT),
            hasMainReturnPoint = true,
        )

        assertContains(fixed, "保留现有固定 MAIN 返回点")
        assertContains(refresh, "状态已明确确认")
        assertContains(refresh, "保留旧返回点并警告")
    }

    @Test
    fun policyLabelsAreExplicitChoicesRatherThanReadOnlySources() {
        assertEquals("固定保存", SwitchPolicyText.mainReturnLabel(MainReturnPointPolicy.FIXED))
        assertEquals(
            "每次离开 MAIN 时更新",
            SwitchPolicyText.mainReturnLabel(MainReturnPointPolicy.REFRESH_ON_MAIN_EXIT),
        )
        assertEquals(
            "不更新分身系统",
            SwitchPolicyText.cloneSessionLabel(CloneSessionPolicy.DISCARD_ON_MAIN_RETURN),
        )
    }
}

package com.uclone.restore.model

enum class CloneReturnPlan(
    val syncsCloneUser: Boolean,
    val createsCheckpoint: Boolean,
    val copyPasses: Int,
) {
    SYNC_SAFE(syncsCloneUser = true, createsCheckpoint = true, copyPasses = 3),
    SYNC_FAST(syncsCloneUser = true, createsCheckpoint = false, copyPasses = 2),
    DISCARD_SAFE(syncsCloneUser = false, createsCheckpoint = true, copyPasses = 2),
    DISCARD_FAST(syncsCloneUser = false, createsCheckpoint = false, copyPasses = 1),
}

fun UCloneSettings.cloneReturnPlan(): CloneReturnPlan = when (cloneSessionPolicy) {
    CloneSessionPolicy.SYNC_TO_CLONE_USER -> when (switchSafetyMode) {
        SwitchSafetyMode.SAFE -> CloneReturnPlan.SYNC_SAFE
        SwitchSafetyMode.DANGEROUS_FAST -> CloneReturnPlan.SYNC_FAST
    }
    CloneSessionPolicy.DISCARD_ON_MAIN_RETURN -> when (switchSafetyMode) {
        SwitchSafetyMode.SAFE -> CloneReturnPlan.DISCARD_SAFE
        SwitchSafetyMode.DANGEROUS_FAST -> CloneReturnPlan.DISCARD_FAST
    }
}

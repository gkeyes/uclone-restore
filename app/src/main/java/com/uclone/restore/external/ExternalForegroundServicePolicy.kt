package com.uclone.restore.external

internal enum class ExternalForegroundWorkType {
    DATA_SYNC,
    SPECIAL_USE,
}

internal object ExternalForegroundServicePolicy {
    fun workType(source: String?, sdkInt: Int): ExternalForegroundWorkType =
        if (sdkInt >= 34 && source != ExternalActionContract.SOURCE_APP) {
            ExternalForegroundWorkType.SPECIAL_USE
        } else {
            ExternalForegroundWorkType.DATA_SYNC
        }
}

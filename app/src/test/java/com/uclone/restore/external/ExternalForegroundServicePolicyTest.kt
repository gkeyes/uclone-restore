package com.uclone.restore.external

import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalForegroundServicePolicyTest {
    @Test
    fun externalUserActionsUseSpecialTypeOnSupportedAndroidVersions() {
        listOf(
            ExternalActionContract.SOURCE_MODULE,
            ExternalActionContract.SOURCE_LAUNCHER_MODULE,
            ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT,
            null,
        ).forEach { source ->
            assertEquals(
                ExternalForegroundWorkType.SPECIAL_USE,
                ExternalForegroundServicePolicy.workType(source, sdkInt = 36),
                source,
            )
        }
    }

    @Test
    fun inAppAndOlderAndroidActionsUseDataSyncType() {
        assertEquals(
            ExternalForegroundWorkType.DATA_SYNC,
            ExternalForegroundServicePolicy.workType(ExternalActionContract.SOURCE_APP, sdkInt = 36),
        )
        assertEquals(
            ExternalForegroundWorkType.DATA_SYNC,
            ExternalForegroundServicePolicy.workType(ExternalActionContract.SOURCE_MODULE, sdkInt = 33),
        )
    }
}

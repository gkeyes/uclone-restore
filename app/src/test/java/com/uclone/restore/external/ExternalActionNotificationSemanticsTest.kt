package com.uclone.restore.external

import androidx.core.app.NotificationCompat
import kotlin.test.Test
import kotlin.test.assertEquals

class ExternalActionNotificationSemanticsTest {
    @Test
    fun stillRunningIsRenderedAsActiveWorkInsteadOfRejectedWork() {
        assertEquals(
            "仍在运行",
            externalActionNotificationStateText(ExternalActionContract.STATUS_STILL_RUNNING),
        )
        assertEquals(
            NotificationCompat.PRIORITY_DEFAULT,
            externalActionNotificationPriority(ExternalActionContract.STATUS_STILL_RUNNING),
        )
    }

    @Test
    fun duplicateRequestIsRenderedAsTheExistingActiveWork() {
        assertEquals(
            "已在运行",
            externalActionNotificationStateText(ExternalActionContract.STATUS_ALREADY_RUNNING),
        )
        assertEquals(
            NotificationCompat.PRIORITY_DEFAULT,
            externalActionNotificationPriority(ExternalActionContract.STATUS_ALREADY_RUNNING),
        )
    }
}

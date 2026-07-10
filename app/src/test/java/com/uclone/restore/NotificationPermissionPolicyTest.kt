package com.uclone.restore

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPermissionPolicyTest {
    @Test
    fun requestPermission_whenAndroid13OrNewerAndPermissionMissing() {
        assertTrue(shouldRequestNotificationPermission(sdkInt = 33, isGranted = false))
        assertTrue(shouldRequestNotificationPermission(sdkInt = 36, isGranted = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 32, isGranted = false))
        assertFalse(shouldRequestNotificationPermission(sdkInt = 36, isGranted = true))
    }
}

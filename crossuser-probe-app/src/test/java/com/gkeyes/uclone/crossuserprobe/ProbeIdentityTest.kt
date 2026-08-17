package com.gkeyes.uclone.crossuserprobe

import kotlin.test.Test
import kotlin.test.assertEquals

class ProbeIdentityTest {
    @Test
    fun androidUidSplitsIntoUserIdAndAppId() {
        val uid = 10 * ProbeIdentity.PER_USER_RANGE + 12345

        assertEquals(10, ProbeIdentity.userIdFromUid(uid))
        assertEquals(12345, ProbeIdentity.appIdFromUid(uid))
    }

    @Test
    fun markerTextMakesStorageAndUserExplicit() {
        assertEquals("user0-ce", ProbeIdentity.markerText(0, StorageKind.CE))
        assertEquals("user10-de", ProbeIdentity.markerText(10, StorageKind.DE))
    }
}

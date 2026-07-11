package com.uclone.restore.external

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalStatusRoutingTest {
    @Test
    fun onlyModuleSourcesHaveAnExternalStatusRecipient() {
        assertTrue(hasLauncherModuleStatusRecipient(ExternalActionContract.SOURCE_MODULE))
        assertTrue(hasLauncherModuleStatusRecipient(ExternalActionContract.SOURCE_LAUNCHER_MODULE))
        assertFalse(hasLauncherModuleStatusRecipient(ExternalActionContract.SOURCE_LAUNCHER_SHORTCUT))
        assertFalse(hasLauncherModuleStatusRecipient(ExternalActionContract.SOURCE_APP))
        assertFalse(hasLauncherModuleStatusRecipient("unknown"))
    }
}

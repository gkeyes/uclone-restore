package com.uclone.restore.launcher

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LauncherShortcutControllerTest {
    @Test
    fun trustedShortcutToken_requiresNonBlankExactMatch() {
        assertTrue(isTrustedLauncherShortcutToken("token-123", "token-123"))
        assertFalse(isTrustedLauncherShortcutToken(null, "token-123"))
        assertFalse(isTrustedLauncherShortcutToken("", "token-123"))
        assertFalse(isTrustedLauncherShortcutToken("token-123", ""))
        assertFalse(isTrustedLauncherShortcutToken("token-123", "other"))
    }
}

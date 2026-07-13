package com.uclone.restore.launcher

import com.uclone.restore.sync.AppDataState
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun shortcutActionLabel_reflectsUnifiedAppDataState() {
        assertEquals("切换", AppDataState.Main.launcherShortcutActionLabel)
        assertEquals("还原", AppDataState.Clone("main-return-point").launcherShortcutActionLabel)
        assertEquals("检查状态", AppDataState.Unknown.launcherShortcutActionLabel)
    }

    @Test
    fun launcherShortcutRequest_distinguishesDetailsOnlyNavigation() {
        assertFalse(LauncherShortcutRequest("com.example.app", nonce = 1L).openDetailsOnly)
        assertTrue(LauncherShortcutRequest("com.example.app", openDetailsOnly = true, nonce = 1L).openDetailsOnly)
    }

    @Test
    fun shortcutRefreshGeneration_advancesAndWrapsWithoutUsingZero() {
        assertEquals(1L, nextShortcutRefreshGeneration(0L))
        assertEquals(2L, nextShortcutRefreshGeneration(1L))
        assertEquals(1L, nextShortcutRefreshGeneration(Long.MAX_VALUE))
    }

    @Test
    fun shortcutRefreshGeneration_onlyClearsTheObservedGeneration() {
        assertTrue(canClearShortcutRefreshGeneration(expected = 4L, current = 4L))
        assertFalse(canClearShortcutRefreshGeneration(expected = 4L, current = 5L))
        assertFalse(canClearShortcutRefreshGeneration(expected = 0L, current = 0L))
    }

    @Test
    fun olderRefreshCannotPublishAfterNewerRefreshHasFinished() {
        val olderRefresh = 4L
        val newerRefresh = 5L

        assertFalse(canClearShortcutRefreshGeneration(expected = olderRefresh, current = newerRefresh))
        assertFalse(canClearShortcutRefreshGeneration(expected = olderRefresh, current = 0L))
        assertTrue(canClearShortcutRefreshGeneration(expected = newerRefresh, current = newerRefresh))
    }
}

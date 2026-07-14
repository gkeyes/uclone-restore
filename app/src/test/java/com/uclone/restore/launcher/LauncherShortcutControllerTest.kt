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

    @Test
    fun shortcutUpdate_skipsIdenticalVisiblePayloadButRepairsMissingSystemShortcut() {
        val signature = listOf(FavoriteShortcutSignature("com.example.app", "Example", "切换"))
        val expectedId = favoriteShortcutId("com.example.app")

        assertFalse(shouldUpdateFavoriteShortcuts(signature, signature, setOf(expectedId)))
        assertTrue(shouldUpdateFavoriteShortcuts(signature, signature, emptySet()))
        assertTrue(
            shouldUpdateFavoriteShortcuts(
                signature,
                listOf(signature.single().copy(actionLabel = "还原")),
                setOf(expectedId),
            ),
        )
        assertTrue(
            shouldUpdateFavoriteShortcuts(
                signature,
                listOf(signature.single().copy(iconVersion = 2)),
                setOf(expectedId),
            ),
        )
    }

    @Test
    fun shortcutUpdate_doesNotClearAnAlreadyEmptySystemList() {
        assertFalse(shouldUpdateFavoriteShortcuts(null, emptyList(), emptySet()))
    }
}

package com.uclone.restore.module.relay

import android.app.Application
import android.app.PendingIntent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ModuleRelayProviderTest {
    @Test
    fun callerValidation_requiresConfiguredSystemLauncherPackage() {
        val allowed = setOf("com.miui.home")
        val systemPackages = setOf("com.miui.home")

        assertTrue(isTrustedSystemLauncherCaller(setOf("com.miui.home"), allowed, systemPackages::contains))
        assertFalse(isTrustedSystemLauncherCaller(setOf("com.miui.home"), allowed) { false })
        assertFalse(isTrustedSystemLauncherCaller(setOf("com.android.launcher3"), allowed) { true })
        assertFalse(isTrustedSystemLauncherCaller(emptySet(), allowed, systemPackages::contains))
    }

    @Test
    fun pendingIntent_startsUCloneForegroundService_whenLauncherRequestsAction() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Application>()
        val provider = ModuleRelayProvider().apply { attachInfo(context, null) }
        ModuleSettingsStore.setHookEnabled(context, true)
        ModuleSettingsStore.setAllowedPackageText(context, TARGET_PACKAGE)
        val query = Bundle().apply {
            putString(ModuleRelayContract.EXTRA_PACKAGE_NAME, TARGET_PACKAGE)
            putInt(ModuleRelayContract.EXTRA_TARGET_USER_ID, 0)
            putString(ModuleRelayContract.EXTRA_REQUEST_ID, REQUEST_ID)
        }

        // When
        val result = ModuleRelayProvider::class.java
            .getDeclaredMethod("queryMenuState", Bundle::class.java)
            .apply { isAccessible = true }
            .invoke(provider, query) as Bundle
        @Suppress("DEPRECATION")
        val pendingIntent = result.getParcelable<PendingIntent>(ModuleRelayContract.EXTRA_PENDING_INTENT)
            ?: error("Provider did not return a PendingIntent")
        val shadow = shadowOf(pendingIntent)

        // Then
        assertTrue(shadow.isForegroundService, "Desktop action must directly cold-start a foreground service")
        assertTrue(shadow.isImmutable, "Launcher must not be able to mutate the UClone action")
        assertTrue(shadow.flags and PendingIntent.FLAG_ONE_SHOT != 0, "Each desktop action token must be single-use")
        assertEquals(ModuleConstants.UCLONE_PACKAGE, shadow.savedIntent.component?.packageName)
        assertEquals(ModuleConstants.UCLONE_SERVICE, shadow.savedIntent.component?.className)
        assertEquals("uclone-action:$REQUEST_ID", shadow.savedIntent.dataString)
        assertEquals(REQUEST_ID, shadow.savedIntent.getStringExtra(ModuleRelayContract.UCLONE_EXTRA_REQUEST_ID))
        assertEquals(
            0,
            shadow.savedIntent.getIntExtra(ModuleRelayContract.UCLONE_EXTRA_TARGET_USER_ID, -1),
            "The provider must bind the accepted user0 launcher identity to the UClone request",
        )

        pendingIntent.send()
        val startedService = shadowOf(context).nextStartedService
        assertEquals(ModuleConstants.UCLONE_SERVICE, startedService.component?.className)
        assertEquals(REQUEST_ID, startedService.getStringExtra(ModuleRelayContract.UCLONE_EXTRA_REQUEST_ID))
        assertEquals(0, startedService.getIntExtra(ModuleRelayContract.UCLONE_EXTRA_TARGET_USER_ID, -1))
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.target"
        const val REQUEST_ID = "relay-request-1"
    }
}

package com.uclone.crossuser.runtime

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Display
import android.view.Surface
import com.uclone.crossuser.api.CrossUserProtocol
import com.uclone.crossuser.api.CrossUserSessionConfig
import java.util.concurrent.atomic.AtomicBoolean

internal interface CrossUserDisplayHandle {
    val displayId: Int

    fun replaceSurface(surface: Surface, widthPx: Int, heightPx: Int, densityDpi: Int)

    fun release()
}

internal fun interface CrossUserDisplayFactory {
    fun create(config: CrossUserSessionConfig, surface: Surface): CrossUserDisplayHandle
}

/** Creates the target display from the uid-0 RootService process. */
internal class AndroidCrossUserDisplayFactory(context: Context) : CrossUserDisplayFactory {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    override fun create(config: CrossUserSessionConfig, surface: Surface): CrossUserDisplayHandle {
        CrossUserSessionValidator.requireValid(config)
        require(surface.isValid) { CrossUserRuntimeErrors.SURFACE_INVALID }
        val flags = runCatching { CrossUserVirtualDisplayFlags.resolveRuntimeFlags() }
            .getOrElse { error ->
                throw FrameworkCallException(
                    errorCode = CrossUserRuntimeErrors.FRAMEWORK_SIGNATURE_MISMATCH,
                    supportStatus = CrossUserProtocol.STATUS_UNKNOWN,
                    message = "Required trusted-display flags are unavailable: ${error.message}",
                )
            }
        val display = displayManager.createVirtualDisplay(
            DISPLAY_NAME,
            config.widthPx,
            config.heightPx,
            config.densityDpi,
            surface,
            flags,
        ) ?: throw FrameworkCallException(
            errorCode = CrossUserRuntimeErrors.DISPLAY_CREATE_DENIED,
            supportStatus = CrossUserProtocol.STATUS_BLOCKED_PERMISSION,
            message = "RootService could not create a trusted virtual display",
        )
        val handle = AndroidCrossUserDisplayHandle(display)
        if (handle.displayId <= Display.DEFAULT_DISPLAY) {
            handle.release()
            throw FrameworkCallException(
                errorCode = CrossUserRuntimeErrors.DISPLAY_CREATE_DENIED,
                supportStatus = CrossUserProtocol.STATUS_UNSUPPORTED,
                message = "Virtual display returned invalid id ${handle.displayId}",
            )
        }
        return handle
    }

    private companion object {
        const val DISPLAY_NAME = "UClone CrossUser WeChat"
    }
}

private class AndroidCrossUserDisplayHandle(
    private val display: VirtualDisplay,
) : CrossUserDisplayHandle {
    private val released = AtomicBoolean(false)

    override val displayId: Int
        get() = display.display.displayId

    override fun replaceSurface(surface: Surface, widthPx: Int, heightPx: Int, densityDpi: Int) {
        check(!released.get()) { CrossUserRuntimeErrors.DISPLAY_CREATE_DENIED }
        require(surface.isValid) { CrossUserRuntimeErrors.SURFACE_INVALID }
        display.resize(widthPx, heightPx, densityDpi)
        display.surface = surface
    }

    override fun release() {
        if (released.compareAndSet(false, true)) display.release()
    }
}

internal object CrossUserVirtualDisplayFlags {
    internal val requiredHiddenFlags = listOf(
        "VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS",
        "VIRTUAL_DISPLAY_FLAG_TRUSTED",
        "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
        "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED",
        "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED",
    )

    fun resolveRuntimeFlags(): Int {
        val baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val reflected = runCatching {
            resolve(baseFlags) { name ->
                DisplayManager::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                }.getInt(null)
            }
        }
        reflected.getOrNull()?.let { return it }
        check(Build.VERSION.SDK_INT == TARGET_SDK && Build.FINGERPRINT == TARGET_FINGERPRINT) {
            "Hidden display flags unavailable on unrecognized build ${Build.FINGERPRINT}"
        }
        return resolve(baseFlags) { name -> verifiedTargetValues.getValue(name) }
    }

    internal fun resolve(baseFlags: Int, lookup: (String) -> Int): Int =
        requiredHiddenFlags.fold(baseFlags) { flags, name -> flags or lookup(name) }

    internal val verifiedTargetValues = mapOf(
        "VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS" to 0x200,
        "VIRTUAL_DISPLAY_FLAG_TRUSTED" to 0x400,
        "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP" to 0x800,
        "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED" to 0x1000,
        "VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED" to 0x2000,
    )

    private const val TARGET_SDK = 37
    private const val TARGET_FINGERPRINT =
        "Xiaomi/popsicle/popsicle:17/CP2A.260605.016/OS4.0.0.20.XPBCNXM:user/release-keys"
}

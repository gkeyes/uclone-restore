package com.uclone.restore.module.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/*
 * Cross-app design system v2 tokens for the View-based module UI (DESIGN_SYSTEM.md). Values
 * match the Compose app token for token.
 */

internal class ModulePalette(
    val isDark: Boolean,
    val page: Int,
    val card: Int,
    val raised: Int,
    val hairline: Int,
    val scrim: Int,
    val onSurface: Int,
    val accent: Int,
    val onAccent: Int,
    val success: ToneColor,
    val warning: ToneColor,
    val danger: ToneColor,
    val info: ToneColor,
    val neutral: ToneColor,
) {
    val textPrimary: Int = withAlpha(onSurface, ModuleAlpha.PRIMARY)
    val textSecondary: Int = withAlpha(onSurface, ModuleAlpha.SECONDARY)
    val textTertiary: Int = withAlpha(onSurface, ModuleAlpha.TERTIARY)

    /** Tonal secondary button / selected segment fill: accent at 12%. */
    val accentTonal: Int = withAlpha(accent, 0.12f)

    fun tone(tone: ModuleTone): ToneColor = when (tone) {
        ModuleTone.SUCCESS -> success
        ModuleTone.WARNING -> warning
        ModuleTone.DANGER -> danger
        ModuleTone.INFO -> info
        ModuleTone.NEUTRAL -> neutral
    }

    companion object {
        fun from(context: Context): ModulePalette {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            } else {
                if (dark) Color.rgb(0x5A, 0x93, 0xFF) else Color.rgb(0x34, 0x78, 0xF6)
            }
            return if (dark) {
                ModulePalette(
                    isDark = true,
                    page = Color.BLACK,
                    card = Color.rgb(0x16, 0x17, 0x1A),
                    raised = Color.rgb(0x21, 0x22, 0x26),
                    hairline = Color.argb(0x1A, 0xFF, 0xFF, 0xFF),
                    scrim = Color.argb(0x80, 0, 0, 0),
                    onSurface = Color.rgb(0xF2, 0xF3, 0xF5),
                    accent = accent,
                    onAccent = Color.BLACK,
                    success = ToneColor(Color.rgb(0x17, 0x35, 0x1F), Color.rgb(0x8F, 0xD9, 0xA8)),
                    warning = ToneColor(Color.rgb(0x3A, 0x2B, 0x10), Color.rgb(0xF5, 0xC3, 0x6B)),
                    danger = ToneColor(Color.rgb(0x3B, 0x1A, 0x1A), Color.rgb(0xF4, 0xA7, 0xA3)),
                    info = ToneColor(Color.rgb(0x17, 0x29, 0x47), Color.rgb(0xA9, 0xC5, 0xFA)),
                    neutral = ToneColor(Color.rgb(0x26, 0x28, 0x2D), Color.rgb(0xC4, 0xC8, 0xCF)),
                )
            } else {
                ModulePalette(
                    isDark = false,
                    page = Color.rgb(0xF5, 0xF6, 0xF8),
                    card = Color.WHITE,
                    raised = Color.WHITE,
                    hairline = Color.argb(0x14, 0, 0, 0),
                    scrim = Color.argb(0x52, 0, 0, 0),
                    onSurface = Color.rgb(0x11, 0x12, 0x15),
                    accent = accent,
                    onAccent = Color.WHITE,
                    success = ToneColor(Color.rgb(0xE6, 0xF4, 0xEA), Color.rgb(0x1B, 0x6B, 0x34)),
                    warning = ToneColor(Color.rgb(0xFD, 0xF1, 0xDC), Color.rgb(0x8A, 0x53, 0x00)),
                    danger = ToneColor(Color.rgb(0xFC, 0xE8, 0xE8), Color.rgb(0xB3, 0x26, 0x1E)),
                    info = ToneColor(Color.rgb(0xE5, 0xEE, 0xFD), Color.rgb(0x1A, 0x57, 0xC9)),
                    neutral = ToneColor(Color.rgb(0xEE, 0xF0, 0xF3), Color.rgb(0x4A, 0x4F, 0x57)),
                )
            }
        }
    }
}

internal data class ToneColor(val container: Int, val content: Int)

internal enum class ModuleTone { SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

internal fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

internal object ModuleAlpha {
    const val PRIMARY = 0.92f
    const val SECONDARY = 0.62f
    const val TERTIARY = 0.42f
    const val DISABLED = 0.38f
    const val STATE_LAYER = 0.06f
}

/** Spacing (4dp grid) and radii, in dp. */
internal object ModuleDimens {
    const val XXS = 4
    const val XS = 8
    const val SM = 12
    const val MD = 16
    const val LG = 24
    const val XL = 32
    const val ROW_MIN = 56
    const val TOUCH = 48
    const val PRIMARY_BUTTON = 52
    const val ROW_ICON = 36

    const val RADIUS_CARD = 24
    const val RADIUS_ROW = 16
    const val RADIUS_BUTTON = 16
    const val RADIUS_CONTROL = 14
    const val RADIUS_SMALL = 10
    const val COMPACT_BAR = 44
}

/** Type scale, in sp. */
internal object ModuleType {
    const val LARGE_TITLE = 30f
    const val COLLAPSED_TITLE = 17f
    const val TITLE = 20f
    const val HEADLINE = 17f
    const val BODY = 16f
    const val SUBHEAD = 14f
    const val SECTION_HEADER = 13f
    const val CAPTION = 12f
    const val METRIC = 20f
}

/**
 * View equivalents of the motion tokens: spatial = PathInterpolator(0.2,0,0,1) 360ms,
 * effects = 200ms, press-in 100ms, release ≈ overshoot spring 380ms, exit 220ms accelerate.
 * ViewPropertyAnimator / ValueAnimator honour the system animator scale (0 = instant).
 */
internal object ModuleMotion {
    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    val exit = PathInterpolator(0.3f, 0f, 1f, 1f)
    val release = OvershootInterpolator(1.6f)
    const val SPATIAL = 360L
    const val EFFECTS = 200L
    const val PRESS_IN = 100L
    const val PRESS_RELEASE = 380L
    const val EXIT = 220L
    const val PRESSED_SCALE = 0.965f
    const val PRESSED_SCALE_ROW = 0.985f
    const val STAGGER_STEP = 26L
    const val STAGGER_MAX = 8
    const val STAGGER_OFFSET_DP = 16
    const val PAGE_OFFSET_DP = 30
}

/** Haptic constants with fallbacks for older APIs. */
internal object ModuleHaptics {
    fun toggle(on: Boolean): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

    fun segmentTick(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        HapticFeedbackConstants.SEGMENT_TICK
    } else {
        HapticFeedbackConstants.CLOCK_TICK
    }

    fun confirm(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
}

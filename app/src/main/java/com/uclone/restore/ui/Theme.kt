package com.uclone.restore.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Cross-app design system v2 tokens (DESIGN_SYSTEM.md). Every screen reads spacing, radii,
 * type, color and motion from here; do not introduce local magic numbers.
 */

/** 4dp spacing grid. */
object UCloneSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val gutter = md
    val cardPadding = md
    val cardGap = sm
    val sectionGap = lg
    val rowMinHeight = 56.dp
    val touchTarget = 48.dp
}

/** Continuous-curvature radii. */
object UCloneRadius {
    val card = 24.dp
    val row = 16.dp
    val button = 16.dp
    val control = 14.dp
    val small = 10.dp
    val sheet = 28.dp
}

/** Text alpha levels applied on onSurface. */
object UCloneAlpha {
    const val primary = 0.92f
    const val secondary = 0.62f
    const val tertiary = 0.42f
    const val disabled = 0.38f
    const val pressOverlay = 0.06f
}

internal val UCloneGroupedCornerRadius = UCloneRadius.card
private val FallbackAccentLight = Color(0xFF3478F6)
private val FallbackAccentDark = Color(0xFF5A93FF)

@Immutable
data class ToneColors(val container: Color, val content: Color)

enum class StatusTone { SUCCESS, WARNING, DANGER, INFO, NEUTRAL }

@Immutable
data class UCloneSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val switchOn: Color,
    val actionPrimary: Color,
    val onActionPrimary: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val neutral: Color,
    val groupedSurface: Color,
    val elevatedSurface: Color,
    val separator: Color,
    val navigationSurface: Color,
    val glassHighlight: Color,
    val page: Color,
    val raised: Color,
    val scrim: Color,
    val successTone: ToneColors,
    val warningTone: ToneColors,
    val dangerTone: ToneColors,
    val infoTone: ToneColors,
    val neutralTone: ToneColors,
) {
    fun tone(tone: StatusTone): ToneColors = when (tone) {
        StatusTone.SUCCESS -> successTone
        StatusTone.WARNING -> warningTone
        StatusTone.DANGER -> dangerTone
        StatusTone.INFO -> infoTone
        StatusTone.NEUTRAL -> neutralTone
    }
}

private fun lightSemanticColors(accent: Color) = UCloneSemanticColors(
    success = Color(0xFF1B6B34),
    onSuccess = Color.White,
    successContainer = Color(0xFFE6F4EA),
    switchOn = accent,
    actionPrimary = accent,
    onActionPrimary = Color.White,
    warning = Color(0xFF8A5300),
    onWarning = Color.White,
    warningContainer = Color(0xFFFDF1DC),
    neutral = Color(0xFF4A4F57),
    groupedSurface = Color.White,
    elevatedSurface = Color(0xFFEEF0F3),
    separator = Color(0x14000000),
    navigationSurface = Color.White,
    glassHighlight = Color(0xD9FFFFFF),
    page = Color(0xFFF5F6F8),
    raised = Color.White,
    scrim = Color(0x52000000),
    successTone = ToneColors(Color(0xFFE6F4EA), Color(0xFF1B6B34)),
    warningTone = ToneColors(Color(0xFFFDF1DC), Color(0xFF8A5300)),
    dangerTone = ToneColors(Color(0xFFFCE8E8), Color(0xFFB3261E)),
    infoTone = ToneColors(Color(0xFFE5EEFD), Color(0xFF1A57C9)),
    neutralTone = ToneColors(Color(0xFFEEF0F3), Color(0xFF4A4F57)),
)

private fun darkSemanticColors(accent: Color) = UCloneSemanticColors(
    success = Color(0xFF8FD9A8),
    onSuccess = Color.Black,
    successContainer = Color(0xFF17351F),
    switchOn = accent,
    actionPrimary = accent,
    onActionPrimary = Color.Black,
    warning = Color(0xFFF5C36B),
    onWarning = Color.Black,
    warningContainer = Color(0xFF3A2B10),
    neutral = Color(0xFFC4C8CF),
    groupedSurface = Color(0xFF16171A),
    elevatedSurface = Color(0xFF26282D),
    separator = Color(0x1AFFFFFF),
    navigationSurface = Color(0xFF212226),
    glassHighlight = Color(0x33FFFFFF),
    page = Color.Black,
    raised = Color(0xFF212226),
    scrim = Color(0x80000000),
    successTone = ToneColors(Color(0xFF17351F), Color(0xFF8FD9A8)),
    warningTone = ToneColors(Color(0xFF3A2B10), Color(0xFFF5C36B)),
    dangerTone = ToneColors(Color(0xFF3B1A1A), Color(0xFFF4A7A3)),
    infoTone = ToneColors(Color(0xFF172947), Color(0xFFA9C5FA)),
    neutralTone = ToneColors(Color(0xFF26282D), Color(0xFFC4C8CF)),
)

private val LocalUCloneSemanticColors = staticCompositionLocalOf { lightSemanticColors(FallbackAccentLight) }

val MaterialTheme.ucloneColors: UCloneSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalUCloneSemanticColors.current

private fun colorScheme(dark: Boolean, accent: Color, semantic: UCloneSemanticColors): ColorScheme {
    val onSurface = if (dark) Color(0xFFF2F3F5) else Color(0xFF111215)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = semantic.onActionPrimary,
        primaryContainer = accent.copy(alpha = 0.12f).compositeOver(semantic.groupedSurface),
        onPrimaryContainer = accent,
        secondary = accent,
        onSecondary = semantic.onActionPrimary,
        secondaryContainer = accent.copy(alpha = 0.12f).compositeOver(semantic.groupedSurface),
        onSecondaryContainer = accent,
        tertiary = semantic.warning,
        background = semantic.page,
        onBackground = onSurface.copy(alpha = UCloneAlpha.primary).compositeOver(semantic.page),
        surface = semantic.page,
        onSurface = onSurface.copy(alpha = UCloneAlpha.primary).compositeOver(semantic.page),
        surfaceVariant = semantic.elevatedSurface,
        onSurfaceVariant = onSurface.copy(alpha = UCloneAlpha.secondary).compositeOver(semantic.groupedSurface),
        surfaceContainerLowest = semantic.groupedSurface,
        surfaceContainerLow = semantic.groupedSurface,
        surfaceContainer = semantic.groupedSurface,
        surfaceContainerHigh = semantic.raised,
        surfaceContainerHighest = semantic.elevatedSurface,
        outline = onSurface.copy(alpha = UCloneAlpha.tertiary).compositeOver(semantic.groupedSurface),
        outlineVariant = semantic.separator.compositeOver(semantic.groupedSurface),
        scrim = semantic.scrim,
        error = semantic.dangerTone.content,
        onError = if (dark) Color.Black else Color.White,
        errorContainer = semantic.dangerTone.container,
        onErrorContainer = semantic.dangerTone.content,
    )
}

/** Tabular figures so counts, sizes and times never jitter. */
private const val Tabular = "tnum"

private fun style(size: Int, line: Int, weight: FontWeight) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = Tabular,
)

/*
 * Role mapping: headlineMedium = largeTitle 30/Bold, titleLarge = title 20/SemiBold,
 * titleMedium = headline 17/SemiBold, bodyLarge = body 16, bodyMedium = subhead 14,
 * labelMedium = sectionHeader 13/Medium, bodySmall = caption 12.
 */
private val UCloneTypography = Typography(
    headlineLarge = style(30, 36, FontWeight.Bold),
    headlineMedium = style(30, 36, FontWeight.Bold),
    headlineSmall = style(20, 26, FontWeight.SemiBold),
    titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(17, 22, FontWeight.SemiBold),
    titleSmall = style(14, 19, FontWeight.Medium),
    bodyLarge = style(16, 22, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal),
    labelLarge = style(16, 21, FontWeight.SemiBold),
    labelMedium = style(13, 17, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)

/** Collapsed large title: 17 / SemiBold. */
internal val CollapsedTitleStyle = style(17, 22, FontWeight.SemiBold)

private val UCloneShapes = Shapes(
    extraSmall = RoundedCornerShape(UCloneRadius.small),
    small = RoundedCornerShape(UCloneRadius.control),
    medium = RoundedCornerShape(UCloneRadius.card),
    large = RoundedCornerShape(UCloneRadius.card),
    extraLarge = RoundedCornerShape(UCloneRadius.sheet),
)

@Composable
fun UCloneTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val accent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
    } else {
        if (darkTheme) FallbackAccentDark else FallbackAccentLight
    }
    val semantic = if (darkTheme) darkSemanticColors(accent) else lightSemanticColors(accent)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalUCloneSemanticColors provides semantic,
    ) {
        MaterialTheme(
            colorScheme = colorScheme(darkTheme, accent, semantic),
            typography = UCloneTypography,
            shapes = UCloneShapes,
        ) {
            ReduceMotionProvider(content)
        }
    }
}

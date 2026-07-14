package com.uclone.restore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SystemBlue = Color(0xFF007AFF)
private val SystemBlueDark = Color(0xFF0A84FF)
internal val UCloneGroupedCornerRadius = 11.dp

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
)

private val LightSemanticColors = UCloneSemanticColors(
    success = Color(0xFF1F7A35),
    onSuccess = Color.White,
    successContainer = Color(0xFFE8F8ED),
    switchOn = Color(0xFF34C759),
    actionPrimary = Color(0xFF0066CC),
    onActionPrimary = Color.White,
    warning = Color(0xFFA15C00),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFF3E0),
    neutral = Color(0xFF8E8E93),
    groupedSurface = Color.White,
    elevatedSurface = Color(0xFFFAFAFC),
    separator = Color(0xFFE0E0E0),
    navigationSurface = Color(0xFFF9F9FB),
    glassHighlight = Color(0xD9FFFFFF),
)

private val DarkSemanticColors = UCloneSemanticColors(
    success = Color(0xFF30D158),
    onSuccess = Color.Black,
    successContainer = Color(0xFF173B21),
    switchOn = Color(0xFF30D158),
    actionPrimary = Color(0xFF0066CC),
    onActionPrimary = Color.White,
    warning = Color(0xFFFF9F0A),
    onWarning = Color.Black,
    warningContainer = Color(0xFF493116),
    neutral = Color(0xFF98989D),
    groupedSurface = Color(0xFF1C1C1E),
    elevatedSurface = Color(0xFF2A2A2C),
    separator = Color(0xFF38383A),
    navigationSurface = Color(0xFF242426),
    glassHighlight = Color(0x33FFFFFF),
)

private val LocalUCloneSemanticColors = staticCompositionLocalOf { LightSemanticColors }

val MaterialTheme.ucloneColors: UCloneSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalUCloneSemanticColors.current

private val LightColors: ColorScheme = lightColorScheme(
    primary = SystemBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2F0FF),
    onPrimaryContainer = Color(0xFF004A99),
    secondary = Color(0xFF5856D6),
    onSecondary = Color.White,
    tertiary = Color(0xFFFF9500),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFF5F5F7),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFE8E8ED),
    onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0xFF86868B),
    outlineVariant = Color(0xFFD2D2D7),
    error = Color(0xFFFF3B30),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFFB4231B),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = SystemBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF153A5F),
    onPrimaryContainer = Color(0xFFB9DBFF),
    secondary = Color(0xFF5E5CE6),
    onSecondary = Color.White,
    tertiary = Color(0xFFFF9F0A),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989D),
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF38383A),
    error = Color(0xFFFF453A),
    onError = Color.White,
    errorContainer = Color(0xFF4B1F1C),
    onErrorContainer = Color(0xFFFFB4AF),
)

private val UCloneTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
)

private val UCloneShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(UCloneGroupedCornerRadius),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(18.dp),
)

@Composable
fun UCloneTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalUCloneSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = UCloneTypography,
            shapes = UCloneShapes,
        ) {
            ReduceMotionProvider(content)
        }
    }
}

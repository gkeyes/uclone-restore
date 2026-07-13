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

@Immutable
data class UCloneSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val switchOn: Color,
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
    warning = Color(0xFFA15C00),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFF3E0),
    neutral = Color(0xFF8E8E93),
    groupedSurface = Color.White,
    elevatedSurface = Color(0xFFF7F7FA),
    separator = Color(0xFFC6C6C8),
    navigationSurface = Color(0xFFF9F9FB),
    glassHighlight = Color(0xD9FFFFFF),
)

private val DarkSemanticColors = UCloneSemanticColors(
    success = Color(0xFF30D158),
    onSuccess = Color.Black,
    successContainer = Color(0xFF173B21),
    switchOn = Color(0xFF30D158),
    warning = Color(0xFFFF9F0A),
    onWarning = Color.Black,
    warningContainer = Color(0xFF493116),
    neutral = Color(0xFF98989D),
    groupedSurface = Color(0xFF1C1C1E),
    elevatedSurface = Color(0xFF2C2C2E),
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
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6C6C70),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFC6C6C8),
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
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
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
        fontSize = 15.sp,
        lineHeight = 20.sp,
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
        fontSize = 13.sp,
        lineHeight = 18.sp,
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
        fontSize = 13.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    ),
)

private val UCloneShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
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

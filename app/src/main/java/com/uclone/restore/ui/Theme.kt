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

private val BrandBlue = Color(0xFF1769E0)
private val BrandBlueDark = Color(0xFFA9C7FF)

@Immutable
data class UCloneSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val neutral: Color,
)

private val LightSemanticColors = UCloneSemanticColors(
    success = Color(0xFF146C2E),
    onSuccess = Color.White,
    successContainer = Color(0xFFC7F1D0),
    warning = Color(0xFF8B5000),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDDB4),
    neutral = Color(0xFF5F6368),
)

private val DarkSemanticColors = UCloneSemanticColors(
    success = Color(0xFF87D993),
    onSuccess = Color(0xFF003913),
    successContainer = Color(0xFF0B5223),
    warning = Color(0xFFFFB95F),
    onWarning = Color(0xFF4A2800),
    warningContainer = Color(0xFF663B00),
    neutral = Color(0xFFC4C7C5),
)

private val LocalUCloneSemanticColors = staticCompositionLocalOf { LightSemanticColors }

val MaterialTheme.ucloneColors: UCloneSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalUCloneSemanticColors.current

private val LightColors: ColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E7FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF4F616F),
    onSecondary = Color.White,
    tertiary = Color(0xFF715573),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E3E8),
    onSurfaceVariant = Color(0xFF44474E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF00478B),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = Color(0xFFB7C9D7),
    onSecondary = Color(0xFF22323C),
    tertiary = Color(0xFFDFBCDF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val UCloneTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
)

private val UCloneShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
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
            content = content,
        )
    }
}

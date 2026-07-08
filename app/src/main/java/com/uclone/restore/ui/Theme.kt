package com.uclone.restore.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val IosPage = Color(0xFFF5F5F7)
val IosGroup = Color(0xFFFFFFFF)
val IosRaised = Color(0xFFFAFAFC)
val IosText = Color(0xFF1D1D1F)
val IosSecondaryText = Color(0xFF6E6E73)
val IosTertiaryText = Color(0xFF8E8E93)
val IosSeparator = Color(0xFFD2D2D7)
val IosBlue = Color(0xFF007AFF)
val IosBluePressed = Color(0xFF0066CC)
val IosGreen = Color(0xFF34C759)
val IosOrange = Color(0xFFFF9500)
val IosRed = Color(0xFFFF3B30)

private val UCloneColors: ColorScheme = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    secondary = IosSecondaryText,
    tertiary = IosOrange,
    background = IosPage,
    onBackground = IosText,
    surface = IosGroup,
    onSurface = IosText,
    surfaceVariant = IosRaised,
    onSurfaceVariant = IosSecondaryText,
    outline = IosSeparator,
    error = IosRed,
)

private val UCloneTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
)

@Composable
fun UCloneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UCloneColors,
        typography = UCloneTypography,
        content = content,
    )
}

package com.uclone.restore.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UCloneColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F766E),
    secondary = Color(0xFF475569),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE2E8F0),
    error = Color(0xFFB91C1C),
)

@Composable
fun UCloneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = UCloneColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

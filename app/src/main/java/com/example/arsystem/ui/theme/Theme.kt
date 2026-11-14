package com.example.arsystem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ObsidianScheme = darkColorScheme(
    primary = SignalPrimary,
    onPrimary = Color(0xFF06201D),
    secondary = SignalSecondary,
    onSecondary = Color(0xFF07111F),
    tertiary = SignalSuccess,
    background = ObsidianBackground,
    onBackground = Color.White,
    surface = ObsidianSurface,
    onSurface = Color.White,
    surfaceVariant = ObsidianElevated,
    outline = ObsidianOutline,
    error = SignalDanger
)

@Composable
fun ArsystemTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ObsidianScheme,
        typography = Typography,
        content = content
    )
}

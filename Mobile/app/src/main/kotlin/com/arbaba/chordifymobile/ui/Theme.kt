package com.arbaba.chordifymobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The web app's colour tokens (chordify-frontend/src/index.css), light and dark, following the
// system setting. The harmonic-function hues come back with the analysis screen (M5).

private val Light = lightColorScheme(
    primary = Color(0xFF2A78D6), onPrimary = Color.White, // --tonic
    background = Color(0xFFF9F9F7), onBackground = Color(0xFF0B0B0B), // --page, --ink
    surface = Color(0xFFFCFCFB), onSurface = Color(0xFF0B0B0B), // --surface
    surfaceVariant = Color(0xFFF0EFEC), onSurfaceVariant = Color(0xFF52514E), // --surface-2, --ink-2
    surfaceContainer = Color(0xFFF0EFEC),
    outline = Color(0xFFC3C2B7), outlineVariant = Color(0xFFE1E0D9), // --axis, --grid
    error = Color(0xFFB3261E), onError = Color.White, // --warn
)

private val Dark = darkColorScheme(
    primary = Color(0xFF3987E5), onPrimary = Color.White,
    background = Color(0xFF0D0D0D), onBackground = Color.White,
    surface = Color(0xFF1A1A19), onSurface = Color.White,
    surfaceVariant = Color(0xFF242423), onSurfaceVariant = Color(0xFFC3C2B7),
    surfaceContainer = Color(0xFF242423),
    outline = Color(0xFF383835), outlineVariant = Color(0xFF2C2C2A),
    error = Color(0xFFF2B8B5), onError = Color(0xFF0B0B0B),
)

@Composable
fun ChordifyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

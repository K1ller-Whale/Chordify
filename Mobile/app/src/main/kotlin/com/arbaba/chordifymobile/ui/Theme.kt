package com.arbaba.chordifymobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.arbaba.chordifymobile.music.FunctionHue

// The web app's colour tokens (chordify-frontend/src/index.css), light and dark, following the
// system setting.

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

/** The web's tokens Material 3 has no slot for: harmonic functions, muted text, the grid, and
 *  [tint], how much of its function colour a chord that is not playing shows (--tint). */
data class ChordifyColors(val tonic: Color, val subdominant: Color, val dominant: Color, val borrowed: Color,
                          val muted: Color, val grid: Color, val tint: Float) {
    fun of(hue: FunctionHue): Color = when (hue) {
        FunctionHue.TONIC -> tonic
        FunctionHue.SUBDOMINANT -> subdominant
        FunctionHue.DOMINANT -> dominant
        FunctionHue.BORROWED -> borrowed
        FunctionHue.NONE -> grid
    }
}

private val LightTokens = ChordifyColors(Color(0xFF2A78D6), Color(0xFF1BAF7A), Color(0xFFEB6834), Color(0xFF898781),
                                         Color(0xFF898781), Color(0xFFE1E0D9), tint = 0.22f)
private val DarkTokens = ChordifyColors(Color(0xFF3987E5), Color(0xFF199E70), Color(0xFFD95926), Color(0xFF898781),
                                        Color(0xFF898781), Color(0xFF2C2C2A), tint = 0.30f)

val LocalChordifyColors = staticCompositionLocalOf { LightTokens }

@Composable
fun ChordifyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChordifyColors provides if (dark) DarkTokens else LightTokens) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}

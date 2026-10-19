package com.mbzmhs.anidocktv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 与 anidock web 前端完全一致的配色(App.vue :root)。 */
object Anidock {
    val Bg = Color(0xFF0B0E14)
    val BgSoft = Color(0xFF10141C)
    val Card = Color(0xFF141926)
    val CardHover = Color(0xFF1A2133)
    val Border = Color(0xFF232B3D)
    val BorderSoft = Color(0xFF1C2333)
    val Text = Color(0xFFE6EBF4)
    val Text2 = Color(0xFF9AA5BB)
    val Text3 = Color(0xFF5F6B82)
    val Accent = Color(0xFF5B8CFF)
    val Accent2 = Color(0xFF8B5CF6)
    val Ok = Color(0xFF34D399)
    val Err = Color(0xFFF87171)
}

val AnidockDark = darkColorScheme(
    primary = Anidock.Accent,
    onPrimary = Color.White,
    secondary = Anidock.Accent2,
    onSecondary = Color.White,
    background = Anidock.Bg,
    onBackground = Anidock.Text,
    surface = Anidock.Card,
    onSurface = Anidock.Text,
    surfaceVariant = Anidock.Card,
    onSurfaceVariant = Anidock.Text2,
    outline = Anidock.Border,
    error = Anidock.Err,
)

@Composable
fun AnidockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AnidockDark, content = content)
}

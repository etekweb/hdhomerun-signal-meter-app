package net.etekweb.hdhrmeter.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0E1114)
val InkRaised = Color(0xFF171C21)
val Amber = Color(0xFFF5A524)
val Good = Color(0xFF3DDC97)
val Bad = Color(0xFFFF5D5D)
val Paper = Color(0xFFE7EDF2)
val Muted = Color(0xFF9AA6B2)

private val Scheme = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    secondary = Good,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = InkRaised,
    onSurface = Paper,
    surfaceVariant = Color(0xFF222A31),
    onSurfaceVariant = Muted,
    error = Bad,
    onError = Ink,
    outline = Color(0xFF3A4550),
)

@Composable
fun HdhrTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

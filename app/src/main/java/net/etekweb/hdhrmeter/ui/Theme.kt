package net.etekweb.hdhrmeter.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val Ink = Color(0xFF0E1114)
val InkRaised = Color(0xFF171C21)
val Amber = Color(0xFFF5A524)
val Good = Color(0xFF3DDC97)
val Bad = Color(0xFFFF5D5D)
val Paper = Color(0xFFE7EDF2)
val Muted = Color(0xFF9AA6B2)

private val TvFocusShape = RoundedCornerShape(8.dp)

/** Keep D-pad focus visible independently of pressed and selected states. */
@Composable
internal fun Modifier.tvFocusBorder(shape: Shape = TvFocusShape): Modifier {
    if (!isTelevision()) return this
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }.tvFocusBorder(focused, shape)
}

internal fun Modifier.tvFocusBorder(highlight: Boolean, shape: Shape = TvFocusShape): Modifier {
    return if (highlight) border(3.dp, Color.White, shape) else this
}

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

package app.fluide.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF3D7BFF)

private val Colors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Color(0xFF7FA6FF),
    background = Color(0xFF05070D),
    onBackground = Color(0xFFE8ECF5),
    surface = Color(0xFF0E1322),
    onSurface = Color(0xFFE8ECF5),
    surfaceVariant = Color(0xFF1A2135),
    onSurfaceVariant = Color(0xFFB7C0D6),
    error = Color(0xFFFF6B6B),
)

/** Dark only: the app is drawn over video, often on a TV in a dim room. */
@Composable
fun FluideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

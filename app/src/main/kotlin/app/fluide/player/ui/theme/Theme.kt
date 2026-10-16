package app.fluide.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Electric blue of the brand (same value as FluideColors.Blue). */
val Brand = Color(0xFF3D7BFF)

private val OnDark = Color(0xFFE8ECF7)

private val Colors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C3A8F),
    onPrimaryContainer = Color(0xFFDDE6FF),
    inversePrimary = Color(0xFF2350C8),
    secondary = Color(0xFF22D3EE),
    onSecondary = Color(0xFF00313A),
    secondaryContainer = Color(0xFF0D4D5A),
    onSecondaryContainer = Color(0xFFCDF6FD),
    tertiary = Color(0xFF7B3DFF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF33207A),
    onTertiaryContainer = Color(0xFFE6DCFF),
    background = Color(0xFF070B1C),
    onBackground = OnDark,
    surface = Color(0xFF0F1530),
    onSurface = OnDark,
    surfaceVariant = Color(0xFF19213F),
    onSurfaceVariant = Color(0xFFAEB7D3),
    surfaceTint = Brand,
    inverseSurface = OnDark,
    inverseOnSurface = Color(0xFF0F1530),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5C1A1E),
    onErrorContainer = Color(0xFFFFDAD8),
    outline = Color(0xFF3A4670),
    outlineVariant = Color(0xFF242D52),
    scrim = Color.Black,
    surfaceBright = Color(0xFF232C52),
    surfaceDim = Color(0xFF070B1C),
    surfaceContainerLowest = Color(0xFF060918),
    surfaceContainerLow = Color(0xFF0C1228),
    surfaceContainer = Color(0xFF111833),
    surfaceContainerHigh = Color(0xFF161E3C),
    surfaceContainerHighest = Color(0xFF1C2546),
)

private val Base = Typography()

/** Material type scale with bolder titles (read from a sofa, on a TV). */
private val Type = Typography(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.25).sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    bodySmall = Base.bodySmall,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.Medium),
)

/** Rounder shapes: 12 dp rows, 16 dp cards, 24 dp pills and sheets. */
private val Corners = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Dark only: the app is drawn over video, often on a TV in a dim room. */
@Composable
fun FluideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = Corners) {
        // Text and icons without an explicit color are light on the dark screens (default is black).
        CompositionLocalProvider(LocalContentColor provides OnDark, content = content)
    }
}

package app.fluide.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluide.player.R
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/*
 * Colour themes of Fluide Player. Each palette defines every brand colour (read through
 * app.fluide.player.ui.components.FluideColors, whose members are getters on the active palette), the
 * Material colour scheme and the background of the screens. FluideTheme selects the palette from the
 * settings and recomposes the whole interface when it changes.
 */

/**
 * One colour theme. Brand roles keep the names of the original palette: [navy] / [navyLight] / [night]
 * are the background stops, [blue] the accent (start of the gradient), [cyan] the second accent (end of
 * the gradient), [violet] the third accent and [warm] the live / alert colour.
 */
class FluidePalette(
    /** Id stored in the settings, one of SettingsValues.THEMES. */
    val id: String,
    /** Deepest background colour. */
    val navy: Color,
    /** Middle stop of the screen gradient. */
    val navyLight: Color,
    /** Last stop of the screen gradient. */
    val night: Color,
    /** Accent: start of the gradient, primary colour. */
    val blue: Color,
    /** Between [blue] and [cyan] (upper part of the mark). */
    val azure: Color,
    /** Second accent: end of the gradient, focus borders. */
    val cyan: Color,
    /** Light highlight of the second accent (lower part of the mark). */
    val aqua: Color,
    /** Third accent (series, glows, rings). */
    val violet: Color,
    /** Live badges, recording, alerts. */
    val warm: Color,
    /** Text and icons drawn over the accent gradient (white, or dark on the light accents). */
    val onAccent: Color,
    /** Default text colour of the dark screens. */
    val text: Color,
    /** Main text, slightly tinted. */
    val soft: Color,
    /** Secondary text (meta, labels). */
    val muted: Color,
    /** Tertiary text (hints, past items). */
    val dim: Color,
    /** Material surface (cards, sheets). */
    val surface: Color,
    /** Material outline. */
    val outline: Color,
    /** Opaque raised surface (focused text field...). */
    val raised: Color,
    /** Translucent panel drawn over the video. */
    val panel: Color,
    /** Top and bottom of the dialogs gradient. */
    val dialogTop: Color,
    val dialogBottom: Color,
    /** Radial glow at the top left of the screens. */
    val glowStart: Color,
    /** Radial glow at the bottom right of the screens. */
    val glowEnd: Color,
    /** Material colour scheme; derived from the colours above when null. */
    scheme: ColorScheme? = null,
) {
    /** Glass panel fill: white at 7 %. */
    val glass: Color = Color(0x12FFFFFF)

    /** Glass panel border: white at 10 %. */
    val glassBorder: Color = Color(0x1AFFFFFF)

    /** The "fluid" gradient: accent to second accent. */
    val gradient: Brush = Brush.linearGradient(listOf(blue, cyan))

    /** Diagonal background gradient of the full screens. */
    val screenBrush: Brush = Brush.linearGradient(listOf(navy, navyLight, night))

    /** Material colour scheme of the palette. */
    val colorScheme: ColorScheme = scheme ?: derivedScheme(
        accent = blue,
        second = cyan,
        third = violet,
        onAccent = onAccent,
        background = navy,
        surface = surface,
        outline = outline,
        text = text,
        muted = muted,
    )
}

/** sRGB mix of two colours, alpha included ([fraction] 0 gives [from], 1 gives [to]). */
fun blend(from: Color, to: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t,
    )
}

/** The five colour themes and the active one. */
object FluideThemes {
    /** Electric blue to cyan on deep navy: the original look. */
    val Fluide = FluidePalette(
        id = "fluide",
        navy = Color(0xFF070B1C),
        navyLight = Color(0xFF0A1330),
        night = Color(0xFF120A26),
        blue = Color(0xFF3D7BFF),
        azure = Color(0xFF2BB0F7),
        cyan = Color(0xFF22D3EE),
        aqua = Color(0xFF86F4FF),
        violet = Color(0xFF7B3DFF),
        warm = Color(0xFFFF6A3D),
        onAccent = Color.White,
        text = Color(0xFFE8ECF7),
        soft = Color(0xFFDCE3F2),
        muted = Color(0xFFAEB7D3),
        dim = Color(0xFF7D87A6),
        surface = Color(0xFF0F1530),
        outline = Color(0xFF3A4670),
        raised = Color(0xFF111A3D),
        panel = Color(0xE80A1028),
        dialogTop = Color(0xFF161D40),
        dialogBottom = Color(0xFF0D1230),
        glowStart = Color(0xFF3D7BFF).copy(alpha = 0.14f),
        glowEnd = Color(0xFF7B3DFF).copy(alpha = 0.12f),
        scheme = darkColorScheme(
            primary = Color(0xFF3D7BFF),
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
            onBackground = Color(0xFFE8ECF7),
            surface = Color(0xFF0F1530),
            onSurface = Color(0xFFE8ECF7),
            surfaceVariant = Color(0xFF19213F),
            onSurfaceVariant = Color(0xFFAEB7D3),
            surfaceTint = Color(0xFF3D7BFF),
            inverseSurface = Color(0xFFE8ECF7),
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
        ),
    )

    /** Deep crimson to red on near black, a dark red glow and amber for live / alerts. */
    val Rubis = FluidePalette(
        id = "rubis",
        navy = Color(0xFF0A0506),
        navyLight = Color(0xFF13070A),
        night = Color(0xFF1E080D),
        blue = Color(0xFFC8102E),
        azure = Color(0xFFE2223C),
        cyan = Color(0xFFFF3B4E),
        aqua = Color(0xFFFF97A0),
        violet = Color(0xFFFF5C8A),
        warm = Color(0xFFFFB020),
        onAccent = Color.White,
        text = Color(0xFFF7E9EB),
        soft = Color(0xFFF0DCDF),
        muted = Color(0xFFCDB2B6),
        dim = Color(0xFF9A8084),
        surface = Color(0xFF180A0D),
        outline = Color(0xFF5E2B34),
        raised = Color(0xFF230B10),
        panel = Color(0xE8140709),
        dialogTop = Color(0xFF260C12),
        dialogBottom = Color(0xFF14070A),
        glowStart = Color(0xFF2A0A10),
        glowEnd = Color(0xFFC8102E).copy(alpha = 0.10f),
    )

    /** Emerald to mint on a very dark green. */
    val Emeraude = FluidePalette(
        id = "emeraude",
        navy = Color(0xFF03110C),
        navyLight = Color(0xFF041A12),
        night = Color(0xFF061614),
        blue = Color(0xFF10B981),
        azure = Color(0xFF1FC48A),
        cyan = Color(0xFF34D399),
        aqua = Color(0xFFA7F3D0),
        violet = Color(0xFF2DD4BF),
        warm = Color(0xFFFF6A3D),
        onAccent = Color(0xFF022C1E),
        text = Color(0xFFE6F6EF),
        soft = Color(0xFFD5EEE3),
        muted = Color(0xFFA6C9BB),
        dim = Color(0xFF77998C),
        surface = Color(0xFF081D15),
        outline = Color(0xFF2C5A48),
        raised = Color(0xFF0B2A1E),
        panel = Color(0xE8061A12),
        dialogTop = Color(0xFF0E2A1F),
        dialogBottom = Color(0xFF071910),
        glowStart = Color(0xFF10B981).copy(alpha = 0.13f),
        glowEnd = Color(0xFF2DD4BF).copy(alpha = 0.10f),
    )

    /** Amber to gold on a very dark brown, red for live / alerts. */
    val Ambre = FluidePalette(
        id = "ambre",
        navy = Color(0xFF120B03),
        navyLight = Color(0xFF1A1105),
        night = Color(0xFF210D07),
        blue = Color(0xFFF59E0B),
        azure = Color(0xFFF8AF18),
        cyan = Color(0xFFFBBF24),
        aqua = Color(0xFFFDE68A),
        violet = Color(0xFFFB7185),
        warm = Color(0xFFFF5247),
        onAccent = Color(0xFF2A1800),
        text = Color(0xFFF8EEE0),
        soft = Color(0xFFF2E4CE),
        muted = Color(0xFFD2BE9F),
        dim = Color(0xFFA08D70),
        surface = Color(0xFF1F1408),
        outline = Color(0xFF5E4828),
        raised = Color(0xFF2A1C0A),
        panel = Color(0xE8180F05),
        dialogTop = Color(0xFF2B1D0B),
        dialogBottom = Color(0xFF170F04),
        glowStart = Color(0xFFF59E0B).copy(alpha = 0.12f),
        glowEnd = Color(0xFFFB7185).copy(alpha = 0.10f),
    )

    /** Violet to lilac on a very dark purple, pink as third accent. */
    val Amethyste = FluidePalette(
        id = "amethyste",
        navy = Color(0xFF0D0718),
        navyLight = Color(0xFF140A26),
        night = Color(0xFF1C0A2E),
        blue = Color(0xFF8B5CF6),
        azure = Color(0xFFA16CF9),
        cyan = Color(0xFFC084FC),
        aqua = Color(0xFFE9D5FF),
        violet = Color(0xFFF472B6),
        warm = Color(0xFFFF6A3D),
        onAccent = Color.White,
        text = Color(0xFFEFE9F8),
        soft = Color(0xFFE2D9F2),
        muted = Color(0xFFBDB0D8),
        dim = Color(0xFF8B7FA8),
        surface = Color(0xFF160D2B),
        outline = Color(0xFF4A3B73),
        raised = Color(0xFF1D1240),
        panel = Color(0xE8120A24),
        dialogTop = Color(0xFF201543),
        dialogBottom = Color(0xFF120A28),
        glowStart = Color(0xFF8B5CF6).copy(alpha = 0.14f),
        glowEnd = Color(0xFFF472B6).copy(alpha = 0.12f),
    )

    /** Every palette, in the order of SettingsValues.THEMES. */
    val all: List<FluidePalette> = listOf(Fluide, Rubis, Emeraude, Ambre, Amethyste)

    /** The palette of [id] (the original one for an unknown id). */
    fun byId(id: String): FluidePalette = all.firstOrNull { it.id == id } ?: Fluide

    /**
     * The palette in use, read by the FluideColors getters. Set by [FluideTheme] before its content is
     * composed; FluideTheme recreates the whole content when it changes.
     */
    var active: FluidePalette = Fluide
        internal set
}

/** The active palette, for code that prefers a composition local to the FluideColors getters. */
val LocalFluidePalette = staticCompositionLocalOf { FluideThemes.Fluide }

/** True inside a FluideTheme: a nested one (preview of another theme) leaves the app palette alone. */
private val LocalInsideFluideTheme = staticCompositionLocalOf { false }

/** Accent of the active theme (same value as FluideColors.Blue). */
val Brand: Color get() = FluideThemes.active.blue

/**
 * A value built from the active palette and rebuilt only when the palette changes, for brushes and
 * colours declared once at the top of a file: `private val Fill: Brush by themed { Brush.linearGradient(...) }`.
 */
fun <T> themed(build: (FluidePalette) -> T): ReadOnlyProperty<Any?, T> = ThemedValue(build)

private class ThemedValue<T>(private val build: (FluidePalette) -> T) : ReadOnlyProperty<Any?, T> {
    private var palette: FluidePalette? = null
    private var value: T? = null

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val current = FluideThemes.active
        if (current !== palette) {
            value = build(current)
            palette = current
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

/** Name of the theme [id] to show in the settings (string resource). */
fun themeName(id: String): Int = when (id) {
    "rubis" -> R.string.theme_rubis
    "emeraude" -> R.string.theme_emeraude
    "ambre" -> R.string.theme_ambre
    "amethyste" -> R.string.theme_amethyste
    else -> R.string.theme_fluide
}

/** Material colour scheme derived from the colours of a palette. */
private fun derivedScheme(
    accent: Color,
    second: Color,
    third: Color,
    onAccent: Color,
    background: Color,
    surface: Color,
    outline: Color,
    text: Color,
    muted: Color,
): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = blend(background, accent, 0.42f),
    onPrimaryContainer = blend(accent, Color.White, 0.82f),
    inversePrimary = blend(accent, Color.Black, 0.2f),
    secondary = second,
    onSecondary = blend(second, Color.Black, 0.8f),
    secondaryContainer = blend(background, second, 0.32f),
    onSecondaryContainer = blend(second, Color.White, 0.8f),
    tertiary = third,
    onTertiary = blend(third, Color.Black, 0.8f),
    tertiaryContainer = blend(background, third, 0.36f),
    onTertiaryContainer = blend(third, Color.White, 0.82f),
    background = background,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = blend(surface, outline, 0.25f),
    onSurfaceVariant = muted,
    surfaceTint = accent,
    inverseSurface = text,
    inverseOnSurface = surface,
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF5C1A1E),
    onErrorContainer = Color(0xFFFFDAD8),
    outline = outline,
    outlineVariant = blend(surface, outline, 0.45f),
    scrim = Color.Black,
    surfaceBright = blend(surface, outline, 0.4f),
    surfaceDim = background,
    surfaceContainerLowest = blend(background, Color.Black, 0.2f),
    surfaceContainerLow = blend(background, surface, 0.6f),
    surfaceContainer = blend(surface, outline, 0.06f),
    surfaceContainerHigh = blend(surface, outline, 0.15f),
    surfaceContainerHighest = blend(surface, outline, 0.27f),
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

/**
 * Dark only: the app is drawn over video, often on a TV in a dim room. [theme] is a palette id (one of
 * SettingsValues.THEMES). The palette becomes the active one before the content is composed, and the
 * content is recreated when it changes, so every screen (and every value derived from FluideColors)
 * follows the new colours.
 *
 * Call it once, at the root. A FluideTheme nested in another one (a small preview of a theme) only
 * provides its MaterialTheme and [LocalFluidePalette]: the FluideColors getters keep the app palette.
 */
@Composable
fun FluideTheme(theme: String = "fluide", content: @Composable () -> Unit) {
    val palette = FluideThemes.byId(theme)
    val nested = LocalInsideFluideTheme.current
    // Before the content reads FluideColors: the getters return the colours of this palette.
    if (!nested) FluideThemes.active = palette
    MaterialTheme(colorScheme = palette.colorScheme, typography = Type, shapes = Corners) {
        // Text and icons without an explicit color are light on the dark screens (default is black).
        CompositionLocalProvider(
            LocalContentColor provides palette.text,
            LocalFluidePalette provides palette,
            LocalInsideFluideTheme provides true,
        ) {
            key(palette.id) { content() }
        }
    }
}

package app.fluide.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluide.player.ui.theme.FluideThemes

/**
 * Fluide Player brand palette, shared by every screen. Every member is a getter on the active colour
 * theme (see ui/theme/Theme.kt): read them in composition or while drawing, never in a value computed
 * once (use `by themed { }` for brushes declared at the top of a file).
 */
object FluideColors {
    /** App background (deepest colour of the theme). */
    val Navy: Color get() = FluideThemes.active.navy

    /** Middle stop of the screen gradient. */
    val NavyLight: Color get() = FluideThemes.active.navyLight

    /** Last stop of the screen gradient. */
    val Night: Color get() = FluideThemes.active.night

    /** Accent: start of the "fluid" gradient, primary color (electric blue in the original theme). */
    val Blue: Color get() = FluideThemes.active.blue

    /** Between the accent and the second accent (upper part of the mark). */
    val Azure: Color get() = FluideThemes.active.azure

    /** Second accent: end of the "fluid" gradient, focus borders (cyan in the original theme). */
    val Cyan: Color get() = FluideThemes.active.cyan

    /** Light highlight of the second accent (lower part of the mark). */
    val Aqua: Color get() = FluideThemes.active.aqua

    /** Third accent (violet in the original theme). */
    val Violet: Color get() = FluideThemes.active.violet

    /** Warm accent: live badges, recording, alerts. */
    val Warm: Color get() = FluideThemes.active.warm

    /** Glass panel fill: white at 7 %. */
    val Glass: Color get() = FluideThemes.active.glass

    /** Glass panel border: white at 10 %. */
    val GlassBorder: Color get() = FluideThemes.active.glassBorder

    /** Text and icons drawn over [FluideGradient] or a solid accent (dark on the light accents). */
    val OnAccent: Color get() = FluideThemes.active.onAccent

    /** Default text colour of the screens. */
    val Text: Color get() = FluideThemes.active.text

    /** Main text, slightly tinted by the theme. */
    val Soft: Color get() = FluideThemes.active.soft

    /** Secondary text (meta, labels). */
    val Muted: Color get() = FluideThemes.active.muted

    /** Tertiary text (hints, past items). */
    val Dim: Color get() = FluideThemes.active.dim

    /** Opaque raised surface (focused text field, wells). */
    val Raised: Color get() = FluideThemes.active.raised

    /** Translucent panel drawn over the video. */
    val Panel: Color get() = FluideThemes.active.panel
}

/** The "fluid" gradient (accent to second accent, top-left to bottom-right of the drawn area). */
val FluideGradient: Brush get() = FluideThemes.active.gradient

/** Glass panel: white 7 % fill with a 1 dp white 10 % border ([shape]: 16 dp for cards, 12 dp for rows). */
fun Modifier.fluideGlass(shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    this.background(FluideColors.Glass, shape).border(1.dp, FluideColors.GlassBorder, shape)

/**
 * The Fluide Player logo: the mark followed, when [withWordmark], by the word mark "Fluide" (bold,
 * white) "Player" (lighter, second accent of the theme). [size] is the height of the mark; the word
 * mark is sized from it.
 */
@Composable
fun FluideLogo(modifier: Modifier = Modifier, size: Dp = 40.dp, withWordmark: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FluideMark(Modifier.size(size))
        if (withWordmark) {
            Spacer(Modifier.width(size * 0.1f))
            // From Dp: the logo keeps its proportions whatever the system font scale.
            val fontSize = with(LocalDensity.current) { (size * 0.58f).toSp() }
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.ExtraBold)) { append("Fluide") }
                    withStyle(SpanStyle(color = FluideColors.Cyan, fontWeight = FontWeight.Normal)) { append(" Player") }
                },
                fontSize = fontSize,
                lineHeight = fontSize * 1.2f,
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * The Fluide Player mark alone: a rounded play triangle filled with a flowing wave, an accent coloured
 * upper part and a second accent "liquid" lower part split by a wavy gap (in the colours of the theme). Drawn in the square of [modifier]
 * (give it a size). Same geometry as the launcher icon and the TV banner (res/drawable).
 */
@Composable
fun FluideMark(modifier: Modifier = Modifier) {
    Spacer(
        modifier.drawWithCache {
            // The artwork is designed in a 100 x 100 box; the triangle spans x 24..94, so it is shifted
            // left by 9 to be centred in the square.
            val unit = size.minDimension / 100f
            val left = (size.width - unit * 100f) / 2f - 9f * unit
            val top = (size.height - unit * 100f) / 2f
            fun at(x: Float, y: Float) = Offset(left + x * unit, top + y * unit)

            val upper = MarkPath(unit, left, top).apply {
                moveTo(24f, 58f)
                lineTo(24f, 20f)
                quadTo(24f, 10f, 32.69f, 14.96f)
                lineTo(85.31f, 45.04f)
                quadTo(94f, 50f, 85.31f, 54.96f)
                lineTo(80f, 58f)
                cubicTo(58f, 40f, 42f, 74f, 24f, 58f)
                close()
            }.path
            val lower = MarkPath(unit, left, top).apply {
                moveTo(24f, 64.5f)
                cubicTo(42f, 80.5f, 57f, 46f, 76f, 60.29f)
                lineTo(32.69f, 85.04f)
                quadTo(24f, 90f, 24f, 80f)
                close()
            }.path
            val upperBrush = Brush.linearGradient(
                listOf(FluideColors.Blue, FluideColors.Azure),
                start = at(24f, 12f),
                end = at(90f, 56f),
            )
            val lowerBrush = Brush.linearGradient(
                listOf(FluideColors.Cyan, FluideColors.Aqua),
                start = at(24f, 62f),
                end = at(62f, 88f),
            )
            onDrawBehind {
                drawPath(upper, upperBrush)
                drawPath(lower, lowerBrush)
            }
        },
    )
}

/** Path builder in the 100 x 100 design box of the mark (quadratic curves written as cubics). */
private class MarkPath(private val unit: Float, private val left: Float, private val top: Float) {
    val path = Path()
    private var lastX = 0f
    private var lastY = 0f

    private fun x(v: Float) = left + v * unit
    private fun y(v: Float) = top + v * unit

    fun moveTo(px: Float, py: Float) {
        path.moveTo(x(px), y(py))
        lastX = px
        lastY = py
    }

    fun lineTo(px: Float, py: Float) {
        path.lineTo(x(px), y(py))
        lastX = px
        lastY = py
    }

    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, px: Float, py: Float) {
        path.cubicTo(x(x1), y(y1), x(x2), y(y2), x(px), y(py))
        lastX = px
        lastY = py
    }

    /** Quadratic curve with control point (cx, cy), as the equivalent cubic curve. */
    fun quadTo(cx: Float, cy: Float, px: Float, py: Float) {
        cubicTo(
            lastX + (cx - lastX) * 2f / 3f,
            lastY + (cy - lastY) * 2f / 3f,
            px + (cx - px) * 2f / 3f,
            py + (cy - py) * 2f / 3f,
            px,
            py,
        )
    }

    fun close() {
        path.close()
    }
}

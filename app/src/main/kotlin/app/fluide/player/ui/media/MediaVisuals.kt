package app.fluide.player.ui.media

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.fluide.player.data.Section
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.theme.blend
import app.fluide.player.ui.theme.themed
import coil.compose.AsyncImage
import java.util.Locale

/*
 * Visual building blocks shared by the movies / series grid and the details pages: palette, glass
 * panels, focus halo, shimmer placeholders, rating chip, progress stripe and badges.
 */

/** Fluide Player palette, as used by the media screens (brand colours follow the colour theme). */
internal object MediaPalette {
    val Navy: Color get() = FluideColors.Navy
    val Blue: Color get() = FluideColors.Blue
    val Cyan: Color get() = FluideColors.Cyan
    val Violet: Color get() = FluideColors.Violet
    val Heart = Color(0xFFFF5C7A)

    /** Text and icons over the full accent gradient (dark on the light accents of some themes). */
    val OnAccent: Color get() = FluideColors.OnAccent

    /** Main text on the dark screens. */
    val Soft: Color get() = FluideColors.Soft

    /** Secondary text (meta, labels). */
    val Muted: Color get() = FluideColors.Muted

    /** Glass panel fill: white at 7 %. */
    val Glass = Color(0x12FFFFFF)

    /** Glass panel border: white at 10 %. */
    val GlassBorder = Color(0x1AFFFFFF)

    /** Dark glass behind small labels drawn over pictures. */
    val Scrim: Color get() = FluideColors.Navy.copy(alpha = 0.78f)

    val ShimmerBase: Color by themed { blend(it.surface, it.dialogTop, 0.66f) }
    val ShimmerHighlight: Color by themed { blend(it.blue, Color.White, 0.56f).copy(alpha = 0.18f) }
}

/** The "fluid" gradient: accent to second accent (electric blue to cyan in the original theme). */
internal val MediaFluidBrush: Brush get() = FluideGradient

/** Progress fill (left to right). */
internal val MediaProgressBrush: Brush by themed { Brush.horizontalGradient(listOf(it.blue, it.cyan)) }

/** Border of a focused card: white melting into the second accent. */
internal val MediaFocusBorder: Brush by themed { Brush.linearGradient(listOf(Color.White, it.cyan)) }

/** Border of a card at rest. */
internal val MediaRestBorder: Brush = SolidColor(MediaPalette.GlassBorder)

internal val MediaGlassBrush: Brush = SolidColor(MediaPalette.Glass)

/** Card background while there is no picture (or none at all). */
internal val MediaArtPlaceholder: Brush by themed {
    Brush.linearGradient(
        listOf(blend(it.dialogTop, it.blue, 0.08f), blend(it.surface, it.dialogTop, 0.62f), blend(it.night, it.violet, 0.16f)),
    )
}

/** Bottom scrim of the posters and episode pictures, under their texts. */
internal val MediaBottomScrim: Brush by themed {
    Brush.verticalGradient(listOf(Color.Transparent, it.navy.copy(alpha = 0.62f), it.navy.copy(alpha = 0.94f)))
}

private val TrackColor = Color(0x2EFFFFFF)
private val PillShape = RoundedCornerShape(24.dp)

/** Glass panel: white 7 % fill with a 1 dp white 10 % border. */
internal fun Modifier.mediaGlass(shape: Shape): Modifier =
    this.background(MediaPalette.Glass, shape).border(1.dp, MediaPalette.GlassBorder, shape)

/**
 * Soft blue / cyan halo around a focused element, drawn outside its bounds (works on every API level,
 * unlike colored shadows). Put it before `clip` in the modifier chain.
 */
internal fun Modifier.mediaFocusGlow(active: Boolean, corner: Dp): Modifier = drawBehind {
    if (!active) return@drawBehind
    val radius = corner.toPx()
    for (i in 1..3) {
        val spread = i * 3.dp.toPx()
        drawRoundRect(
            brush = MediaFluidBrush,
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2f, size.height + spread * 2f),
            cornerRadius = CornerRadius(radius + spread),
            alpha = 0.3f / i,
        )
    }
}

/**
 * Widens a horizontally scrolling row by [bleed] on both sides without moving it: pair it with the
 * same horizontal content padding and the first item stays aligned with the text around it, while the
 * scroll clip no longer cuts the enlarged border and the glow of a focused first or last item.
 */
internal fun Modifier.horizontalBleed(bleed: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val free = measurable.measure(constraints)
        return@layout layout(free.width, free.height) { free.placeRelative(0, 0) }
    }
    val extra = bleed.roundToPx() * 2
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-extra / 2, 0) }
}

/** Glyph standing for a section (placeholders, kickers). */
internal fun sectionGlyph(section: Section): Glyph = if (section == Section.SERIES) Glyph.SERIES else Glyph.MOVIE

/**
 * Loading placeholder: a dark block crossed by a slow diagonal light sweep. The animation value is read
 * in the draw phase only, so the sweep never recomposes anything.
 */
@Composable
internal fun ShimmerBlock(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp)) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val sweep = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerSweep",
    )
    Box(
        modifier
            .clip(shape)
            .background(MediaPalette.ShimmerBase, shape)
            .drawBehind {
                // Diagonal coordinate u = x + y runs from 0 (top left) to width + height (bottom right).
                val total = (size.width + size.height).coerceAtLeast(1f)
                val band = total * 0.3f
                val center = -band + (total + band * 2f) * sweep.value
                val from = (center - band) / 2f
                val to = (center + band) / 2f
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color.Transparent, MediaPalette.ShimmerHighlight, Color.Transparent),
                        start = Offset(from, from),
                        end = Offset(to, to),
                    ),
                )
            },
    )
}

/**
 * Picture of a poster or an episode: a shimmer while it loads, a glyph on the placeholder gradient
 * when there is no picture or it cannot be loaded.
 */
@Composable
internal fun MediaArt(
    url: String?,
    contentDescription: String?,
    fallbackGlyph: Glyph,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 40.dp,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (url == null || failed) {
            Box(Modifier.fillMaxSize().background(MediaArtPlaceholder))
            AppIcon(fallbackGlyph, Color.White.copy(alpha = 0.22f), size = glyphSize)
        } else {
            if (!loaded) ShimmerBlock(Modifier.fillMaxSize(), RectangleShape)
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { loaded = true },
                onError = { failed = true },
            )
        }
    }
}

/** Rating out of 10 ("7,4"), or null when unknown (some catalogues store it out of 100). */
internal fun ratingLabel(rating: Double?): String? {
    if (rating == null || rating <= 0.0) return null
    val value = if (rating > 10) rating / 10 else rating
    return String.format(Locale.getDefault(), "%.1f", value)
}

/** "★ 7,4" on dark glass, for the corner of a poster. Draws nothing without a rating. */
@Composable
internal fun RatingChip(rating: Double?, modifier: Modifier = Modifier) {
    val label = ratingLabel(rating) ?: return
    Row(
        modifier
            .background(MediaPalette.Scrim, PillShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), PillShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("★", color = MediaPalette.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(3.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Small round badge with a heart, for the favorites. */
@Composable
internal fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .background(MediaPalette.Scrim, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("♥", color = MediaPalette.Heart, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** Thin rounded progress bar: translucent track, blue to cyan fill. */
@Composable
internal fun ProgressStripe(fraction: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val value = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .height(height)
            .clip(CircleShape)
            .background(TrackColor),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(value)
                .background(MediaProgressBrush, CircleShape),
        )
    }
}

/** Small bold pill label (episode number, status...). */
@Composable
internal fun MediaBadge(text: String, modifier: Modifier = Modifier, fill: Brush = MediaFluidBrush, color: Color = MediaPalette.OnAccent) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.4.sp,
        maxLines = 1,
        modifier = modifier
            .background(fill, PillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Information chip of the details pages (year, genre, duration...): a glass pill. */
@Composable
internal fun MetaChip(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        text,
        color = if (accent) Color.White else MediaPalette.Soft,
        fontSize = 14.sp,
        fontWeight = if (accent) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                if (accent) Brush.linearGradient(listOf(MediaPalette.Blue.copy(alpha = 0.45f), MediaPalette.Cyan.copy(alpha = 0.25f))) else MediaGlassBrush,
                PillShape,
            )
            .border(1.dp, if (accent) MediaPalette.Cyan.copy(alpha = 0.45f) else MediaPalette.GlassBorder, PillShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** Short blue to cyan bar in front of the section titles. */
@Composable
internal fun AccentBar(modifier: Modifier = Modifier, width: Dp = 22.dp) {
    Box(
        modifier
            .width(width)
            .height(3.dp)
            .background(MediaFluidBrush, CircleShape),
    )
}

/** Round glass icon button (back...), filled with the fluid gradient and enlarged while focused. */
@Composable
internal fun RoundIconButton(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, diameter: Dp = 48.dp) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "roundButton")
    Box(
        modifier
            .size(diameter)
            .onFocusChanged { focused = it.isFocused }
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .mediaFocusGlow(focused, diameter / 2)
            .clip(CircleShape)
            .background(if (focused) MediaFluidBrush else MediaGlassBrush, CircleShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else MediaPalette.GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(glyph, if (focused) MediaPalette.OnAccent else Color.White, size = diameter * 0.42f)
    }
}

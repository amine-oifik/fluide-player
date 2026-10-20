package app.fluide.player.ui.live

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.Programme
import app.fluide.player.ui.EpgState
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenBrush
import app.fluide.player.ui.components.formatDuration
import app.fluide.player.ui.theme.FluideThemes
import app.fluide.player.ui.theme.blend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Corner radius of the preview hole: the same as the clip of the video in FluideRoot. */
internal val PreviewCorner = 12.dp

/**
 * Where the video preview is in this full-screen draw area. The root places the video with
 * Modifier.offset, which is mirrored in right-to-left layouts (Arabic): the hole follows it.
 */
private fun CacheDrawScope.holeRect(holeOrigin: DpOffset, holeSize: DpSize): Rect {
    val width = holeSize.width.toPx()
    val left = if (layoutDirection == LayoutDirection.Rtl) size.width - holeOrigin.x.toPx() - width else holeOrigin.x.toPx()
    val top = holeOrigin.y.toPx()
    return Rect(left, top, left + width, top + holeSize.height.toPx())
}

/** Paints the screen background everywhere but the video preview. */
fun Modifier.previewHole(origin: DpOffset, size: DpSize): Modifier = holeBackground(origin, size)

private fun Modifier.holeBackground(holeOrigin: DpOffset, holeSize: DpSize): Modifier = drawWithCache {
    val rect = holeRect(holeOrigin, holeSize)
    val hole = RoundRect(
        left = rect.left,
        top = rect.top,
        right = rect.right,
        bottom = rect.bottom,
        cornerRadius = CornerRadius(PreviewCorner.toPx()),
    )
    val holePath = Path().apply { addRoundRect(hole) }
    val reach = size.maxDimension.coerceAtLeast(1f)
    // Same soft glows as the other screens (ScreenSurface): those of the colour theme.
    val palette = FluideThemes.active
    val blueGlow = Brush.radialGradient(
        listOf(palette.glowStart, Color.Transparent),
        center = Offset(size.width * 0.1f, 0f),
        radius = reach * 0.55f,
    )
    val violetGlow = Brush.radialGradient(
        listOf(palette.glowEnd, Color.Transparent),
        center = Offset(size.width, size.height),
        radius = reach * 0.5f,
    )
    val background = ScreenBrush
    onDrawBehind {
        clipPath(holePath, ClipOp.Difference) {
            drawRect(background)
            drawRect(blueGlow)
            drawRect(violetGlow)
        }
    }
}

/**
 * Glowing rounded frame around the preview: a crisp blue / cyan / violet line and soft halos, all
 * drawn outside the hole (never over the video).
 */
internal fun Modifier.previewGlow(origin: DpOffset, size: DpSize): Modifier = holeGlow(origin, size)

private fun Modifier.holeGlow(holeOrigin: DpOffset, holeSize: DpSize): Modifier = drawWithCache {
    val rect = holeRect(holeOrigin, holeSize)
    val left = rect.left
    val top = rect.top
    val right = rect.right
    val bottom = rect.bottom
    val corner = PreviewCorner.toPx()
    val holePath = Path().apply {
        addRoundRect(RoundRect(left = left, top = top, right = right, bottom = bottom, cornerRadius = CornerRadius(corner)))
    }
    val frame = Brush.linearGradient(
        listOf(FluideColors.Blue, FluideColors.Cyan, FluideColors.Violet),
        start = Offset(left, top),
        end = Offset(right, bottom),
    )
    // (distance of the stroke centre from the edge of the hole, stroke width, alpha) of each halo.
    val halos = listOf(
        Triple(10.dp.toPx(), 12.dp.toPx(), 0.05f),
        Triple(6.dp.toPx(), 8.dp.toPx(), 0.09f),
        Triple(3.dp.toPx(), 4.dp.toPx(), 0.22f),
    )
    val line = 1.25.dp.toPx()
    val lineWidth = 2.5.dp.toPx()
    onDrawBehind {
        clipPath(holePath, ClipOp.Difference) {
            for ((spread, width, alpha) in halos) {
                drawRoundRect(
                    color = FluideColors.Cyan,
                    topLeft = Offset(left - spread, top - spread),
                    size = Size(right - left + 2 * spread, bottom - top + 2 * spread),
                    cornerRadius = CornerRadius(corner + spread),
                    style = Stroke(width = width),
                    alpha = alpha,
                )
            }
            drawRoundRect(
                brush = frame,
                topLeft = Offset(left - line, top - line),
                size = Size(right - left + 2 * line, bottom - top + 2 * line),
                cornerRadius = CornerRadius(corner + line),
                style = Stroke(width = lineWidth),
            )
        }
    }
}

/** Where a programme stands: past (without recording), recorded (catch-up), on air, to come. */
private enum class Slot { PAST, RECORDED, ON_AIR, FUTURE }

private fun slotOf(p: Programme, catchupDays: Int, nowSec: Long): Slot = when {
    p.startSec <= nowSec && p.stopSec > nowSec -> Slot.ON_AIR
    p.startSec > nowSec -> Slot.FUTURE
    catchupDays > 0 && p.startSec >= nowSec - catchupDays * 86_400L -> Slot.RECORDED
    else -> Slot.PAST
}

/**
 * Programme guide of one channel, day by day. Past programmes of a channel with catch-up are played
 * from the provider's recordings; the programme on air opens the live channel.
 */
@Composable
fun EpgScreen(vm: MainViewModel) {
    val epg by vm.epg.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val timeFormat = rememberTimeFormat(settings.clock24h)
    val nowSec = rememberNowSec()
    val listState = rememberLazyListState()
    val dayState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableIntStateOf(-1) }
    val target = if (epg.nowIndex >= 0) epg.nowIndex else 0
    val today = todayIndex(epg.days)
    // Width of the time column: "12:45 PM" needs more room than "12:45".
    val timeWidth = if (settings.clock24h) 52.dp else 72.dp

    LaunchedEffect(epg.version) {
        if (epg.programmes.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((target - 2).coerceAtLeast(0))
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    // A new channel: the selected day (today) is brought into view in the row of days.
    LaunchedEffect(epg.channelId, epg.days.size) {
        if (epg.days.isNotEmpty()) dayState.scrollToItem((epg.dayIndex - 1).coerceAtLeast(0))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (origin, size) = LiveLayout.preview(maxWidth)
        val start = liveStart(maxWidth)
        Box(
            Modifier
                .fillMaxSize()
                .previewHole(origin, size)
                .previewGlow(origin, size),
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(LiveLayout.GROUPS + LiveLayout.CHANNELS)
                        .fillMaxHeight()
                        .padding(start = start, top = 16.dp, end = 12.dp),
                ) {
                    EpgHeader(epg, settings.clock24h, onBack = { vm.onBack() })
                    Spacer(Modifier.height(14.dp))
                    LazyRow(
                        state = dayState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(epg.days) { index, day ->
                            val selected = index == epg.dayIndex
                            FluidPill(
                                selected = selected,
                                onClick = { vm.selectEpgDay(index) },
                                modifier = Modifier.height(40.dp),
                                idleFill = LiveColors.Glass,
                                idleBorder = LiveColors.GlassBorder,
                            ) { pillFocused ->
                                if (index == today) {
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .background(if (pillFocused) FluideColors.OnAccent else FluideColors.Warm, CircleShape),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    dayLabel(index - today, day),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = when {
                                        pillFocused -> FluideColors.OnAccent
                                        selected -> Color.White
                                        else -> LiveColors.Soft
                                    },
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (epg.programmes.isEmpty() && !epg.loading) {
                        EmptyDay()
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 20.dp),
                        ) {
                            itemsIndexed(epg.programmes, key = { index, p -> "$index:${p.startSec}" }) { index, p ->
                                ProgrammeRow(
                                    p = p,
                                    slot = slotOf(p, epg.catchupDays, nowSec),
                                    nowSec = nowSec,
                                    timeFormat = timeFormat,
                                    timeWidth = timeWidth,
                                    focusRequester = if (index == target) focus else null,
                                    onClick = { vm.playProgramme(index) },
                                    onFocused = { focused = index },
                                )
                            }
                        }
                    }
                }
                Column(
                    Modifier
                        .weight(1f - LiveLayout.GROUPS - LiveLayout.CHANNELS)
                        .fillMaxHeight()
                        .padding(start = 8.dp, end = LiveLayout.margin, bottom = 20.dp),
                ) {
                    Spacer(Modifier.height(origin.y + size.height + 18.dp))
                    val shown = epg.programmes.getOrNull(focused) ?: epg.programmes.getOrNull(epg.nowIndex)
                    ProgrammeDetails(
                        p = shown,
                        catchupDays = epg.catchupDays,
                        nowSec = nowSec,
                        timeFormat = timeFormat,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    )
                }
            }
            if (playback.channelId == 0L) {
                PreviewPlaceholder(
                    origin = origin,
                    size = size,
                    title = stringResource(R.string.live_no_preview),
                    hint = null,
                    logo = epg.logo,
                    name = epg.channelName,
                )
            }
        }
    }
}

@Composable
private fun EpgHeader(epg: EpgState, clock24h: Boolean, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconChip(Glyph.BACK, onClick = onBack)
        Spacer(Modifier.width(14.dp))
        LogoTile(epg.logo, epg.channelName, Modifier.size(width = 64.dp, height = 40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                epg.channelName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(if (epg.catchupDays > 0) Glyph.REPLAY else Glyph.GUIDE, FluideColors.Cyan, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                val caption = if (epg.catchupDays > 0) {
                    stringResource(R.string.live_replay, epg.catchupDays)
                } else {
                    stringResource(R.string.epg_title)
                }
                Text(caption, style = MaterialTheme.typography.bodyMedium, color = LiveColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Clock(clock24h)
    }
}

@Composable
private fun EmptyDay() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .liveGlass()
            .padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(Glyph.GUIDE, LiveColors.Muted, size = 34.dp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.epg_empty), style = MaterialTheme.typography.titleMedium, color = LiveColors.Soft)
    }
}

/**
 * One programme: start and end times, the timeline (with the "now" marker on the programme on air),
 * the title and its badge. The rows touch so that the timeline is continuous.
 */
@Composable
private fun ProgrammeRow(
    p: Programme,
    slot: Slot,
    nowSec: Long,
    timeFormat: java.text.DateFormat,
    timeWidth: Dp,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
    val fraction = if (slot == Slot.ON_AIR) progressOf(p.startSec, p.stopSec, nowSec) else 0f
    FocusableRow(
        onClick = onClick,
        selected = slot == Slot.ON_AIR,
        focusRequester = focusRequester,
        onFocused = onFocused,
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) { focused ->
        val dimmed = slot == Slot.PAST && !focused
        Column(Modifier.width(timeWidth)) {
            Text(
                timeFormat.formatSec(p.startSec),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = when {
                    focused -> Color.White
                    dimmed -> LiveColors.Dim
                    else -> LiveColors.Soft
                },
                maxLines = 1,
            )
            Text(
                timeFormat.formatSec(p.stopSec),
                style = MaterialTheme.typography.labelSmall,
                color = if (focused) Color(0xCCFFFFFF) else LiveColors.Dim,
                maxLines = 1,
            )
        }
        Timeline(slot, fraction, Modifier.width(22.dp).fillMaxHeight())
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    focused -> Color.White
                    dimmed -> LiveColors.Muted
                    else -> LiveColors.Soft
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = p.subtitle ?: p.category
            if (slot == Slot.ON_AIR) {
                Spacer(Modifier.height(5.dp))
                ThinProgress(fraction, Modifier.fillMaxWidth(), brush = LiveWarmBrush)
            } else if (meta != null) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) Color(0xFFE6ECFA) else LiveColors.Dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (slot) {
            Slot.ON_AIR -> LiveTag(stringResource(R.string.epg_live_badge), LiveWarmBrush, Modifier.padding(start = 10.dp))
            Slot.RECORDED -> ReplayTag(Modifier.padding(start = 10.dp))
            Slot.PAST, Slot.FUTURE -> Unit
        }
    }
}

private val PastLine: Color get() = FluideColors.Cyan.copy(alpha = 0.45f)
private val FutureLine = Color(0x26FFFFFF)

/**
 * Vertical timeline of the guide. The elapsed part is cyan; on the programme on air a warm glowing dot,
 * placed at the current time, marks "now". The line runs over the row padding to join the next rows.
 */
@Composable
private fun Timeline(slot: Slot, fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            val x = size.width / 2f
            val reach = 6.dp.toPx()
            val top = -reach
            val bottom = size.height + reach
            val stroke = 2.dp.toPx()
            val dotY = if (slot == Slot.ON_AIR) top + (bottom - top) * fraction else size.height / 2f
            when (slot) {
                Slot.PAST, Slot.RECORDED -> drawLine(color = PastLine, start = Offset(x, top), end = Offset(x, bottom), strokeWidth = stroke)
                Slot.FUTURE -> drawLine(color = FutureLine, start = Offset(x, top), end = Offset(x, bottom), strokeWidth = stroke)
                Slot.ON_AIR -> {
                    drawLine(color = PastLine, start = Offset(x, top), end = Offset(x, dotY), strokeWidth = stroke)
                    drawLine(color = FutureLine, start = Offset(x, dotY), end = Offset(x, bottom), strokeWidth = stroke)
                }
            }
            val dot = Offset(x, dotY)
            when (slot) {
                Slot.PAST -> drawCircle(color = PastLine, radius = 3.dp.toPx(), center = dot)
                Slot.RECORDED -> drawCircle(color = FluideColors.Cyan, radius = 3.5.dp.toPx(), center = dot)
                Slot.FUTURE -> drawCircle(color = Color(0x66FFFFFF), radius = 3.5.dp.toPx(), center = dot, style = Stroke(width = 1.5.dp.toPx()))
                Slot.ON_AIR -> {
                    drawCircle(color = FluideColors.Warm.copy(alpha = 0.22f), radius = 10.dp.toPx(), center = dot)
                    drawCircle(color = FluideColors.Warm, radius = 5.dp.toPx(), center = dot)
                    drawCircle(color = Color.White, radius = 2.dp.toPx(), center = dot)
                }
            }
        },
    )
}

/** Card under the preview: the focused programme (or the one on air) with its details and what OK does. */
@Composable
private fun ProgrammeDetails(
    p: Programme?,
    catchupDays: Int,
    nowSec: Long,
    timeFormat: java.text.DateFormat,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .liveGlass()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (p == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Glyph.GUIDE, LiveColors.Muted, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.epg_title), style = MaterialTheme.typography.titleSmall, color = LiveColors.Soft)
            }
            return@Column
        }
        val slot = slotOf(p, catchupDays, nowSec)
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (slot) {
                Slot.ON_AIR -> LiveTag(stringResource(R.string.epg_live_badge), LiveWarmBrush)
                Slot.RECORDED -> ReplayTag()
                Slot.FUTURE -> LiveTag(
                    stringResource(R.string.live_upcoming_badge),
                    SolidColor(FluideColors.Violet.copy(alpha = 0.22f)),
                    textColor = blend(FluideColors.Violet, Color.White, 0.72f),
                    border = FluideColors.Violet.copy(alpha = 0.6f),
                )
                Slot.PAST -> Unit
            }
            if (slot != Slot.PAST) Spacer(Modifier.width(10.dp))
            Text(
                formatDuration(p.stopSec - p.startSec),
                style = MaterialTheme.typography.labelMedium,
                color = LiveColors.Muted,
                maxLines = 1,
            )
        }
        Text(
            p.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${timeFormat.formatSec(p.startSec)} – ${timeFormat.formatSec(p.stopSec)}" + (p.category?.let { "  ·  $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
            color = LiveColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (slot == Slot.ON_AIR) {
            ThinProgress(progressOf(p.startSec, p.stopSec, nowSec), Modifier.fillMaxWidth(), brush = LiveWarmBrush, height = 4.dp)
            Text(
                stringResource(R.string.live_remaining, formatDuration(p.stopSec - nowSec)),
                style = MaterialTheme.typography.labelMedium,
                color = FluideColors.Cyan,
            )
        }
        if (slot == Slot.FUTURE) {
            Text(
                stringResource(R.string.live_starts_in, formatDuration(p.startSec - nowSec)),
                style = MaterialTheme.typography.labelMedium,
                color = blend(FluideColors.Violet, Color.White, 0.62f),
            )
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            p.subtitle?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, color = LiveColors.Soft, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            p.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = LiveColors.Muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val playable = slot == Slot.ON_AIR || slot == Slot.RECORDED
            AppIcon(if (playable) Glyph.PLAY else Glyph.GUIDE, if (playable) FluideColors.Cyan else LiveColors.Dim, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when (slot) {
                        Slot.ON_AIR -> R.string.live_epg_play_live
                        Slot.RECORDED -> R.string.live_epg_play_replay
                        Slot.FUTURE -> R.string.epg_future
                        Slot.PAST -> R.string.epg_no_recording
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (playable) LiveColors.Soft else LiveColors.Dim,
                letterSpacing = 0.2.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun todayIndex(days: List<Long>): Int {
    val now = System.currentTimeMillis() / 1_000
    val index = days.indexOfLast { it <= now }
    return if (index < 0) 0 else index
}

@Composable
private fun dayLabel(relative: Int, dayStartSec: Long): String = when (relative) {
    0 -> stringResource(R.string.epg_today)
    -1 -> stringResource(R.string.epg_yesterday)
    1 -> stringResource(R.string.epg_tomorrow)
    else -> {
        val locale = Locale.getDefault()
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale).format(Date(dayStartSec * 1_000))
    }
}

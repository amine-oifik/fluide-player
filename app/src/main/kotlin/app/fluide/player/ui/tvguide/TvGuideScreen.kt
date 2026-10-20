package app.fluide.player.ui.tvguide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.Programme
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.components.fluideGlass
import app.fluide.player.ui.components.formatDuration
import app.fluide.player.ui.theme.blend
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

private val CellShape = RoundedCornerShape(10.dp)
private val ChannelShape = RoundedCornerShape(12.dp)
private val CardShape = RoundedCornerShape(18.dp)
private val LogoShape = RoundedCornerShape(8.dp)
private val BadgeShape = RoundedCornerShape(6.dp)

private val RowHeight = 54.dp
private val RowSpacing = 6.dp
private val RulerHeight = 30.dp

/** Between the channel column and the programmes. */
private val ColumnGap = 8.dp

/** Between two programme cells. */
private val CellGap = 3.dp

/**
 * Full TV guide: channels down, time across (a window of 3 hours). The grid is a single focus target
 * driven by the remote: up / down change channel, left / right move between programmes (the window moves
 * at its edges), OK plays the programme on air or its recording, or sets a reminder for a future one.
 * The focus is a (row, time) pair held by [TvGuideController]: it stays put while the pages load.
 */
@Composable
fun TvGuideScreen(vm: MainViewModel) {
    val guide = vm.tvGuide
    val state by guide.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val reminderList by vm.reminders.all.collectAsStateWithLifecycle()
    val sourceId = ui.activeSourceId
    // Same test as vm.reminders.isSet, but read from the collected list so the cells follow its changes.
    val reminderKeys = remember(reminderList, sourceId) {
        reminderList.filter { it.sourceId == sourceId }.mapTo(HashSet()) { reminderKey(it.channelName, it.startSec) }
    }
    val isReminded: (GuideRow, Programme) -> Boolean = { row, p -> reminderKey(row.channel.name, p.startSec) in reminderKeys }
    val nowSec = rememberGuideNow()
    val clock24h = settings.clock24h
    val timeFormat = remember(clock24h) { SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    val windowStart = state.windowStartSec
    val windowEnd = windowStart + state.windowHours * TvGuideController.HOUR
    val gridFocus = remember { FocusRequester() }

    // The focused programme, shown by the information card.
    val focusedData = guide.rowAt(guide.focusedRow)
    val focusedCells = remember(focusedData, windowStart, windowEnd) { guideCells(focusedData, windowStart, windowEnd) }
    val focusedCell = focusedCells.getOrNull(focusedCells.indexAtTime(guide.anchorSec))

    val activate: (GuideRow?, GuideCell?) -> Unit = { row, cell ->
        val p = cell?.programme
        val now = System.currentTimeMillis() / 1_000
        when {
            row == null || cell == null -> Unit
            p != null -> vm.playProgrammeOf(row.channel.id, p)
            // A channel without guide can still be watched from here.
            now >= cell.startSec && now < cell.endSec -> vm.playChannelFromHome(row.channel.id)
        }
    }

    val windowLabel = stringResource(
        R.string.tvguide_window,
        relativeDay(windowStart),
        timeFormat.sec(windowStart),
        timeFormat.sec(windowEnd),
    )

    ScreenSurface {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 720.dp
            val side = if (compact) 16.dp else 32.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = side, end = side, top = if (compact) 14.dp else 22.dp, bottom = 12.dp),
            ) {
                GuideHeader(
                    compact = compact,
                    windowLabel = windowLabel,
                    clock24h = clock24h,
                    onEarlier = { guide.shift(-2) },
                    onNow = guide::showNow,
                    onLater = { guide.shift(2) },
                ) { modifier ->
                    InfoCard(
                        row = focusedData,
                        cell = focusedCell,
                        loadingList = state.loading,
                        emptyList = !state.loading && state.totalChannels == 0,
                        nowSec = nowSec,
                        timeFormat = timeFormat,
                        reminded = focusedData != null && focusedCell?.programme?.let { isReminded(focusedData, it) } == true,
                        compact = compact,
                        modifier = modifier,
                    )
                }
                Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                GuideGrid(
                    guide = guide,
                    state = state,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    nowSec = nowSec,
                    channelWidth = if (compact) 92.dp else 216.dp,
                    compact = compact,
                    timeFormat = timeFormat,
                    focusRequester = gridFocus,
                    isReminded = isReminded,
                    onActivate = activate,
                    onTapCell = { index, row, cell ->
                        runCatching { gridFocus.requestFocus() }
                        val anchor = guide.anchorSec
                        if (guide.focusedRow == index && anchor >= cell.startSec && anchor < cell.endSec) {
                            activate(row, cell)
                        } else {
                            guide.select(index, cell.startSec)
                        }
                    },
                    onTapChannel = { index, row ->
                        runCatching { gridFocus.requestFocus() }
                        if (guide.focusedRow == index && row != null) {
                            vm.playChannelFromHome(row.channel.id)
                        } else {
                            guide.select(index, guide.anchorSec)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { gridFocus.requestFocus() } }
}

// ------------------------------------------------------------------ header

@Composable
private fun GuideHeader(
    compact: Boolean,
    windowLabel: String,
    clock24h: Boolean,
    onEarlier: () -> Unit,
    onNow: () -> Unit,
    onLater: () -> Unit,
    info: @Composable (Modifier) -> Unit,
) {
    if (compact) {
        Column(Modifier.fillMaxWidth()) {
            TitleRow(clock24h)
            WindowLabel(windowLabel)
            Spacer(Modifier.height(8.dp))
            WindowButtons(compact = true, onEarlier = onEarlier, onNow = onNow, onLater = onLater)
            Spacer(Modifier.height(10.dp))
            info(Modifier.fillMaxWidth().height(116.dp))
        }
    } else {
        Row(Modifier.fillMaxWidth().height(156.dp)) {
            Column(Modifier.weight(0.42f).fillMaxHeight()) {
                TitleRow(clock24h)
                Spacer(Modifier.height(2.dp))
                WindowLabel(windowLabel)
                Spacer(Modifier.weight(1f))
                WindowButtons(compact = false, onEarlier = onEarlier, onNow = onNow, onLater = onLater)
            }
            Spacer(Modifier.width(20.dp))
            info(Modifier.weight(0.58f).fillMaxHeight())
        }
    }
}

@Composable
private fun TitleRow(clock24h: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Glyph.GUIDE, FluideColors.Cyan, size = 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.tvguide_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = FluideColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Clock(clock24h, showDate = false)
    }
}

@Composable
private fun WindowLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Glyph.CALENDAR, FluideColors.Muted, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = FluideColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WindowButtons(compact: Boolean, onEarlier: () -> Unit, onNow: () -> Unit, onLater: () -> Unit) {
    // Right to left: time runs from right to left, the arrows follow it.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        TvButton(
            stringResource(R.string.tvguide_earlier),
            onClick = onEarlier,
            glyph = if (compact) null else if (rtl) Glyph.FORWARD else Glyph.REWIND,
        )
        TvButton(stringResource(R.string.epg_now), onClick = onNow, glyph = Glyph.CLOCK, primary = true)
        TvButton(
            stringResource(R.string.tvguide_later),
            onClick = onLater,
            glyph = if (compact) null else if (rtl) Glyph.REWIND else Glyph.FORWARD,
        )
    }
}

/** The focused programme: badges, title, times, description and what OK does. */
@Composable
private fun InfoCard(
    row: GuideRow?,
    cell: GuideCell?,
    loadingList: Boolean,
    emptyList: Boolean,
    nowSec: Long,
    timeFormat: DateFormat,
    reminded: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val channel = row?.channel
    val p = cell?.programme
    val onAir = p != null && p.startSec <= nowSec && nowSec < p.stopSec
    val replay = p != null && channel != null && replayable(channel, p, nowSec)
    val future = p != null && p.startSec > nowSec
    Column(
        modifier
            .clip(CardShape)
            .fluideGlass(CardShape)
            .padding(horizontal = 18.dp, vertical = if (compact) 10.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                onAir -> Badge(stringResource(R.string.epg_live_badge), FluideColors.Warm)
                replay -> Badge(stringResource(R.string.epg_replay_badge), FluideColors.Cyan, Glyph.REPLAY)
                future -> Badge(stringResource(R.string.tvguide_badge_upcoming), FluideColors.Violet)
            }
            if (onAir || replay || future) Spacer(Modifier.width(6.dp))
            if (reminded) {
                Badge(stringResource(R.string.tvguide_badge_reminder), FluideColors.Warm, Glyph.ALARM)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                channel?.let { c -> listOfNotNull(c.number?.toString(), c.name).joinToString("  ") }.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = FluideColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (p != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDuration(p.stopSec - p.startSec),
                    style = MaterialTheme.typography.labelMedium,
                    color = FluideColors.Dim,
                    maxLines = 1,
                )
            }
        }
        Text(
            when {
                p != null -> p.title
                emptyList -> stringResource(R.string.guide_empty)
                row == null || cell == null || cell.loading -> stringResource(if (loadingList) R.string.tvguide_loading else R.string.tvguide_loading_row)
                else -> stringResource(R.string.tvguide_no_info)
            },
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (p != null) Color.White else FluideColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (p != null) {
            val times = "${timeFormat.sec(p.startSec)} – ${timeFormat.sec(p.stopSec)}"
            val extra = when {
                onAir -> stringResource(R.string.tvguide_remaining, formatDuration(p.stopSec - nowSec))
                future -> stringResource(R.string.tvguide_starts_in, formatDuration(p.startSec - nowSec))
                else -> null
            }
            Text(
                listOfNotNull(times, extra, p.category).joinToString("  ·  "),
                style = MaterialTheme.typography.bodySmall,
                color = if (onAir) FluideColors.Cyan else FluideColors.Soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            // Compact layout: no room for a full line, the description is left out.
            val text = if (compact) null else p?.description ?: p?.subtitle
            if (text != null) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = FluideColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val coversNow = cell != null && nowSec >= cell.startSec && nowSec < cell.endSec
        val (glyph, hint) = when {
            onAir -> Glyph.PLAY to R.string.tvguide_ok_live
            replay -> Glyph.REPLAY to R.string.tvguide_ok_replay
            future && reminded -> Glyph.ALARM to R.string.tvguide_ok_unremind
            future -> Glyph.ALARM to R.string.tvguide_ok_remind
            p != null -> Glyph.INFO to R.string.epg_no_recording
            row != null && coversNow -> Glyph.PLAY to R.string.tvguide_ok_channel
            else -> Glyph.GUIDE to R.string.tvguide_keys_hint
        }
        val active = glyph == Glyph.PLAY || glyph == Glyph.REPLAY || glyph == Glyph.ALARM
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(glyph, if (active) FluideColors.Cyan else FluideColors.Dim, size = 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) FluideColors.Soft else FluideColors.Dim,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Small tinted label ("EN DIRECT", "REPLAY", "RAPPEL"...), readable in every colour theme. */
@Composable
private fun Badge(text: String, tint: Color, glyph: Glyph? = null) {
    val ink = blend(tint, Color.White, 0.55f)
    Row(
        Modifier
            .clip(BadgeShape)
            .background(tint.copy(alpha = 0.2f), BadgeShape)
            .border(1.dp, tint.copy(alpha = 0.6f), BadgeShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            AppIcon(glyph, ink, size = 11.dp)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = ink,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ grid

@Composable
private fun GuideGrid(
    guide: TvGuideController,
    state: TvGuideState,
    windowStart: Long,
    windowEnd: Long,
    nowSec: Long,
    channelWidth: Dp,
    compact: Boolean,
    timeFormat: DateFormat,
    focusRequester: FocusRequester,
    isReminded: (GuideRow, Programme) -> Boolean,
    onActivate: (GuideRow?, GuideCell?) -> Unit,
    onTapCell: (Int, GuideRow?, GuideCell) -> Unit,
    onTapChannel: (Int, GuideRow?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var focused by remember { mutableStateOf(false) }
    // OK acts when released, like the buttons, and only when it was pressed here.
    var okPressed by remember { mutableStateOf(false) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    // Keeps the focused row visible, with one row of context above and below it.
    LaunchedEffect(guide.focusedRow, state.version, state.totalChannels) {
        if (state.totalChannels > 0) keepVisible(listState, guide.focusedRow)
    }

    Box(
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) okPressed = false
            }
            .onKeyEvent { event ->
                when (event.type) {
                    KeyEventType.KeyDown -> when (event.key) {
                        // At the top, the key is not used: the focus goes up to the buttons.
                        Key.DirectionUp -> guide.moveRow(-1)
                        Key.DirectionDown -> {
                            guide.moveRow(1)
                            true
                        }
                        Key.DirectionLeft -> {
                            if (rtl) guide.moveLater() else guide.moveEarlier()
                            true
                        }
                        Key.DirectionRight -> {
                            if (rtl) guide.moveEarlier() else guide.moveLater()
                            true
                        }
                        Key.PageUp, Key.ChannelUp -> {
                            guide.moveRow(-pageSize(listState))
                            true
                        }
                        Key.PageDown, Key.ChannelDown -> {
                            guide.moveRow(pageSize(listState))
                            true
                        }
                        Key.MediaRewind -> {
                            guide.shift(-2)
                            true
                        }
                        Key.MediaFastForward -> {
                            guide.shift(2)
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            okPressed = true
                            true
                        }
                        else -> false
                    }
                    KeyEventType.KeyUp -> when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (okPressed) {
                                okPressed = false
                                val (cells, index) = guide.focusedCells()
                                onActivate(guide.rowAt(guide.focusedRow), cells.getOrNull(index))
                            }
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val timelinePx = with(density) { (maxWidth - channelWidth - ColumnGap).toPx() }.coerceAtLeast(1f)
            val pxPerSec = timelinePx / (windowEnd - windowStart).coerceAtLeast(1L).toFloat()
            Column(Modifier.fillMaxSize()) {
                TimeRuler(
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    pxPerSec = pxPerSec,
                    nowSec = nowSec,
                    channelWidth = channelWidth,
                    timeFormat = timeFormat,
                )
                when {
                    state.loading -> GridNote(stringResource(R.string.tvguide_loading))
                    state.totalChannels == 0 -> GridNote(stringResource(R.string.guide_empty))
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(RowSpacing),
                    ) {
                        items(count = state.totalChannels, key = { index -> guide.idAt(index) ?: (-1L - index) }) { index ->
                            GuideRowView(
                                guide = guide,
                                index = index,
                                windowStart = windowStart,
                                windowEnd = windowEnd,
                                pxPerSec = pxPerSec,
                                nowSec = nowSec,
                                channelWidth = channelWidth,
                                compact = compact,
                                gridFocused = focused,
                                timeFormat = timeFormat,
                                isReminded = isReminded,
                                onTapCell = onTapCell,
                                onTapChannel = onTapChannel,
                            )
                        }
                    }
                }
            }
            NowLine(
                windowStart = windowStart,
                windowEnd = windowEnd,
                pxPerSec = pxPerSec,
                nowSec = nowSec,
                channelWidth = channelWidth,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun pageSize(listState: LazyListState): Int = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)

private suspend fun keepVisible(listState: LazyListState, row: Int) {
    val info = listState.layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) {
        listState.scrollToItem((row - 1).coerceAtLeast(0))
        return
    }
    val full = visible.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }
    val first = full.firstOrNull()?.index ?: visible.first().index
    val last = full.lastOrNull()?.index ?: visible.last().index
    val count = full.size.coerceAtLeast(1)
    val target = when {
        row - 1 < first -> (row - 1).coerceAtLeast(0)
        row + 1 > last -> (row + 2 - count).coerceAtLeast(0)
        else -> return
    }
    if (target == listState.firstVisibleItemIndex && listState.firstVisibleItemScrollOffset == 0) return
    // A long jump (page keys, list reloaded) is instant; one row is animated.
    if (abs(target - listState.firstVisibleItemIndex) > count * 2) listState.scrollToItem(target) else listState.animateScrollToItem(target)
}

@Composable
private fun GridNote(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = FluideColors.Muted)
    }
}

/** Half-hour marks above the programmes (hours only when the space is short), the day at the left. */
@Composable
private fun TimeRuler(
    windowStart: Long,
    windowEnd: Long,
    pxPerSec: Float,
    nowSec: Long,
    channelWidth: Dp,
    timeFormat: DateFormat,
) {
    val density = LocalDensity.current
    val minLabelPx = with(density) { 64.dp.toPx() }
    val labelPad = with(density) { 6.dp.roundToPx() }
    val step = if (TvGuideController.HALF_HOUR * pxPerSec < minLabelPx) TvGuideController.HOUR else TvGuideController.HALF_HOUR
    Row(Modifier.fillMaxWidth().height(RulerHeight), verticalAlignment = Alignment.CenterVertically) {
        Text(
            shortDay(windowStart),
            modifier = Modifier.width(channelWidth).padding(horizontal = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = FluideColors.Soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(ColumnGap))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            var mark = firstMark(windowStart, step)
            while (mark < windowEnd) {
                val x = ((mark - windowStart) * pxPerSec).roundToInt()
                val current = nowSec >= mark && nowSec < mark + step
                key(mark) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset { IntOffset(x, 0) }
                            .size(width = 1.dp, height = 8.dp)
                            .background(FluideColors.GlassBorder),
                    )
                    Text(
                        timeFormat.sec(mark),
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset(x + labelPad, 0) },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) FluideColors.Text else FluideColors.Muted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                mark += step
            }
        }
    }
}

/** Vertical "now" line across the ruler and the rows, with a dot on the ruler. */
@Composable
private fun NowLine(
    windowStart: Long,
    windowEnd: Long,
    pxPerSec: Float,
    nowSec: Long,
    channelWidth: Dp,
    modifier: Modifier = Modifier,
) {
    if (nowSec < windowStart || nowSec >= windowEnd) return
    val density = LocalDensity.current
    val x = ((nowSec - windowStart) * pxPerSec).roundToInt()
    val lineHalf = with(density) { 1.dp.roundToPx() }
    val glowHalf = with(density) { 3.dp.roundToPx() }
    val dotHalf = with(density) { 5.dp.roundToPx() }
    val lineTop = RulerHeight - 9.dp
    val dotTop = with(density) { (lineTop - 5.dp).roundToPx() }
    Row(modifier) {
        Spacer(Modifier.width(channelWidth + ColumnGap))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(
                Modifier
                    .offset { IntOffset(x - glowHalf, 0) }
                    .padding(top = lineTop)
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(FluideColors.Warm.copy(alpha = 0.16f)),
            )
            Box(
                Modifier
                    .offset { IntOffset(x - lineHalf, 0) }
                    .padding(top = lineTop)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(FluideColors.Warm),
            )
            Box(
                Modifier
                    .offset { IntOffset(x - dotHalf, dotTop) }
                    .size(10.dp)
                    .background(FluideColors.Warm, CircleShape),
            )
        }
    }
}

@Composable
private fun GuideRowView(
    guide: TvGuideController,
    index: Int,
    windowStart: Long,
    windowEnd: Long,
    pxPerSec: Float,
    nowSec: Long,
    channelWidth: Dp,
    compact: Boolean,
    gridFocused: Boolean,
    timeFormat: DateFormat,
    isReminded: (GuideRow, Programme) -> Boolean,
    onTapCell: (Int, GuideRow?, GuideCell) -> Unit,
    onTapChannel: (Int, GuideRow?) -> Unit,
) {
    val row = guide.rowAt(index)
    // Only the rows gaining or losing the focus recompose when it moves.
    val isFocusedRow by remember(index) { derivedStateOf { guide.focusedRow == index } }
    val cells = remember(row, windowStart, windowEnd) { guideCells(row, windowStart, windowEnd) }
    val selected = if (isFocusedRow) cells.indexAtTime(guide.anchorSec) else -1
    val density = LocalDensity.current
    val gapPx = with(density) { CellGap.roundToPx() }
    val timePx = with(density) { 92.dp.toPx() }
    val textPx = with(density) { 34.dp.toPx() }
    val tapChannel by rememberUpdatedState(onTapChannel)
    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        ChannelCell(
            channel = row?.channel,
            focusedRow = isFocusedRow,
            gridFocused = gridFocused,
            compact = compact,
            modifier = Modifier
                .width(channelWidth)
                .fillMaxHeight()
                .pointerInput(index) { detectTapGestures(onTap = { tapChannel(index, guide.rowAt(index)) }) },
        )
        Spacer(Modifier.width(ColumnGap))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            cells.forEachIndexed { i, cell ->
                val x0 = ((cell.startSec - windowStart) * pxPerSec).roundToInt()
                val x1 = ((cell.endSec - windowStart) * pxPerSec).roundToInt()
                val width = (x1 - x0 - gapPx).coerceAtLeast(1)
                val p = cell.programme
                key(cell.startSec) {
                    ProgrammeCell(
                        cell = cell,
                        x = x0,
                        widthPx = width,
                        windowStart = windowStart,
                        focused = i == selected && gridFocused,
                        selected = i == selected,
                        nowSec = nowSec,
                        timeFormat = timeFormat,
                        reminded = row != null && p != null && p.startSec > nowSec && isReminded(row, p),
                        replay = row != null && p != null && replayable(row.channel, p, nowSec),
                        showTitle = width >= textPx,
                        showTime = !compact && width >= timePx,
                        onTap = { onTapCell(index, row, cell) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelCell(
    channel: ChannelRow?,
    focusedRow: Boolean,
    gridFocused: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val fill = when {
        focusedRow && gridFocused -> FluideColors.Blue.copy(alpha = 0.32f)
        focusedRow -> Color.White.copy(alpha = 0.1f)
        else -> FluideColors.Glass
    }
    val border = when {
        focusedRow && gridFocused -> FluideColors.Cyan
        focusedRow -> FluideColors.Cyan.copy(alpha = 0.45f)
        else -> FluideColors.GlassBorder
    }
    Row(
        modifier
            .clip(ChannelShape)
            .background(fill, ChannelShape)
            .border(1.dp, border, ChannelShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (channel == null) {
            // Row still loading: the shape of a logo and a name.
            Box(Modifier.size(width = 46.dp, height = 30.dp).background(Color.White.copy(alpha = 0.05f), LogoShape))
            if (!compact) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).height(10.dp).background(Color.White.copy(alpha = 0.05f), CircleShape))
            }
        } else if (compact) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ChannelLogo(channel.logo, channel.name, Modifier.size(width = 52.dp, height = 30.dp))
                Text(
                    channel.number?.toString() ?: channel.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (focusedRow) FluideColors.Text else FluideColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                channel.number?.toString().orEmpty(),
                modifier = Modifier.width(36.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (focusedRow) FluideColors.Text else FluideColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 52.dp, height = 34.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (focusedRow) Color.White else FluideColors.Soft,
                    maxLines = if (channel.catchupDays > 0) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.catchupDays > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(Glyph.REPLAY, FluideColors.Cyan, size = 12.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.epg_replay_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = FluideColors.Cyan,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Channel logo, or its initials when it has none (or it cannot be loaded). */
@Composable
private fun ChannelLogo(logo: String?, name: String, modifier: Modifier = Modifier) {
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        modifier
            .clip(LogoShape)
            .background(Color.White.copy(alpha = 0.06f), LogoShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!logo.isNullOrBlank() && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                onError = { failed = true },
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                initials(name),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.ExtraBold,
                color = FluideColors.Muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * One cell, placed at [x] (pixels from the start of the timeline) with a width proportional to its
 * duration. Past programmes are dimmer, the programme on air shows its elapsed part.
 */
@Composable
private fun ProgrammeCell(
    cell: GuideCell,
    x: Int,
    widthPx: Int,
    windowStart: Long,
    focused: Boolean,
    selected: Boolean,
    nowSec: Long,
    timeFormat: DateFormat,
    reminded: Boolean,
    replay: Boolean,
    showTitle: Boolean,
    showTime: Boolean,
    onTap: () -> Unit,
) {
    val p = cell.programme
    val onAir = p != null && p.startSec <= nowSec && nowSec < p.stopSec
    val past = p != null && p.stopSec <= nowSec
    val fill: Brush = when {
        focused -> FluideGradient
        selected -> SolidColor(FluideColors.Cyan.copy(alpha = 0.16f))
        cell.loading -> SolidColor(Color.White.copy(alpha = 0.025f))
        p == null -> SolidColor(Color.White.copy(alpha = 0.02f))
        onAir -> SolidColor(FluideColors.Blue.copy(alpha = 0.2f))
        past -> SolidColor(Color.White.copy(alpha = 0.035f))
        else -> SolidColor(FluideColors.Glass)
    }
    val border = when {
        focused -> Color.White
        selected -> FluideColors.Cyan
        onAir -> FluideColors.Blue.copy(alpha = 0.5f)
        p == null -> FluideColors.GlassBorder.copy(alpha = 0.5f)
        else -> FluideColors.GlassBorder
    }
    val titleColor = when {
        focused -> FluideColors.OnAccent
        p == null -> FluideColors.Dim
        past -> FluideColors.Muted
        else -> FluideColors.Text
    }
    val metaColor = when {
        focused -> FluideColors.OnAccent.copy(alpha = 0.8f)
        past -> FluideColors.Dim
        else -> FluideColors.Muted
    }
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier
            .span(x, widthPx)
            .clip(CellShape)
            .background(fill, CellShape)
            .border(if (focused) 2.dp else 1.dp, border, CellShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) },
    ) {
        if (onAir && !focused) {
            // Elapsed part of the programme on air: a warm line at the bottom, up to the "now" line.
            val elapsed = ((nowSec - cell.startSec).toFloat() / (cell.endSec - cell.startSec).coerceAtLeast(1L)).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(elapsed)
                    .height(3.dp)
                    .background(FluideColors.Warm.copy(alpha = 0.85f)),
            )
        }
        if (showTitle) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Started before the window: a small mark at the start of the title.
                    val title = when {
                        p == null -> if (cell.loading) "" else stringResource(R.string.tvguide_no_info)
                        p.startSec < windowStart -> "‹ ${p.title}"
                        else -> p.title
                    }
                    Text(
                        title,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (p != null) FontWeight.SemiBold else FontWeight.Normal,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (replay) {
                        Spacer(Modifier.width(5.dp))
                        AppIcon(Glyph.REPLAY, if (focused) FluideColors.OnAccent else FluideColors.Cyan, size = 13.dp)
                    }
                    if (reminded) {
                        Spacer(Modifier.width(5.dp))
                        AppIcon(Glyph.ALARM, if (focused) FluideColors.OnAccent else FluideColors.Warm, size = 13.dp)
                    }
                }
                if (showTime && p != null) {
                    Text(
                        "${timeFormat.sec(p.startSec)} – ${timeFormat.sec(p.stopSec)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        } else if (reminded || replay) {
            AppIcon(
                if (reminded) Glyph.ALARM else Glyph.REPLAY,
                if (focused) FluideColors.OnAccent else if (reminded) FluideColors.Warm else FluideColors.Cyan,
                modifier = Modifier.align(Alignment.Center),
                size = 12.dp,
            )
        }
    }
}

/** Places a cell [x] pixels from the start of its row (mirrored right to left) with an exact pixel width. */
private fun Modifier.span(x: Int, width: Int): Modifier = this
    .offset { IntOffset(x, 0) }
    .layout { measurable, constraints ->
        val w = width.coerceIn(0, constraints.maxWidth)
        val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h))
        layout(w, placeable.height) { placeable.place(0, 0) }
    }

// ------------------------------------------------------------------ helpers

/** Recording available: the channel has catch-up and the programme is over and within its days. */
private fun replayable(channel: ChannelRow, p: Programme, nowSec: Long): Boolean =
    channel.catchupDays > 0 && p.stopSec <= nowSec && p.startSec >= nowSec - channel.catchupDays * 86_400L

private fun reminderKey(channelName: String, startSec: Long): String = "$startSec|$channelName"

private fun DateFormat.sec(epochSec: Long): String = format(Date(epochSec * 1_000))

private fun initials(name: String): String {
    val words = name.split(' ', '-', '_', '.', '|', '/').filter { it.isNotBlank() }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
}

/** First mark of [step] seconds (half hour or hour, local time) at or after [startSec]. */
private fun firstMark(startSec: Long, step: Long): Long {
    val offset = TimeZone.getDefault().getOffset(startSec * 1_000) / 1_000L
    val rest = (startSec + offset).mod(step)
    return if (rest == 0L) startSec else startSec + step - rest
}

/** Current time (epoch s), refreshed every 30 s so the "now" line and the states of the cells move. */
@Composable
private fun rememberGuideNow(): Long {
    val now by produceState(System.currentTimeMillis() / 1_000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1_000
        }
    }
    return now
}

/** Days between today and the day of [sec] (local time). */
private fun daysFromToday(sec: Long): Int {
    val calendar = Calendar.getInstance()
    fun midnight(ms: Long): Long {
        calendar.timeInMillis = ms
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
    val today = midnight(System.currentTimeMillis())
    val day = midnight(sec * 1_000)
    // Rounded: a day with a daylight saving change lasts 23 or 25 hours.
    return Math.round((day - today) / 86_400_000.0).toInt()
}

/** "Aujourd'hui", "Demain", "Hier" or the full date ("Lundi 27 octobre"). */
@Composable
private fun relativeDay(sec: Long): String = when (daysFromToday(sec)) {
    0 -> stringResource(R.string.epg_today)
    1 -> stringResource(R.string.epg_tomorrow)
    -1 -> stringResource(R.string.epg_yesterday)
    else -> formatDay(sec, "EEEEdMMMM")
}

/** "Lun. 20 oct.": the day of the window, above the channel column. */
private fun shortDay(sec: Long): String = formatDay(sec, "EEEdMMM")

private fun formatDay(sec: Long, skeleton: String): String {
    val locale = Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(sec * 1_000)).replaceFirstChar { it.titlecase(locale) }
}

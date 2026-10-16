package app.fluide.player.ui.live

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.Programme
import app.fluide.player.ui.GuideGroup
import app.fluide.player.ui.GuideState
import app.fluide.player.ui.LivePanel
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.components.formatDuration
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Geometry shared by the Live TV screen and the root (which places the video in the preview). */
object LiveLayout {
    const val GROUPS = 0.22f
    const val CHANNELS = 0.38f
    val margin = 20.dp

    /** Top-left corner and size of the preview video for a screen [width] wide. */
    fun preview(width: Dp): Pair<DpOffset, DpSize> {
        val x = width * (GROUPS + CHANNELS) + 8.dp
        val w = width - x - margin
        return DpOffset(x, margin) to DpSize(w, w * 9f / 16f)
    }
}

// ------------------------------------------------------------------ shared look of the Live TV and guide screens

/** Start padding of the Live TV and programme guide screens (TV overscan). */
internal val LiveStart = 36.dp

/** Palette of the Live TV and guide screens (Fluide Player design language). */
internal object LiveColors {
    /** Main text. */
    val Soft = Color(0xFFDCE3F2)

    /** Secondary text. */
    val Muted = Color(0xFFAEB7D3)

    /** Past programmes, end times. */
    val Dim = Color(0xFF7D87A6)

    /** Favorite star. */
    val Gold = Color(0xFFFFC857)

    /** Empty part of the progress bars. */
    val Track = Color(0x24FFFFFF)

    /** Glass panels: white 7 % with a white 10 % border. */
    val Glass = Color(0x12FFFFFF)
    val GlassBorder = Color(0x1AFFFFFF)

    /** Logo tiles and number badges. */
    val Tile = Color(0x14FFFFFF)

    /** Opaque fill of a focused text field. */
    val FieldFocused = Color(0xFF111A3D)
}

internal val LiveCardShape = RoundedCornerShape(16.dp)
internal val LivePillShape = RoundedCornerShape(24.dp)

/** Progress of a programme: the fluid blue to cyan gradient. */
internal val LiveProgressBrush = Brush.horizontalGradient(listOf(FluideColors.Blue, FluideColors.Cyan))

/** Live accent (programme on air, channel playing): warm orange. */
internal val LiveWarmBrush = Brush.horizontalGradient(listOf(FluideColors.Warm, Color(0xFFFF9150)))

private val SelectedPillBrush = Brush.horizontalGradient(
    listOf(FluideColors.Blue.copy(alpha = 0.34f), FluideColors.Cyan.copy(alpha = 0.14f)),
)
private val PlaceholderBrush = Brush.linearGradient(listOf(Color(0xFF121B42), Color(0xFF0B1230), Color(0xFF1B1040)))

/** Glass panel: white 7 % fill with a 1 dp white 10 % border. */
internal fun Modifier.liveGlass(shape: Shape = LiveCardShape): Modifier =
    this.background(LiveColors.Glass, shape).border(1.dp, LiveColors.GlassBorder, shape)

/** Time of the day in the format chosen in the settings (24 h or 12 h). */
@Composable
internal fun rememberTimeFormat(clock24h: Boolean): DateFormat {
    val locale = Locale.getDefault()
    return remember(clock24h, locale) { SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", locale) }
}

internal fun DateFormat.formatSec(epochSec: Long): String = format(Date(epochSec * 1_000))

/** Current time (epoch seconds), refreshed every 30 s so that the progress bars and the "now" marker move. */
@Composable
internal fun rememberNowSec(): Long {
    val now = produceState(System.currentTimeMillis() / 1_000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1_000
        }
    }
    return now.value
}

/** 0..1 part of a programme already played at [nowSec]. */
internal fun progressOf(startSec: Long, stopSec: Long, nowSec: Long): Float {
    val span = (stopSec - startSec).coerceAtLeast(1)
    return ((nowSec - startSec).toFloat() / span).coerceIn(0f, 1f)
}

// ------------------------------------------------------------------ Live TV screen

/**
 * Live TV: a header (title, group, clock), the groups as a glass column, the channels, and on the
 * right the channel playing (preview) above the programmes of the focused channel. The screen
 * background is painted around the preview so that the video, drawn below the UI, shows through.
 */
@Composable
fun LiveScreen(vm: MainViewModel, guide: GuideState, playingId: Long) {
    val panel by vm.livePanel.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nowSec = rememberNowSec()
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(guide.version) {
        if (guide.size == 0) return@LaunchedEffect
        if (!guide.focusList) {
            listState.scrollToItem(0)
            return@LaunchedEffect
        }
        listState.scrollToItem((guide.focusIndex - 3).coerceAtLeast(0))
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (origin, size) = LiveLayout.preview(maxWidth)
        Box(
            Modifier
                .fillMaxSize()
                .previewHole(origin, size)
                .previewGlow(origin, size),
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(LiveLayout.GROUPS + LiveLayout.CHANNELS).fillMaxHeight()) {
                    LiveHeader(guide = guide, clock24h = settings.clock24h, onHome = vm::openHome)
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        GroupColumn(
                            groups = guide.groups,
                            selected = guide.selected,
                            onSelect = vm::selectFilter,
                            modifier = Modifier.weight(LiveLayout.GROUPS).fillMaxHeight(),
                        )
                        Column(
                            Modifier
                                .weight(LiveLayout.CHANNELS)
                                .fillMaxHeight()
                                .padding(start = 6.dp, end = 10.dp),
                        ) {
                            SearchField(query = guide.query, onQuery = vm::search)
                            Spacer(Modifier.height(8.dp))
                            if (guide.size == 0) {
                                EmptyChannels()
                            } else {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(count = guide.size, key = { index -> vm.pager.idAt(index) ?: (-index.toLong() - 1) }) { index ->
                                        val row = vm.pager.rowAt(index)
                                        ChannelItem(
                                            row = row,
                                            playing = row != null && row.id == playingId,
                                            nowSec = nowSec,
                                            focusRequester = if (index == guide.focusIndex) focusRequester else null,
                                            onClick = { vm.playFromGuide(index) },
                                            onFavorite = { vm.toggleFavorite(index) },
                                            onFocused = { vm.focusChannel(index) },
                                        )
                                    }
                                }
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
                    ChannelPanel(
                        panel = panel,
                        playingId = playingId,
                        vm = vm,
                        clock24h = settings.clock24h,
                        nowSec = nowSec,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    )
                }
            }
            if (playingId == 0L) {
                PreviewPlaceholder(
                    origin = origin,
                    size = size,
                    title = stringResource(R.string.live_no_preview),
                    hint = stringResource(R.string.live_no_preview_hint),
                )
            }
        }
    }
}

@Composable
private fun LiveHeader(guide: GuideState, clock24h: Boolean, onHome: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = LiveStart, end = 16.dp, top = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconChip(Glyph.BACK, onClick = onHome)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot()
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.home_live),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                selectedLabel(guide) + "  ·  " + pluralStringResource(R.plurals.live_channel_count, guide.size, guide.size),
                style = MaterialTheme.typography.bodyMedium,
                color = LiveColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Clock(clock24h)
    }
}

/** Categories: a glass column of pills, the selected one tinted, with the number of channels. */
@Composable
private fun GroupColumn(
    groups: List<GuideGroup>,
    selected: ChannelFilter,
    onSelect: (ChannelFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .padding(start = LiveStart, end = 8.dp, bottom = 20.dp)
            .liveGlass(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(groups, key = { it.filter.toString() }) { group ->
            val isSelected = group.filter == selected
            FluidPill(
                selected = isSelected,
                onClick = { onSelect(group.filter) },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                focusedScale = 1.04f,
            ) { focused ->
                val highlighted = focused || isSelected
                val glyph = if (group.locked) Glyph.LOCK else groupGlyph(group.filter)
                if (glyph != null) {
                    val tint = when {
                        focused -> Color.White
                        glyph == Glyph.STAR -> LiveColors.Gold
                        isSelected -> FluideColors.Cyan
                        else -> LiveColors.Muted
                    }
                    AppIcon(glyph, tint, size = 16.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = groupLabel(group),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (highlighted) Color.White else LiveColors.Soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (group.count >= 0) {
                    Spacer(Modifier.width(6.dp))
                    CountChip(group.count, highlighted)
                }
            }
        }
    }
}

@Composable
private fun CountChip(count: Int, highlighted: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (highlighted) Color(0x33FFFFFF) else LiveColors.Tile, shape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (highlighted) Color.White else LiveColors.Muted,
            maxLines = 1,
        )
    }
}

private fun groupGlyph(filter: ChannelFilter): Glyph? = when (filter) {
    ChannelFilter.All -> Glyph.TV
    ChannelFilter.Favorites -> Glyph.STAR
    ChannelFilter.Recent -> Glyph.REFRESH
    ChannelFilter.Catchup -> Glyph.REPLAY
    is ChannelFilter.Search -> Glyph.SEARCH
    is ChannelFilter.Group -> null
}

@Composable
private fun groupLabel(group: GuideGroup): String = filterLabel(group.filter)

@Composable
private fun filterLabel(filter: ChannelFilter): String = when (filter) {
    ChannelFilter.All -> stringResource(R.string.guide_all_channels)
    ChannelFilter.Favorites -> stringResource(R.string.guide_favorites)
    ChannelFilter.Recent -> stringResource(R.string.guide_recent)
    ChannelFilter.Catchup -> stringResource(R.string.guide_catchup)
    is ChannelFilter.Group -> filter.name ?: stringResource(R.string.guide_no_group)
    is ChannelFilter.Search -> filter.query
}

/** Name of the list shown, for the header. */
@Composable
private fun selectedLabel(guide: GuideState): String {
    val group = guide.groups.firstOrNull { it.filter == guide.selected }
    return if (group != null) groupLabel(group) else filterLabel(guide.selected)
}

/** Search field of the channel list: a glass pill, cyan border while focused. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White)
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(FluideColors.Cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(LivePillShape)
                    .background(if (focused) LiveColors.FieldFocused else LiveColors.Glass, LivePillShape)
                    .border(
                        if (focused) 2.dp else 1.dp,
                        if (focused) FluideColors.Cyan else LiveColors.GlassBorder,
                        LivePillShape,
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(Glyph.SEARCH, if (focused) FluideColors.Cyan else LiveColors.Muted, size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.guide_search_hint),
                            style = textStyle,
                            color = LiveColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
private fun EmptyChannels() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .liveGlass()
            .padding(vertical = 28.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(Glyph.TV, LiveColors.Muted, size = 36.dp)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.guide_empty), style = MaterialTheme.typography.titleMedium, color = LiveColors.Soft)
    }
}

/**
 * One channel: number badge, logo tile, name, programme on air with a thin progress bar, replay and
 * favorite marks. OK plays it (twice: full screen), long OK or Menu toggles the favorite.
 */
@Composable
private fun ChannelItem(
    row: ChannelRow?,
    playing: Boolean,
    nowSec: Long,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onFocused: () -> Unit,
) {
    FocusableRow(
        onClick = onClick,
        onLongClick = onFavorite,
        focusRequester = focusRequester,
        selected = playing,
        onFocused = onFocused,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .onKeyEvent { event ->
                // Menu key on a channel: add to / remove from favorites (long OK works too).
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    onFavorite()
                    true
                } else {
                    false
                }
            },
    ) { focused ->
        NumberBadge(row?.number, playing = playing, focused = focused)
        Spacer(Modifier.width(10.dp))
        LogoTile(row?.logo, row?.name.orEmpty(), Modifier.size(width = 58.dp, height = 38.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    row?.name.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (focused || playing) Color.White else LiveColors.Soft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (playing) {
                    Spacer(Modifier.width(8.dp))
                    PlayingBars(if (focused) Color.White else FluideColors.Cyan)
                }
            }
            val title = row?.nowTitle
            if (row != null && title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) Color(0xFFE6ECFA) else LiveColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                ThinProgress(
                    fraction = progressOf(row.nowStartSec, row.nowStopSec, nowSec),
                    modifier = Modifier.fillMaxWidth(),
                    brush = if (playing) LiveWarmBrush else LiveProgressBrush,
                )
            }
        }
        if (row != null && row.catchupDays > 0) {
            AppIcon(
                Glyph.REPLAY,
                if (focused) Color.White else FluideColors.Cyan.copy(alpha = 0.85f),
                Modifier.padding(start = 10.dp),
                size = 18.dp,
            )
        }
        if (row?.favorite == true) {
            AppIcon(Glyph.STAR, LiveColors.Gold, Modifier.padding(start = 8.dp), size = 18.dp)
        }
    }
}

@Composable
private fun NumberBadge(number: Int?, playing: Boolean, focused: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    val fill: Brush = when {
        playing -> FluideGradient
        focused -> SolidColor(Color(0x33FFFFFF))
        else -> SolidColor(LiveColors.Tile)
    }
    Box(
        Modifier
            .widthIn(min = 42.dp)
            .height(28.dp)
            .clip(shape)
            .background(fill, shape)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number?.toString() ?: "–",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (playing || focused) Color.White else LiveColors.Soft,
            maxLines = 1,
        )
    }
}

/** Three small bars: the channel playing in the preview. */
@Composable
private fun PlayingBars(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val bar = size.width / 5f
        val heights = floatArrayOf(0.55f, 1f, 0.75f)
        for (i in heights.indices) {
            val h = size.height * heights[i]
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2f * bar, size.height - h),
                size = Size(bar, h),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

/**
 * Focused channel: name and group, the programme on air (time, time left, progress, description),
 * the next ones, and the programme guide / replay buttons, always visible at the bottom.
 */
@Composable
private fun ChannelPanel(
    panel: LivePanel?,
    playingId: Long,
    vm: MainViewModel,
    clock24h: Boolean,
    nowSec: Long,
    modifier: Modifier = Modifier,
) {
    val timeFormat = rememberTimeFormat(clock24h)
    Column(
        modifier
            .liveGlass()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (panel == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Glyph.TV, LiveColors.Muted, size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.live_select_channel), style = MaterialTheme.typography.titleSmall, color = LiveColors.Soft)
            }
            if (playingId != 0L) TvButton(stringResource(R.string.live_fullscreen_hint), onClick = vm::fullScreen)
            return@Column
        }
        val row = panel.row
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoTile(row.logo, row.name, Modifier.size(width = 56.dp, height = 36.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.group?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = LiveColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.favorite) {
                Spacer(Modifier.width(8.dp))
                AppIcon(Glyph.STAR, LiveColors.Gold, size = 18.dp)
            }
        }
        val now = panel.programmes.firstOrNull { it.startSec <= nowSec && it.stopSec > nowSec }
        val upcoming = panel.programmes.filter { it !== now && it.stopSec > nowSec }.take(3)
        Column(
            Modifier
                .weight(1f, fill = false)
                .clipToBounds(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (now == null && upcoming.isEmpty()) {
                Text(stringResource(R.string.epg_none), style = MaterialTheme.typography.bodyMedium, color = LiveColors.Muted)
            }
            if (now != null) NowBlock(now, timeFormat, nowSec)
            if (upcoming.isNotEmpty()) {
                Text(
                    stringResource(R.string.epg_next),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = FluideColors.Cyan,
                    letterSpacing = 0.6.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                for (p in upcoming) NextLine(p, timeFormat)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(stringResource(R.string.live_epg), onClick = { vm.openEpg(row.id) }, glyph = Glyph.GUIDE)
            if (row.catchupDays > 0) {
                TvButton(stringResource(R.string.live_replay, row.catchupDays), onClick = { vm.openEpg(row.id) }, glyph = Glyph.REPLAY)
            }
        }
        Text(
            stringResource(R.string.guide_favorite_hint),
            style = MaterialTheme.typography.labelSmall,
            color = LiveColors.Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NowBlock(p: Programme, timeFormat: DateFormat, nowSec: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveTag(stringResource(R.string.epg_now), LiveWarmBrush)
            Spacer(Modifier.width(8.dp))
            Text(
                "${timeFormat.formatSec(p.startSec)} – ${timeFormat.formatSec(p.stopSec)}",
                style = MaterialTheme.typography.labelMedium,
                color = LiveColors.Soft,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.live_remaining, formatDuration(p.stopSec - nowSec)),
                style = MaterialTheme.typography.labelMedium,
                color = FluideColors.Cyan,
                maxLines = 1,
            )
        }
        Text(
            p.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ThinProgress(progressOf(p.startSec, p.stopSec, nowSec), Modifier.fillMaxWidth(), brush = LiveWarmBrush, height = 4.dp)
        p.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = LiveColors.Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NextLine(p: Programme, timeFormat: DateFormat) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            timeFormat.formatSec(p.startSec),
            style = MaterialTheme.typography.labelLarge,
            color = FluideColors.Cyan.copy(alpha = 0.9f),
            maxLines = 1,
            modifier = Modifier.widthIn(min = 52.dp).padding(end = 10.dp),
        )
        Text(
            p.title,
            style = MaterialTheme.typography.bodyMedium,
            color = LiveColors.Soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ------------------------------------------------------------------ building blocks (also used by the guide)

/**
 * A pill for the remote and touch: transparent (or [idleFill]) at rest, tinted when [selected], filled
 * with the fluid gradient, white border and slightly enlarged while focused.
 */
@Composable
internal fun FluidPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusedScale: Float = 1.06f,
    idleFill: Color = Color.Transparent,
    idleBorder: Color = Color.Transparent,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "fluidPill")
    val fill: Brush = when {
        focused -> FluideGradient
        selected -> SelectedPillBrush
        else -> SolidColor(idleFill)
    }
    val border = when {
        focused -> Color.White
        selected -> FluideColors.Cyan.copy(alpha = 0.5f)
        else -> idleBorder
    }
    Row(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(LivePillShape)
            .background(fill, LivePillShape)
            .border(if (focused) 2.dp else 1.dp, border, LivePillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(focused)
    }
}

/** Round glass button with a glyph (back...): fluid gradient and white border while focused. */
@Composable
internal fun IconChip(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, chipSize: Dp = 44.dp) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "iconChip")
    val fill: Brush = if (focused) FluideGradient else SolidColor(LiveColors.Glass)
    Box(
        modifier
            .size(chipSize)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else LiveColors.GlassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(glyph, Color.White, size = chipSize * 0.45f)
    }
}

/**
 * Pulsing warm dot: "live". The pulse only changes the properties of its own layer (no recomposition,
 * nothing else redrawn), so it costs nothing next to the video.
 */
@Composable
internal fun LiveDot(modifier: Modifier = Modifier, dotSize: Dp = 10.dp) {
    val transition = rememberInfiniteTransition(label = "liveDot")
    val pulse by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_100), RepeatMode.Reverse),
        label = "liveDotPulse",
    )
    Box(modifier.size(dotSize * 2f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = pulse
                    scaleX = 0.55f + 0.45f * pulse
                    scaleY = 0.55f + 0.45f * pulse
                }
                .background(FluideColors.Warm.copy(alpha = 0.35f), CircleShape),
        )
        Box(
            Modifier
                .size(dotSize)
                .background(FluideColors.Warm, CircleShape),
        )
    }
}

/** Small label: "EN DIRECT", "Maintenant", "REPLAY"... */
@Composable
internal fun LiveTag(
    text: String,
    fill: Brush,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    border: Color? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}

/** "REPLAY" label: outlined cyan. */
@Composable
internal fun ReplayTag(modifier: Modifier = Modifier) {
    LiveTag(
        stringResource(R.string.epg_replay_badge),
        SolidColor(FluideColors.Cyan.copy(alpha = 0.14f)),
        modifier,
        textColor = FluideColors.Cyan,
        border = FluideColors.Cyan.copy(alpha = 0.5f),
    )
}

/** Thin rounded progress bar. */
@Composable
internal fun ThinProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    brush: Brush = LiveProgressBrush,
    height: Dp = 3.dp,
) {
    Box(
        modifier
            .height(height)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(color = LiveColors.Track, cornerRadius = radius)
                val filled = size.width * fraction.coerceIn(0f, 1f)
                if (filled > 0f) drawRoundRect(brush = brush, size = Size(filled, size.height), cornerRadius = radius)
            },
    )
}

/** Channel logo on a small glass tile; the initials of the channel when there is no logo. */
@Composable
internal fun LogoTile(
    logo: String?,
    name: String,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val shape = RoundedCornerShape(corner)
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .background(LiveColors.Tile, shape)
            .border(1.dp, LiveColors.GlassBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
                onError = { failed = true },
            )
        } else {
            Text(initials(name), style = textStyle, fontWeight = FontWeight.ExtraBold, color = LiveColors.Muted, maxLines = 1)
        }
    }
}

internal fun initials(name: String): String {
    val words = name.split(' ', '-', '_', '.', '|', '/').filter { it.isNotBlank() }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
}

/** Drawn in the preview area when nothing is playing (the hole would only show black). */
@Composable
internal fun PreviewPlaceholder(
    origin: DpOffset,
    size: DpSize,
    title: String,
    hint: String?,
    logo: String? = null,
    name: String? = null,
) {
    val shape = RoundedCornerShape(PreviewCorner)
    Column(
        Modifier
            .offset(origin.x, origin.y)
            .size(size.width, size.height)
            .clip(shape)
            .background(PlaceholderBrush, shape)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (name != null) {
            LogoTile(logo, name, Modifier.size(width = 84.dp, height = 52.dp), corner = 12.dp, textStyle = MaterialTheme.typography.titleMedium)
        } else {
            AppIcon(Glyph.TV, LiveColors.Muted, size = 40.dp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = LiveColors.Soft,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = LiveColors.Muted,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

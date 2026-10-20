package app.fluide.player.ui.multiview

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.fluide.player.R
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRow
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.FluideMark
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.components.fluideGlass
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Multi-screen: 2 or 4 live channels at once in a full-screen grid ("1 + 3": one large tile and three
 * small ones). The focused tile has a thick accent ring, the tile whose sound is heard a speaker badge.
 *
 * Remote: OK on a tile takes its sound (on an empty tile: opens the channel picker, on a tile in error:
 * reconnects), long OK or Menu changes its channel, CH+ / CH- zaps inside the tile, 1 to 4 pick a tile.
 * The picker is a glass panel on the right (categories, then a virtualised channel list); Back closes it
 * (handled by the view model), then leaves the multi-screen.
 */
@Composable
fun MultiViewScreen(vm: MainViewModel) {
    val controller = vm.multiView
    val state by controller.state.collectAsStateWithLifecycle()
    val players by controller.players.collectAsStateWithLifecycle()
    val picker by controller.picker.collectAsStateWithLifecycle()
    val notices by controller.notices.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nowSec = rememberMultiNowSec()

    val tileFocus = remember { List(MultiViewController.MAX_TILES) { FocusRequester() } }
    var focusedTile by remember { mutableIntStateOf(-1) }
    val pickerOpen = state.pickerSlot >= 0
    val previousPicker = remember { intArrayOf(-1) }
    val focusAfterPicker = remember { intArrayOf(-1) }

    fun focusTile(index: Int) {
        runCatching { tileFocus[index].requestFocus() }
    }

    // First focus: the tile whose sound is heard.
    LaunchedEffect(state.slots.isNotEmpty()) {
        if (state.slots.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        focusTile(state.audioSlot.coerceIn(0, state.slots.size - 1))
    }
    // The picker closed: back to the tile that was changed.
    LaunchedEffect(state.pickerSlot) {
        val closed = previousPicker[0]
        previousPicker[0] = state.pickerSlot
        if (state.pickerSlot >= 0 || closed < 0) return@LaunchedEffect
        val target = if (focusAfterPicker[0] >= 0) focusAfterPicker[0] else closed
        focusAfterPicker[0] = -1
        withFrameNanos { }
        if (target in state.slots.indices) focusTile(target)
    }
    // Fewer tiles: the focus cannot stay on a tile that is gone.
    LaunchedEffect(state.slots.size) {
        if (focusedTile >= state.slots.size && state.slots.isNotEmpty()) {
            withFrameNanos { }
            focusTile(0)
        }
    }

    val fullScreen: (Long) -> Unit = { channelId ->
        controller.release()
        vm.playChannelFromHome(channelId)
    }

    ScreenSurface {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (pickerOpen || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val digit = tileDigit(event.key)
                    if (digit != null && digit < state.slots.size) {
                        controller.setAudio(digit)
                        focusTile(digit)
                        true
                    } else {
                        false
                    }
                },
        ) {
            val compact = maxWidth < 640.dp
            val panelWidth = minOf(maxWidth, maxOf(360.dp, maxWidth * 0.5f))
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    layout = state.slots.size,
                    featured = state.featured,
                    muted = state.muted,
                    audioName = state.slots.getOrNull(state.audioSlot)?.name,
                    clock24h = settings.clock24h,
                    compact = compact,
                    focusable = !pickerOpen,
                    onBack = { vm.onBack() },
                    onLayout2 = { controller.setLayout(2) },
                    onLayout4 = { controller.setLayout(4) },
                    onFeatured = controller::setFeaturedLayout,
                    onMute = controller::toggleMute,
                )
                BoxWithConstraints(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    val rects = tileRects(state.slots.size, state.featured, maxWidth, maxHeight, TILE_GAP)
                    state.slots.forEachIndexed { index, slot ->
                        val rect = rects.getOrNull(index) ?: return@forEachIndexed
                        key(index) {
                            Tile(
                                slot = slot,
                                player = players.getOrNull(index),
                                rect = rect,
                                audio = index == state.audioSlot,
                                muted = state.muted,
                                editing = index == state.pickerSlot,
                                large = state.slots.size <= 2 || (state.featured && index == 0),
                                focusable = !pickerOpen,
                                nowSec = nowSec,
                                focusRequester = tileFocus[index],
                                onFocused = { focusedTile = index },
                                onClick = {
                                    when {
                                        slot.channelId == 0L -> controller.openPicker(index)
                                        slot.error -> controller.retry(index)
                                        else -> controller.setAudio(index)
                                    }
                                },
                                onChange = { controller.openPicker(index) },
                                onZap = { delta -> controller.zap(index, delta) },
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.multiview_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }

            if (pickerOpen) {
                val slotIndex = state.pickerSlot
                PickerPanel(
                    controller = controller,
                    slotIndex = slotIndex,
                    slot = state.slots.getOrNull(slotIndex),
                    picker = picker,
                    canPromote = state.featured && slotIndex > 0,
                    shownOn = state.slots.filter { it.channelId != 0L }.associate { it.channelId to it.index },
                    nowSec = nowSec,
                    onFullScreen = fullScreen,
                    onPromote = {
                        focusAfterPicker[0] = 0
                        controller.promote(slotIndex)
                        controller.closePicker()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(panelWidth)
                        .fillMaxHeight(),
                )
            }

            notices.firstOrNull()?.let { notice ->
                NoticeBanner(
                    notice = notice,
                    onDismiss = { controller.dismissNotice(notice) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp, start = 24.dp, end = 24.dp)
                        .widthIn(max = 720.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(
    layout: Int,
    featured: Boolean,
    muted: Boolean,
    audioName: String?,
    clock24h: Boolean,
    compact: Boolean,
    focusable: Boolean,
    onBack: () -> Unit,
    onLayout2: () -> Unit,
    onLayout4: () -> Unit,
    onFeatured: () -> Unit,
    onMute: () -> Unit,
) {
    val noFocus = Modifier.focusProperties { canFocus = focusable }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvButton(
            text = stringResource(R.string.multiview_back),
            onClick = onBack,
            modifier = noFocus,
            glyph = Glyph.BACK,
        )
        Spacer(Modifier.width(16.dp))
        FluideMark(Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.multiview_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    when {
                        muted -> stringResource(R.string.multiview_sound_off)
                        audioName != null -> stringResource(R.string.multiview_sound_of, audioName)
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (muted) FluideColors.Warm else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarChip(
                label = if (compact) "2" else stringResource(R.string.multiview_layout_2),
                selected = layout == 2,
                focusable = focusable,
                onClick = onLayout2,
            ) { tint -> LayoutIcon(LayoutKind.TWO, tint) }
            BarChip(
                label = if (compact) "4" else stringResource(R.string.multiview_layout_4),
                selected = layout == 4 && !featured,
                focusable = focusable,
                onClick = onLayout4,
            ) { tint -> LayoutIcon(LayoutKind.GRID, tint) }
            BarChip(
                label = stringResource(R.string.multiview_layout_featured),
                selected = layout == 4 && featured,
                focusable = focusable,
                onClick = onFeatured,
            ) { tint -> LayoutIcon(LayoutKind.FEATURED, tint) }
            BarChip(
                label = if (compact) "" else stringResource(if (muted) R.string.multiview_unmute else R.string.multiview_mute),
                selected = muted,
                focusable = focusable,
                onClick = onMute,
                warn = muted,
            ) { tint -> SpeakerIcon(muted = !muted, tint = tint) }
        }
        if (!compact) {
            Spacer(Modifier.width(16.dp))
            Clock(clock24h, showDate = false)
        }
    }
}

/** Pill of the top bar (layouts, sound): accent gradient while focused, accent outline when selected. */
@Composable
private fun BarChip(
    label: String,
    selected: Boolean,
    focusable: Boolean,
    onClick: () -> Unit,
    warn: Boolean = false,
    icon: @Composable (Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    val accent = if (warn) FluideColors.Warm else FluideColors.Cyan
    val fill: Brush = when {
        focused -> FluideGradient
        selected -> SolidColor(accent.copy(alpha = 0.16f))
        else -> SolidColor(FluideColors.Glass)
    }
    val borderColor = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> accent.copy(alpha = 0.7f)
        else -> FluideColors.GlassBorder
    }
    val content = when {
        focused -> MaterialTheme.colorScheme.onPrimary
        selected -> accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier
            .height(44.dp)
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(fill, shape)
            .border(if (focused) 2.dp else 1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(content)
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = content, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ tiles

private val TILE_GAP = 10.dp
private val TILE_CORNER = 12.dp

/** Thickness of the ring around each video; with [TILE_CORNER] it also hides the square video corners. */
private val TILE_RING = 4.dp

/** Position and size of a tile in the tile area. */
private class TileRect(val x: Dp, val y: Dp, val width: Dp, val height: Dp)

/**
 * 16:9 tiles centred in [width] x [height]: 2 side by side (stacked on a portrait screen), a 2 x 2 grid,
 * or "1 + 3" (the first tile large, the three others in a column on its right, in a row below it on a
 * portrait screen).
 */
private fun tileRects(count: Int, featured: Boolean, width: Dp, height: Dp, gap: Dp): List<TileRect> {
    if (count <= 0) return emptyList()
    val w = width.value
    val h = height.value
    val g = gap.value
    val ratio = 16f / 9f
    fun fit(cw: Float, ch: Float): Pair<Float, Float> =
        if (cw / ch > ratio) Pair(ch * ratio, ch) else Pair(cw, cw / ratio)
    fun rect(x: Float, y: Float, rw: Float, rh: Float) = TileRect(x.dp, y.dp, rw.dp, rh.dp)

    if (count <= 2) {
        val portrait = h > w
        val (tw, th) = if (portrait) fit(w, (h - g) / 2f) else fit((w - g) / 2f, h)
        val totalW = if (portrait) tw else tw * 2 + g
        val totalH = if (portrait) th * 2 + g else th
        val x0 = (w - totalW) / 2f
        val y0 = (h - totalH) / 2f
        return List(count) { i ->
            if (portrait) rect(x0, y0 + i * (th + g), tw, th) else rect(x0 + i * (tw + g), y0, tw, th)
        }
    }
    if (!featured) {
        val (tw, th) = fit((w - g) / 2f, (h - g) / 2f)
        val x0 = (w - (tw * 2 + g)) / 2f
        val y0 = (h - (th * 2 + g)) / 2f
        return List(count) { i -> rect(x0 + (i % 2) * (tw + g), y0 + (i / 2) * (th + g), tw, th) }
    }
    if (h > w) {
        // Portrait: large tile on top, three small ones in a row below.
        val bigW = w
        val bigH = w / ratio
        val smallW = (w - 2 * g) / 3f
        val smallH = smallW / ratio
        val y0 = ((h - (bigH + g + smallH)) / 2f).coerceAtLeast(0f)
        return List(count) { i ->
            if (i == 0) rect(0f, y0, bigW, bigH) else rect((i - 1) * (smallW + g), y0 + bigH + g, smallW, smallH)
        }
    }
    // Landscape: the large tile is exactly as tall as the column of three small ones.
    val small = minOf(((w - g) / ratio - 2 * g) / 4f, (h - 2 * g) / 3f).coerceAtLeast(1f)
    val smallW = small * ratio
    val bigH = small * 3 + g * 2
    val bigW = bigH * ratio
    val x0 = (w - (bigW + g + smallW)) / 2f
    val y0 = (h - bigH) / 2f
    return List(count) { i ->
        if (i == 0) rect(x0, y0, bigW, bigH) else rect(x0 + bigW + g, y0 + (i - 1) * (small + g), smallW, small)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    slot: MultiSlot,
    player: Player?,
    rect: TileRect,
    audio: Boolean,
    muted: Boolean,
    editing: Boolean,
    large: Boolean,
    focusable: Boolean,
    nowSec: Long,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onChange: () -> Unit,
    onZap: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TILE_CORNER)
    val innerShape = RoundedCornerShape(TILE_CORNER - TILE_RING)
    val highlighted = focused || editing
    val accent = FluideColors.Cyan
    // Opaque ring colours: the ring covers the square corners of the video below it.
    val ring: Brush = when {
        highlighted -> FluideGradient
        audio && !muted -> SolidColor(lerp(FluideColors.NavyLight, accent, 0.55f))
        else -> SolidColor(lerp(FluideColors.NavyLight, Color.White, 0.08f))
    }
    val compact = rect.height < 170.dp
    val hasChannel = slot.channelId != 0L
    Box(
        Modifier
            .offset(rect.x, rect.y)
            .size(rect.width, rect.height)
            .drawBehind {
                if (highlighted) {
                    // Soft halo around the focused tile.
                    val spread = 7.dp.toPx()
                    drawRoundRect(
                        color = accent.copy(alpha = 0.22f),
                        topLeft = Offset(-spread, -spread),
                        size = Size(size.width + spread * 2, size.height + spread * 2),
                        cornerRadius = CornerRadius(TILE_CORNER.toPx() + spread),
                    )
                }
            }
            .focusRequester(focusRequester)
            .focusProperties { canFocus = focusable }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onKeyEvent { event ->
                when {
                    event.key == Key.Menu -> {
                        if (event.type == KeyEventType.KeyUp) onChange()
                        true
                    }
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.ChannelUp || event.key == Key.PageUp -> {
                        if (hasChannel) onZap(1)
                        true
                    }
                    event.key == Key.ChannelDown || event.key == Key.PageDown -> {
                        if (hasChannel) onZap(-1)
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onChange,
                onClick = onClick,
            ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(TILE_RING)
                .clip(innerShape)
                .background(FluideColors.Navy, innerShape),
        ) {
            when {
                !hasChannel -> EmptyTile(focused = highlighted, index = slot.index, compact = compact)
                slot.error -> ErrorTile(slot, compact = compact)
                else -> {
                    TileVideo(player, Modifier.fillMaxSize())
                    if (slot.loading) LoadingBadge(slot.attempt, compact, Modifier.align(Alignment.Center))
                    ChannelStrip(slot, nowSec, compact = compact, expanded = highlighted || large, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
            IndexBadge(slot.index, highlighted, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (audio && hasChannel) {
                AudioBadge(muted, compact, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(TILE_RING, ring, shape),
        )
    }
}

/**
 * The video of a tile: Media3 PlayerView without controller. Its SurfaceView is a "media overlay" so
 * that it is always above the (stopped) full-screen surface of the main player and still below the
 * interface drawn over it.
 */
@OptIn(UnstableApi::class)
@Composable
private fun TileVideo(player: Player?, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setEnableComposeSurfaceSyncWorkaround(true)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isFocusable = false
                isFocusableInTouchMode = false
                keepScreenOn = true
                (videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
            }
        },
        modifier = modifier,
        onRelease = { view -> view.player = null },
        update = { view -> if (view.player !== player) view.player = player },
    )
}

/** Logo, number, name and programme on air over a dark gradient at the bottom of the video. */
@Composable
private fun ChannelStrip(slot: MultiSlot, nowSec: Long, compact: Boolean, expanded: Boolean, modifier: Modifier = Modifier) {
    val name = slot.name ?: return
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
            .padding(start = 10.dp, end = 10.dp, top = if (compact) 14.dp else 22.dp, bottom = if (compact) 6.dp else 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(
                slot.logo,
                name,
                Modifier.size(width = if (compact) 34.dp else 46.dp, height = if (compact) 22.dp else 30.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(slot.number?.toString(), name).joinToString("  "),
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val title = slot.nowTitle
                if (title != null && (expanded || !compact)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (slot.nowTitle != null && slot.nowStopSec > slot.nowStartSec && expanded) {
            Spacer(Modifier.height(6.dp))
            ProgressLine(programmeProgress(slot.nowStartSec, slot.nowStopSec, nowSec), Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val accent = FluideGradient
    val track = Color.White.copy(alpha = 0.18f)
    Canvas(modifier.height(3.dp)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(color = track, cornerRadius = radius)
        if (fraction > 0f) drawRoundRect(brush = accent, size = Size(size.width * fraction, size.height), cornerRadius = radius)
    }
}

@Composable
private fun EmptyTile(focused: Boolean, index: Int, compact: Boolean) {
    val accent = FluideColors.Cyan
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(FluideColors.NavyLight, FluideColors.Night)))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(if (compact) 40.dp else 56.dp)
                .clip(CircleShape)
                .background(if (focused) FluideGradient else SolidColor(FluideColors.Glass), CircleShape)
                .border(1.dp, if (focused) Color.Transparent else accent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PlusIcon(if (focused) MaterialTheme.colorScheme.onPrimary else accent, Modifier.size(if (compact) 18.dp else 24.dp))
        }
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Text(
            stringResource(R.string.multiview_empty_title),
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact || focused) {
            Text(
                stringResource(R.string.multiview_screen, index + 1) + " · " + stringResource(R.string.multiview_empty_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ErrorTile(slot: MultiSlot, compact: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(FluideColors.NavyLight, FluideColors.Night)))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AlertIcon(FluideColors.Warm, Modifier.size(if (compact) 24.dp else 34.dp))
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        Text(
            slot.name ?: stringResource(R.string.multiview_error_title),
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (slot.name != null) stringResource(R.string.multiview_error_title) else stringResource(R.string.multiview_error_hint),
            style = MaterialTheme.typography.bodySmall,
            color = FluideColors.Warm,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact && slot.name != null) {
            Text(
                stringResource(R.string.multiview_error_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LoadingBadge(attempt: Int, compact: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(if (compact) 22.dp else 30.dp),
            strokeWidth = 3.dp,
            color = FluideColors.Cyan,
        )
        if (!compact || attempt > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (attempt > 0) {
                    stringResource(R.string.multiview_reconnecting, attempt, MultiViewController.MAX_ATTEMPTS)
                } else {
                    stringResource(R.string.multiview_loading)
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun IndexBadge(index: Int, highlighted: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .size(26.dp)
            .clip(shape)
            .background(if (highlighted) FluideGradient else SolidColor(Color.Black.copy(alpha = 0.55f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            (index + 1).toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimary else Color.White,
            maxLines = 1,
        )
    }
}

/** Speaker badge of the tile whose sound is heard (crossed out when the sound is cut). */
@Composable
private fun AudioBadge(muted: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Row(
        modifier
            .height(28.dp)
            .clip(shape)
            .background(if (muted) SolidColor(FluideColors.Warm) else FluideGradient, shape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakerIcon(muted = muted, tint = onAccent, modifier = Modifier.size(16.dp))
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(if (muted) R.string.multiview_sound_off else R.string.multiview_audio),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = onAccent,
                maxLines = 1,
            )
        }
    }
}

/** Channel logo on a small dark tile; the initials of the channel when there is no logo. */
@Composable
private fun ChannelLogo(logo: String?, name: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.12f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                onError = { failed = true },
            )
        } else {
            Text(
                initialsOf(name),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}

// ------------------------------------------------------------------ channel picker

/**
 * Right-side glass panel: actions on the tile (full screen, clear, make large), the categories and the
 * channels of the selected one (virtualised list, rows loaded by pages). OK on a channel puts it in
 * the tile and closes the panel; Back closes it.
 */
@Composable
private fun PickerPanel(
    controller: MultiViewController,
    slotIndex: Int,
    slot: MultiSlot?,
    picker: MultiPickerState,
    canPromote: Boolean,
    shownOn: Map<Long, Int>,
    nowSec: Long,
    onFullScreen: (Long) -> Unit,
    onPromote: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val groupState = rememberLazyListState()
    val listFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val panelShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)

    LaunchedEffect(slotIndex, picker.version) {
        if (!picker.focusList) {
            listState.scrollToItem(0)
            return@LaunchedEffect
        }
        if (picker.size > 0) {
            listState.scrollToItem((picker.focusIndex - 3).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { listFocus.requestFocus() }
        } else if (picker.groups.isNotEmpty()) {
            withFrameNanos { }
            runCatching { groupFocus.requestFocus() }
        }
    }

    Column(
        modifier
            .clip(panelShape)
            .background(
                Brush.horizontalGradient(
                    listOf(FluideColors.NavyLight.copy(alpha = 0.97f), FluideColors.Navy.copy(alpha = 0.98f)),
                ),
                panelShape,
            )
            .border(1.dp, FluideColors.GlassBorder, panelShape)
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 12.dp),
    ) {
        // Header: which tile, and what it shows now.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(FluideGradient, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    stringResource(R.string.multiview_screen, slotIndex + 1),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.multiview_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val current = slot?.name
                if (current != null) {
                    Text(
                        stringResource(R.string.multiview_picker_current, current),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val channelId = slot?.channelId ?: 0L
            if (channelId != 0L) {
                TvButton(stringResource(R.string.multiview_fullscreen), onClick = { onFullScreen(channelId) }, glyph = Glyph.PLAY)
            }
            if (canPromote && channelId != 0L) {
                TvButton(stringResource(R.string.multiview_promote), onClick = onPromote, glyph = Glyph.GRID)
            }
            if (channelId != 0L) {
                TvButton(
                    stringResource(R.string.multiview_clear),
                    onClick = {
                        controller.clear(slotIndex)
                        controller.closePicker()
                    },
                )
            }
            TvButton(stringResource(R.string.multiview_close), onClick = controller::closePicker, glyph = Glyph.BACK)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // Categories (the selected one takes the focus when the list is empty).
            val groupTarget = picker.groups.indexOfFirst { it.filter == picker.selected }.coerceAtLeast(0)
            LazyColumn(
                state = groupState,
                modifier = Modifier
                    .weight(0.4f)
                    .fillMaxHeight()
                    .fluideGlass(RoundedCornerShape(16.dp)),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                itemsIndexed(picker.groups, key = { _, group -> group.filter.toString() }) { index, group ->
                    val selected = group.filter == picker.selected
                    FocusableRow(
                        onClick = { controller.selectPickerGroup(group.filter) },
                        selected = selected,
                        focusRequester = if (index == groupTarget) groupFocus else null,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                    ) { focused ->
                        Text(
                            groupLabel(group),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            group.count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (focused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            // Channels of the category, rows loaded page by page.
            Box(Modifier.weight(0.6f).fillMaxHeight()) {
                if (picker.size == 0) {
                    if (picker.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center).size(30.dp),
                            strokeWidth = 3.dp,
                            color = FluideColors.Cyan,
                        )
                    } else {
                        Text(
                            stringResource(R.string.multiview_picker_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        items(
                            count = picker.size,
                            key = { index -> controller.pickerIdAt(index) ?: (-index.toLong() - 1) },
                        ) { index ->
                            val row = controller.pickerRows.rowAt(index)
                            PickerChannel(
                                row = row,
                                current = row != null && row.id == slot?.channelId,
                                shownOn = row?.let { shownOn[it.id] },
                                nowSec = nowSec,
                                focusRequester = if (index == picker.focusIndex) listFocus else null,
                                onClick = {
                                    if (row != null) {
                                        controller.assign(slotIndex, row.id)
                                        controller.closePicker()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerChannel(
    row: ChannelRow?,
    current: Boolean,
    shownOn: Int?,
    nowSec: Long,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    FocusableRow(
        onClick = onClick,
        focusRequester = focusRequester,
        selected = current,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) { focused ->
        Text(
            row?.number?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = if (focused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp),
        )
        Spacer(Modifier.width(8.dp))
        ChannelLogo(row?.logo, row?.name.orEmpty(), Modifier.size(width = 46.dp, height = 30.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row?.name.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val title = row?.nowTitle
            if (row != null && title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (focused) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.nowStopSec > row.nowStartSec) {
                    Spacer(Modifier.height(3.dp))
                    ProgressLine(programmeProgress(row.nowStartSec, row.nowStopSec, nowSec), Modifier.fillMaxWidth())
                }
            }
        }
        if (shownOn != null) {
            Spacer(Modifier.width(8.dp))
            // Already on screen: picking it moves it to this tile.
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) SolidColor(Color.White.copy(alpha = 0.22f)) else FluideGradient, RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    (shownOn + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (focused) Color.White else MaterialTheme.colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun groupLabel(group: MultiPickerGroup): String = when (val filter = group.filter) {
    ChannelFilter.All -> stringResource(R.string.guide_all_channels)
    ChannelFilter.Favorites -> stringResource(R.string.guide_favorites)
    ChannelFilter.Recent -> stringResource(R.string.guide_recent)
    ChannelFilter.Catchup -> stringResource(R.string.guide_catchup)
    is ChannelFilter.Group -> filter.name ?: stringResource(R.string.guide_no_group)
    is ChannelFilter.Search -> filter.query
}

// ------------------------------------------------------------------ warnings

/** One-time warning (decoders, connections) at the top of the screen, hidden after a few seconds. */
@Composable
private fun NoticeBanner(notice: MultiViewNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(notice.id) {
        delay(NOTICE_MS)
        onDismiss()
    }
    val text = when (notice.kind) {
        MultiViewNotice.Kind.DECODERS -> pluralStringResource(R.plurals.multiview_warn_decoders, notice.count, notice.count)
        MultiViewNotice.Kind.CONNECTIONS -> pluralStringResource(R.plurals.multiview_warn_connections, notice.count, notice.count)
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .clip(shape)
            .background(FluideColors.Navy.copy(alpha = 0.95f), shape)
            .border(1.dp, FluideColors.Warm.copy(alpha = 0.6f), shape)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlertIcon(FluideColors.Warm, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ small drawn icons

private enum class LayoutKind { TWO, GRID, FEATURED }

/** Miniature of a layout: two cells, a 2 x 2 grid, or one large cell and three small ones. */
@Composable
private fun LayoutIcon(kind: LayoutKind, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        val w = size.width
        val gap = w * 0.1f
        val top = w * 0.18f
        val height = w * 0.64f
        val radius = CornerRadius(w * 0.06f)
        fun cell(x: Float, y: Float, cw: Float, ch: Float) =
            drawRoundRect(color = tint, topLeft = Offset(x, y), size = Size(cw, ch), cornerRadius = radius)
        when (kind) {
            LayoutKind.TWO -> {
                val cw = (w - gap) / 2f
                cell(0f, top, cw, height)
                cell(cw + gap, top, cw, height)
            }
            LayoutKind.GRID -> {
                val cw = (w - gap) / 2f
                val ch = (height - gap) / 2f
                for (i in 0 until 4) cell((i % 2) * (cw + gap), top + (i / 2) * (ch + gap), cw, ch)
            }
            LayoutKind.FEATURED -> {
                val small = (height - 2 * gap) / 3f
                val bigW = w - gap - small * 1.3f
                cell(0f, top, bigW, height)
                for (i in 0 until 3) cell(bigW + gap, top + i * (small + gap), small * 1.3f, small)
            }
        }
    }
}

/** Speaker with sound waves ([muted] false) or crossed out. */
@Composable
private fun SpeakerIcon(muted: Boolean, tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    Canvas(modifier) {
        val w = size.width
        val body = Path().apply {
            moveTo(w * 0.10f, w * 0.38f)
            lineTo(w * 0.28f, w * 0.38f)
            lineTo(w * 0.50f, w * 0.18f)
            lineTo(w * 0.50f, w * 0.82f)
            lineTo(w * 0.28f, w * 0.62f)
            lineTo(w * 0.10f, w * 0.62f)
            close()
        }
        drawPath(body, tint)
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (muted) {
            drawLine(tint, Offset(w * 0.64f, w * 0.36f), Offset(w * 0.92f, w * 0.64f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.92f, w * 0.36f), Offset(w * 0.64f, w * 0.64f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        } else {
            drawArc(
                color = tint,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(w * 0.40f, w * 0.30f),
                size = Size(w * 0.40f, w * 0.40f),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(w * 0.34f, w * 0.14f),
                size = Size(w * 0.72f, w * 0.72f),
                style = stroke,
            )
        }
    }
}

@Composable
private fun PlusIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val stroke = w * 0.14f
        drawLine(tint, Offset(w / 2f, w * 0.12f), Offset(w / 2f, w * 0.88f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.12f, w / 2f), Offset(w * 0.88f, w / 2f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/** Exclamation mark in a circle. */
@Composable
private fun AlertIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        drawCircle(tint, radius = w * 0.44f, style = Stroke(width = w * 0.09f))
        drawLine(tint, Offset(w / 2f, w * 0.28f), Offset(w / 2f, w * 0.56f), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        drawCircle(tint, radius = w * 0.06f, center = Offset(w / 2f, w * 0.72f))
    }
}

// ------------------------------------------------------------------ helpers

private const val NOTICE_MS = 8_000L

/** Current time (epoch seconds), refreshed every 30 s for the programme progress bars. */
@Composable
private fun rememberMultiNowSec(): Long {
    val now = produceState(System.currentTimeMillis() / 1_000) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis() / 1_000
        }
    }
    return now.value
}

private fun programmeProgress(startSec: Long, stopSec: Long, nowSec: Long): Float {
    val span = (stopSec - startSec).coerceAtLeast(1)
    return ((nowSec - startSec).toFloat() / span).coerceIn(0f, 1f)
}

private fun initialsOf(name: String): String {
    val words = name.split(' ', '-', '_', '.', '|', '/').filter { it.isNotBlank() }
    return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" }
}

/** Keys 1 to 4 (top row or keypad): index of the tile, null for any other key. */
private fun tileDigit(key: Key): Int? = when (key) {
    Key.One, Key.NumPad1 -> 0
    Key.Two, Key.NumPad2 -> 1
    Key.Three, Key.NumPad3 -> 2
    Key.Four, Key.NumPad4 -> 3
    else -> null
}

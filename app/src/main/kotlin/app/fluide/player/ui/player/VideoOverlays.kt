package app.fluide.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import app.fluide.player.AspectMode
import app.fluide.player.R
import app.fluide.player.playback.PlaybackPhase
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.PlayerMode
import app.fluide.player.ui.UpNext
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.formatClock
import app.fluide.player.ui.media.openExternal
import app.fluide.player.ui.theme.blend
import app.fluide.player.ui.theme.themed
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Scrim under the video controls: transparent at the top, the deep background of the theme at the bottom. */
private val ControlsScrim: Brush by themed {
    Brush.verticalGradient(
        listOf(
            Color.Transparent,
            it.navy.copy(alpha = 0.6f),
            blend(it.navy, Color.Black, 0.08f).copy(alpha = 0.9f),
            blend(it.navy, Color.Black, 0.22f).copy(alpha = 0.96f),
        ),
    )
}
private val SeekTrack = Color(0x2EFFFFFF)
private val SeekBrush: Brush by themed { Brush.horizontalGradient(listOf(it.blue, it.cyan)) }
private val RoundIdle = SolidColor(Color(0x24FFFFFF))
private val DialogShape = RoundedCornerShape(24.dp)
private val DialogBrush: Brush by themed {
    Brush.verticalGradient(
        listOf(
            it.dialogTop.copy(alpha = 0.96f),
            it.dialogBottom.copy(alpha = 0.96f),
            blend(it.night, it.violet, 0.04f).copy(alpha = 0.96f),
        ),
    )
}
private val PillShape = RoundedCornerShape(24.dp)
private val SelectedChoice: Brush by themed {
    Brush.horizontalGradient(listOf(it.blue.copy(alpha = 0.34f), it.cyan.copy(alpha = 0.14f)))
}

/**
 * Title, seek bar and buttons of the video player (movies, episodes, recordings). The remote drives it
 * through the view model (left / right: seek, OK: pause, down: options); the buttons and the seek bar
 * (tap or drag) are for touch screens.
 */
@Composable
fun VideoControls(
    vm: MainViewModel,
    visible: Boolean,
    title: String?,
    subtitle: String?,
    phase: PlaybackPhase,
    mode: PlayerMode,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 6 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 6 }),
        modifier = modifier,
    ) {
        val settings by vm.settings.collectAsStateWithLifecycle()
        var position by remember { mutableLongStateOf(vm.position()) }
        var duration by remember { mutableLongStateOf(vm.duration()) }
        // Playback speed chosen in the options (1 = normal).
        var speed by remember { mutableFloatStateOf(vm.playbackSpeed()) }
        // Fraction under the finger while the seek bar is dragged, null otherwise.
        var scrub by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                position = vm.position()
                duration = vm.duration()
                speed = vm.playbackSpeed()
                delay(500)
            }
        }
        val numbers = remember { NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 2 } }
        val timeFormat = rememberClockFormat(settings.clock24h)
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val scrubbing = scrub
        val shownFraction = scrubbing ?: fraction
        val shownPosition = if (scrubbing != null) (scrubbing * duration).toLong() else position

        Column(
            Modifier
                .fillMaxWidth()
                .background(ControlsScrim)
                .padding(start = 48.dp, end = 48.dp, top = 56.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    if (mode == PlayerMode.CATCHUP) {
                        OverlayTag(
                            stringResource(R.string.epg_replay_badge),
                            SolidColor(FluideColors.Cyan.copy(alpha = 0.16f)),
                            textColor = FluideColors.Cyan,
                            border = FluideColors.Cyan.copy(alpha = 0.5f),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    title?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = OverlayColors.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (speed != 1f) {
                    OverlayTag(
                        stringResource(R.string.player_speed_value, numbers.format(speed.toDouble())),
                        SolidColor(FluideColors.Cyan.copy(alpha = 0.16f)),
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
                        textColor = FluideColors.Cyan,
                        border = FluideColors.Cyan.copy(alpha = 0.5f),
                    )
                }
                if (duration > 0) {
                    // Real time left: faster or slower than the video's own clock when the speed is changed.
                    val leftMs = ((duration - shownPosition).coerceAtLeast(0) / speed.coerceAtLeast(0.1f)).toLong()
                    val endsAt = System.currentTimeMillis() + leftMs
                    Text(
                        stringResource(R.string.live_ends_at, timeFormat.format(Date(endsAt))),
                        style = MaterialTheme.typography.labelLarge,
                        color = OverlayColors.Muted,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            // Times, seek bar and transport buttons stay left to right in every language (media
            // timelines are not mirrored).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatClock(shownPosition),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (duration > 0) "-" + formatClock(duration - shownPosition) else "--:--",
                            style = MaterialTheme.typography.titleSmall,
                            color = OverlayColors.Muted,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    SeekBar(
                        fraction = shownFraction,
                        enabled = duration > 0,
                        scrubbing = scrubbing != null,
                        onScrub = { scrub = it },
                        onSeek = { f ->
                            val targetMs = (f * duration).toLong()
                            scrub = null
                            position = targetMs
                            vm.seekTo(targetMs)
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth()) {
                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(Glyph.REWIND, onClick = { vm.seekTo(position - 10_000) })
                            Spacer(Modifier.width(22.dp))
                            PlayButton(paused = phase == PlaybackPhase.PAUSED, onClick = vm::togglePause)
                            Spacer(Modifier.width(22.dp))
                            RoundButton(Glyph.FORWARD, onClick = { vm.seekTo(position + 10_000) })
                        }
                        RoundButton(Glyph.LIST, Modifier.align(Alignment.CenterEnd), onClick = vm::openOptions)
                    }
                }
            }
            if (mode != PlayerMode.LIVE) {
                Text(
                    stringResource(R.string.live_controls_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = OverlayColors.Dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
        }
    }
}

/** Chunky seek bar with a round thumb; tap or drag to seek (touch screens). */
@Composable
private fun SeekBar(
    fraction: Float,
    enabled: Boolean,
    scrubbing: Boolean,
    onScrub: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
) {
    val scrubNow by rememberUpdatedState(onScrub)
    val seekNow by rememberUpdatedState(onSeek)
    val thumbScale by animateFloatAsState(if (scrubbing) 1.3f else 1f, label = "seekThumb")
    Box(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { offset -> seekNow(fractionAt(offset.x, size.width)) })
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        last = fractionAt(offset.x, size.width)
                        scrubNow(last)
                    },
                    onDragEnd = {
                        scrubNow(null)
                        seekNow(last)
                    },
                    onDragCancel = { scrubNow(null) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        last = fractionAt(change.position.x, size.width)
                        scrubNow(last)
                    },
                )
            }
            .drawBehind {
                val track = 8.dp.toPx()
                val y = size.height / 2f
                val radius = CornerRadius(track / 2f)
                drawRoundRect(
                    color = SeekTrack,
                    topLeft = Offset(0f, y - track / 2f),
                    size = Size(size.width, track),
                    cornerRadius = radius,
                )
                val x = size.width * fraction.coerceIn(0f, 1f)
                if (x > 0f) {
                    drawRoundRect(
                        brush = SeekBrush,
                        topLeft = Offset(0f, y - track / 2f),
                        size = Size(x, track),
                        cornerRadius = radius,
                    )
                }
                if (enabled) {
                    drawCircle(color = FluideColors.Cyan.copy(alpha = 0.28f), radius = 13.dp.toPx() * thumbScale, center = Offset(x, y))
                    drawCircle(color = Color.White, radius = 8.dp.toPx() * thumbScale, center = Offset(x, y))
                }
            },
    )
}

private fun fractionAt(x: Float, width: Int): Float = if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

@Composable
private fun RoundButton(glyph: Glyph, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "roundButton")
    val fill: Brush = if (focused) FluideGradient else RoundIdle
    Box(
        modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0x33FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(glyph, if (focused) FluideColors.OnAccent else Color.White, size = 22.dp)
    }
}

/** Big round play / pause button, filled with the fluid gradient and a soft glow. */
@Composable
private fun PlayButton(paused: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "playButton")
    Box(
        Modifier
            .size(68.dp)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = 14.dp.toPx()
                ambientShadowColor = FluideColors.Cyan
                spotShadowColor = FluideColors.Blue
                shape = CircleShape
                clip = true
            }
            .background(FluideGradient, CircleShape)
            .border(2.dp, if (focused) Color.White else Color(0x40FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(if (paused) Glyph.PLAY else Glyph.PAUSE, FluideColors.OnAccent, size = 30.dp)
    }
}

/** "Next episode in 8 s" with a countdown ring: OK (or a tap) starts it now, Back returns to the series. */
@Composable
fun UpNextCard(upNext: UpNext, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    // The countdown starts at the first value shown; the ring empties with it.
    val total = remember { upNext.secondsLeft.coerceAtLeast(1) }
    val target = (upNext.secondsLeft.toFloat() / total).coerceIn(0f, 1f)
    val ring by animateFloatAsState(target, animationSpec = tween(durationMillis = 900, easing = LinearEasing), label = "upNextRing")
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .padding(40.dp)
            .widthIn(max = 460.dp)
            .clip(shape)
            .background(OverlayBrush, shape)
            .border(1.dp, OverlayColors.Border, shape)
            .clickable(onClick = onPlay)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(60.dp)
                .drawBehind {
                    val stroke = 4.dp.toPx()
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
                    drawArc(
                        color = Color(0x26FFFFFF),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = stroke),
                    )
                    drawArc(
                        brush = FluideGradient,
                        startAngle = -90f,
                        sweepAngle = 360f * ring,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                upNext.secondsLeft.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.up_next, upNext.secondsLeft),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = FluideColors.Cyan,
                letterSpacing = 0.4.sp,
                maxLines = 1,
            )
            Text(
                upNext.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Glyph.PLAY, OverlayColors.Muted, size = 12.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.up_next_now), style = MaterialTheme.typography.bodySmall, color = OverlayColors.Muted)
            }
        }
    }
}

/**
 * Audio and subtitle tracks, subtitle size, picture format, playback speed (videos), sleep timer,
 * media information, other player: a glass panel.
 */
@Composable
fun OptionsDialog(vm: MainViewModel, aspect: AspectMode, statsVisible: Boolean) {
    val context = LocalContext.current
    val audio = remember { vm.tracks(C.TRACK_TYPE_AUDIO) }
    val text = remember { vm.tracks(C.TRACK_TYPE_TEXT) }
    val subtitlesOff = remember { vm.subtitlesOff() }
    val first = remember { FocusRequester() }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // The speed only applies to videos (movies, episodes, recordings), never to a live channel.
    val video = ui.playerMode != PlayerMode.LIVE
    var speed by remember { mutableFloatStateOf(vm.playbackSpeed()) }
    val numbers = remember { NumberFormat.getNumberInstance(Locale.getDefault()).apply { maximumFractionDigits = 2 } }
    val sleepEndsAt by vm.sleepTimerEndsAt.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleepEndsAt) {
        while (sleepEndsAt > 0L) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val sleepMinutes = SleepTimerChoice.current(sleepEndsAt, SLEEP_OPTIONS, now)
    Dialog(onDismissRequest = vm::closeOptions, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(24.dp)
                .widthIn(min = 380.dp, max = 540.dp)
                .heightIn(max = 540.dp)
                .clip(DialogShape)
                .background(DialogBrush, DialogShape)
                .border(1.dp, OverlayColors.Border, DialogShape),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(FluideGradient, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(Glyph.SETTINGS, FluideColors.OnAccent, size = 20.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.options_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        stringResource(R.string.live_options_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = OverlayColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item { SectionHeader(stringResource(R.string.options_audio)) }
                if (audio.isEmpty()) item { Hint(stringResource(R.string.options_none)) }
                audio.forEachIndexed { i, option ->
                    item {
                        OptionRow(option.label, option.selected, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                            vm.selectTrack(C.TRACK_TYPE_AUDIO, option)
                            vm.closeOptions()
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.options_subtitles)) }
                item {
                    OptionRow(
                        stringResource(R.string.options_subtitles_off),
                        subtitlesOff || text.none { it.selected },
                        if (audio.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    ) {
                        vm.selectTrack(C.TRACK_TYPE_TEXT, null)
                        vm.closeOptions()
                    }
                }
                text.forEach { option ->
                    item {
                        OptionRow(option.label, option.selected && !subtitlesOff) {
                            vm.selectTrack(C.TRACK_TYPE_TEXT, option)
                            vm.closeOptions()
                        }
                    }
                }
                item { SectionHeader(stringResource(R.string.player_subtitle_size)) }
                item {
                    val current = nearest(settings.subtitleScale, SUBTITLE_SCALES.map { it.first })
                    PillRows(SUBTITLE_SCALES, columns = 2) { (scale, label) ->
                        ChoicePill(
                            label = stringResource(label),
                            selected = scale == current,
                            onClick = { vm.updateSettings { it.copy(subtitleScale = scale) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { SectionHeader(stringResource(R.string.options_aspect)) }
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (mode in AspectMode.entries) {
                            ChoicePill(
                                label = stringResource(
                                    when (mode) {
                                        AspectMode.FIT -> R.string.aspect_fit
                                        AspectMode.FILL -> R.string.aspect_fill
                                        AspectMode.ZOOM -> R.string.aspect_zoom
                                    },
                                ),
                                selected = mode == aspect,
                                onClick = { vm.updateSettings { it.copy(aspectMode = mode) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (video) {
                    item { SectionHeader(stringResource(R.string.player_speed)) }
                    item {
                        val current = nearest(speed, PLAYBACK_SPEEDS)
                        PillRows(PLAYBACK_SPEEDS, columns = 3) { value ->
                            ChoicePill(
                                label = stringResource(R.string.player_speed_value, numbers.format(value.toDouble())),
                                selected = value == current,
                                onClick = {
                                    vm.setPlaybackSpeed(value)
                                    speed = vm.playbackSpeed()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth()) {
                        SectionHeader(stringResource(R.string.player_sleep))
                        if (sleepEndsAt > 0L) {
                            Hint(stringResource(R.string.player_sleep_left, SleepTimerChoice.minutesLeft(sleepEndsAt, now)))
                        }
                    }
                }
                item {
                    PillRows(listOf(0) + SLEEP_OPTIONS, columns = 3) { minutes ->
                        ChoicePill(
                            label = if (minutes == 0) {
                                stringResource(R.string.player_sleep_off)
                            } else {
                                stringResource(R.string.player_sleep_minutes, minutes)
                            },
                            selected = minutes == sleepMinutes,
                            onClick = {
                                SleepTimerChoice.set(vm, minutes)
                                now = System.currentTimeMillis()
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { SectionHeader(stringResource(R.string.live_options_more)) }
                item {
                    ToggleRow(stringResource(R.string.options_stats), statsVisible) {
                        vm.updateSettings { it.copy(showStats = !it.showStats) }
                    }
                }
                item {
                    ActionRow(Glyph.PLAY, stringResource(R.string.options_external)) {
                        val url = vm.currentUrl()
                        vm.closeOptions()
                        if (url != null) openExternal(context, url, "", vm)
                    }
                }
            }
        }
        // In the dialog's own window: the rows exist once its first frames are laid out.
        LaunchedEffect(Unit) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}

/** Subtitle sizes offered (scale of the default size) and their names. */
private val SUBTITLE_SCALES: List<Pair<Float, Int>> = listOf(
    0.85f to R.string.player_subtitle_small,
    1f to R.string.player_subtitle_normal,
    1.3f to R.string.player_subtitle_large,
    1.6f to R.string.player_subtitle_huge,
)

/** Playback speeds offered for videos. */
private val PLAYBACK_SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Sleep timer durations offered in the player (minutes; "off" comes first). */
private val SLEEP_OPTIONS: List<Int> = listOf(15, 30, 60, 90, 120)

/** The value of [options] closest to [value]. */
private fun nearest(value: Float, options: List<Float>): Float = options.minBy { abs(it - value) }

/**
 * The sleep timer duration last chosen: the view model only exposes when the timer ends. Shared by
 * the player options and the settings so both show the same choice.
 */
internal object SleepTimerChoice {
    private var minutes = 0
    private var endsAt = 0L

    /** Starts (or stops, [value] 0) the sleep timer and remembers the choice. */
    fun set(vm: MainViewModel, value: Int) {
        vm.setSleepTimer(value)
        minutes = value
        endsAt = vm.sleepTimerEndsAt.value
    }

    /** Whole minutes left (rounded up) until [endsAt] (epoch ms). */
    fun minutesLeft(endsAt: Long, now: Long): Int = (((endsAt - now).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    /**
     * Minutes chosen for the timer that ends at [endsAt] (0 when it is off). For a timer started
     * elsewhere, the smallest of [options] that covers the time left.
     */
    fun current(endsAt: Long, options: List<Int>, now: Long): Int {
        if (endsAt <= 0L) return 0
        if (endsAt == this.endsAt && minutes > 0) return minutes
        val left = minutesLeft(endsAt, now)
        return options.firstOrNull { it >= left } ?: options.last()
    }
}

/** [items] as pills, [columns] per row (every pill gets the same width; short last row padded). */
@Composable
private fun <T> PillRows(items: List<T>, columns: Int, pill: @Composable RowScope.(T) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (item in row) pill(item)
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 14.dp, height = 3.dp)
                .background(FluideGradient, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = FluideColors.Cyan,
            letterSpacing = 0.6.sp,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = OverlayColors.Muted,
        modifier = Modifier.padding(start = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth().height(48.dp)) { focused ->
        Spacer(Modifier.width(4.dp))
        RadioMark(selected = selected, focused = focused)
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (focused || selected) Color.White else OverlayColors.Soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RadioMark(selected: Boolean, focused: Boolean) {
    val ring = when {
        focused -> Color.White
        selected -> FluideColors.Cyan
        else -> Color(0x66FFFFFF)
    }
    Box(
        Modifier
            .size(20.dp)
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(if (focused) SolidColor(Color.White) else FluideGradient, CircleShape),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) { focused ->
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) Color.White else OverlayColors.Soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        Spacer(Modifier.width(12.dp))
        SwitchMark(checked)
    }
}

/** On / off switch drawn like the rest of the app (gradient track when on). */
@Composable
private fun SwitchMark(checked: Boolean) {
    val knob by animateFloatAsState(if (checked) 1f else 0f, label = "switchKnob")
    val shape = RoundedCornerShape(11.dp)
    val track: Brush = if (checked) FluideGradient else SolidColor(Color(0x33FFFFFF))
    Box(
        Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(shape)
            .background(track, shape)
            .drawBehind {
                val inset = 3.dp.toPx()
                val radius = size.height / 2f - inset
                val start = inset + radius
                val end = size.width - inset - radius
                drawCircle(color = Color.White, radius = radius, center = Offset(start + (end - start) * knob, size.height / 2f))
            },
    )
}

@Composable
private fun ActionRow(glyph: Glyph, label: String, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp)) { focused ->
        Spacer(Modifier.width(2.dp))
        AppIcon(glyph, if (focused) Color.White else FluideColors.Cyan, size = 18.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) Color.White else OverlayColors.Soft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Picture format choice: a pill, gradient while focused, tinted when selected. */
@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "choicePill")
    val fill: Brush = when {
        focused -> FluideGradient
        selected -> SelectedChoice
        else -> SolidColor(Color(0x12FFFFFF))
    }
    val border = when {
        focused -> Color.White
        selected -> FluideColors.Cyan.copy(alpha = 0.55f)
        else -> OverlayColors.Border
    }
    Box(
        modifier
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(PillShape)
            .background(fill, PillShape)
            .border(if (focused) 2.dp else 1.dp, border, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                focused -> FluideColors.OnAccent
                selected -> Color.White
                else -> OverlayColors.Soft
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

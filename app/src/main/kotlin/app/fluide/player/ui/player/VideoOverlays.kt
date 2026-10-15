package app.fluide.player.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import app.fluide.player.AspectMode
import app.fluide.player.R
import app.fluide.player.playback.PlaybackPhase
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.PlayerMode
import app.fluide.player.ui.UpNext
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.formatClock
import app.fluide.player.ui.media.openExternal
import kotlinx.coroutines.delay

/** Title, seek bar and (for touch screens) buttons of the video player. */
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
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        var position by remember { mutableLongStateOf(vm.position()) }
        var duration by remember { mutableLongStateOf(vm.duration()) }
        LaunchedEffect(Unit) {
            while (true) {
                position = vm.position()
                duration = vm.duration()
                delay(500)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(horizontal = 48.dp, vertical = 28.dp),
        ) {
            title?.let { Text(it, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            subtitle?.let { Text(it, color = Color(0xFFD7DCEA), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(14.dp))
            val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(position), color = Color.White)
                Spacer(Modifier.weight(1f))
                RoundButton(Glyph.REWIND) { vm.seekTo(position - 10_000) }
                Spacer(Modifier.width(14.dp))
                RoundButton(if (phase == PlaybackPhase.PAUSED) Glyph.PLAY else Glyph.PAUSE, big = true, onClick = vm::togglePause)
                Spacer(Modifier.width(14.dp))
                RoundButton(Glyph.FORWARD) { vm.seekTo(position + 10_000) }
                Spacer(Modifier.width(14.dp))
                RoundButton(Glyph.LIST, onClick = vm::openOptions)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) formatClock(duration) else "--:--", color = Color.White)
            }
            if (mode == PlayerMode.VOD) {
                Text(
                    "◀ ▶ : ±10 s   ·   OK : pause   ·   ▼ : options",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9AA3BA),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun RoundButton(glyph: Glyph, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (big) 56.dp else 44.dp)
            .clip(CircleShape)
            .background(Color(0x33FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(glyph, Color.White, size = if (big) 28.dp else 22.dp)
    }
}

/** "Next episode in 8 s": OK starts it now, Back returns to the series. */
@Composable
fun UpNextCard(upNext: UpNext, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(40.dp)
            .widthIn(max = 420.dp)
            .background(Color(0xE60B1020), RoundedCornerShape(16.dp))
            .clickable(onClick = onPlay)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.up_next, upNext.secondsLeft), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text(upNext.title, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.up_next_now), style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA3BA))
    }
}

/** Audio and subtitle tracks, picture format, media information, other player. */
@Composable
fun OptionsDialog(vm: MainViewModel, aspect: AspectMode, statsVisible: Boolean) {
    val context = LocalContext.current
    val audio = remember { vm.tracks(C.TRACK_TYPE_AUDIO) }
    val text = remember { vm.tracks(C.TRACK_TYPE_TEXT) }
    val subtitlesOff = remember { vm.subtitlesOff() }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = vm::closeOptions) {
        LazyColumn(
            Modifier
                .widthIn(min = 360.dp, max = 520.dp)
                .heightIn(max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Header(stringResource(R.string.options_title), big = true) }
            item { Header(stringResource(R.string.options_audio)) }
            if (audio.isEmpty()) item { Hint(stringResource(R.string.options_none)) }
            audio.forEachIndexed { i, option ->
                item {
                    OptionRow(option.label, option.selected, Modifier.then(if (i == 0) Modifier.focusRequester(first) else Modifier)) {
                        vm.selectTrack(C.TRACK_TYPE_AUDIO, option)
                        vm.closeOptions()
                    }
                }
            }
            item { Header(stringResource(R.string.options_subtitles)) }
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
            item { Header(stringResource(R.string.options_aspect)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in AspectMode.entries) {
                        FocusableRow(
                            onClick = { vm.updateSettings { it.copy(aspectMode = mode) } },
                            selected = mode == aspect,
                            modifier = Modifier.height(44.dp),
                        ) {
                            Text(
                                stringResource(
                                    when (mode) {
                                        AspectMode.FIT -> R.string.aspect_fit
                                        AspectMode.FILL -> R.string.aspect_fill
                                        AspectMode.ZOOM -> R.string.aspect_zoom
                                    },
                                ),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item {
                OptionRow(stringResource(R.string.options_stats), statsVisible) {
                    vm.updateSettings { it.copy(showStats = !it.showStats) }
                }
            }
            item {
                OptionRow(stringResource(R.string.options_external), false) {
                    val url = vm.currentUrl()
                    vm.closeOptions()
                    if (url != null) openExternal(context, url, "", vm)
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun Header(text: String, big: Boolean = false) {
    Text(
        text,
        style = if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
        color = if (big) Color.White else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = if (big) 0.dp else 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusableRow(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth().height(46.dp)) {
        Text(if (selected) "●" else "○", color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

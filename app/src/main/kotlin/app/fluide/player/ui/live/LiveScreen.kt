package app.fluide.player.ui.live

import android.text.format.DateFormat
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
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
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.TvButton
import coil.compose.AsyncImage
import java.util.Date

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

/**
 * Live TV, laid out like provider apps: categories, channels, and on the right the channel playing
 * (preview) above the programmes of the focused channel. The screen background is painted around the
 * preview so that the video, drawn below the UI, shows through.
 */
@Composable
fun LiveScreen(vm: MainViewModel, guide: GuideState, playingId: Long) {
    val panel by vm.livePanel.collectAsStateWithLifecycle()
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
        Box(Modifier.fillMaxSize().previewHole(origin, size)) {
            Row(Modifier.fillMaxSize()) {
                GroupColumn(
                    groups = guide.groups,
                    selected = guide.selected,
                    onSelect = vm::selectFilter,
                    onHome = vm::openHome,
                    modifier = Modifier.weight(LiveLayout.GROUPS).fillMaxHeight(),
                )
                Column(
                    Modifier
                        .weight(LiveLayout.CHANNELS)
                        .fillMaxHeight()
                        .padding(vertical = 20.dp, horizontal = 8.dp),
                ) {
                    OutlinedTextField(
                        value = guide.query,
                        onValueChange = vm::search,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.guide_search_hint)) },
                        leadingIcon = { AppIcon(Glyph.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    if (guide.size == 0) {
                        Text(stringResource(R.string.guide_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(count = guide.size, key = { index -> vm.pager.idAt(index) ?: (-index.toLong() - 1) }) { index ->
                                val row = vm.pager.rowAt(index)
                                ChannelItem(
                                    row = row,
                                    playing = row != null && row.id == playingId,
                                    focusRequester = if (index == guide.focusIndex) focusRequester else null,
                                    onClick = { vm.playFromGuide(index) },
                                    onFavorite = { vm.toggleFavorite(index) },
                                    onFocused = { vm.focusChannel(index) },
                                )
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f - LiveLayout.GROUPS - LiveLayout.CHANNELS).fillMaxHeight().padding(start = 8.dp, end = LiveLayout.margin)) {
                    Spacer(Modifier.height(origin.y + size.height + 12.dp))
                    ChannelPanel(panel, playingId, vm)
                }
            }
        }
    }
}

@Composable
private fun GroupColumn(
    groups: List<GuideGroup>,
    selected: ChannelFilter,
    onSelect: (ChannelFilter) -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(vertical = 20.dp, horizontal = 12.dp)) {
        FocusableRow(onClick = onHome, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            AppIcon(Glyph.BACK, MaterialTheme.colorScheme.primary, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.home_live), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(groups, key = { it.filter.toString() }) { group ->
                FocusableRow(
                    onClick = { onSelect(group.filter) },
                    selected = group.filter == selected,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    if (group.locked) {
                        AppIcon(Glyph.LOCK, MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = groupLabel(group),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (group.count >= 0) {
                        Text(group.count.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun groupLabel(group: GuideGroup): String = when (val filter = group.filter) {
    ChannelFilter.All -> stringResource(R.string.guide_all_channels)
    ChannelFilter.Favorites -> "★ " + stringResource(R.string.guide_favorites)
    ChannelFilter.Recent -> stringResource(R.string.guide_recent)
    ChannelFilter.Catchup -> stringResource(R.string.guide_catchup)
    is ChannelFilter.Group -> filter.name ?: stringResource(R.string.guide_no_group)
    is ChannelFilter.Search -> filter.query
}

@Composable
private fun ChannelItem(
    row: ChannelRow?,
    playing: Boolean,
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
            .height(62.dp)
            .onKeyEvent { event ->
                // Menu key on a channel: add to / remove from favorites (long OK works too).
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    onFavorite()
                    true
                } else {
                    false
                }
            },
    ) {
        Text(
            text = row?.number?.toString() ?: "",
            style = MaterialTheme.typography.titleMedium,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
        )
        Box(Modifier.size(width = 60.dp, height = 38.dp), contentAlignment = Alignment.Center) {
            if (row?.logo != null) {
                AsyncImage(model = row.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row?.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val title = row?.nowTitle
            if (row != null && title != null) {
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val nowSec = System.currentTimeMillis() / 1_000
                val span = (row.nowStopSec - row.nowStartSec).coerceAtLeast(1)
                val fraction = ((nowSec - row.nowStartSec).toFloat() / span).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp))
            }
        }
        if (row != null && row.catchupDays > 0) {
            AppIcon(Glyph.REPLAY, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(start = 8.dp), size = 18.dp)
        }
        if (row?.favorite == true) {
            Text("★", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Focused channel: name, programme on air with its description, next programmes, actions. */
@Composable
private fun ChannelPanel(panel: LivePanel?, playingId: Long, vm: MainViewModel) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0x66000000), RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (panel == null) {
            Text(stringResource(R.string.live_select_channel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (playingId != 0L) TvButton(stringResource(R.string.live_fullscreen_hint), onClick = vm::fullScreen)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (panel.row.logo != null) {
                AsyncImage(model = panel.row.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 56.dp, height = 36.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(panel.row.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (panel.programmes.isEmpty()) {
            Text(stringResource(R.string.epg_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        panel.programmes.forEachIndexed { i, p ->
            ProgrammeLine(p, timeFormat, first = i == 0)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(stringResource(R.string.live_epg), onClick = { vm.openEpg(panel.row.id) }, glyph = Glyph.GUIDE)
            if (panel.row.catchupDays > 0) {
                TvButton(stringResource(R.string.live_replay, panel.row.catchupDays), onClick = { vm.openEpg(panel.row.id) }, glyph = Glyph.REPLAY)
            }
        }
    }
}

@Composable
private fun ProgrammeLine(p: Programme, timeFormat: java.text.DateFormat, first: Boolean) {
    val nowSec = System.currentTimeMillis() / 1_000
    val onAir = p.startSec <= nowSec && p.stopSec > nowSec
    val time = "${timeFormat.format(Date(p.startSec * 1_000))} - ${timeFormat.format(Date(p.stopSec * 1_000))}"
    Text(
        "$time  ${p.title}",
        style = if (first) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = if (onAir) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (onAir) {
        val fraction = ((nowSec - p.startSec).toFloat() / (p.stopSec - p.startSec).coerceAtLeast(1)).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp))
        p.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

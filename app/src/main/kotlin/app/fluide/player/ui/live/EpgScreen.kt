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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenBrush
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Paints the screen background everywhere but the video preview. */
fun Modifier.previewHole(origin: DpOffset, size: DpSize): Modifier = drawBehind {
    val hole = RoundRect(
        left = origin.x.toPx(),
        top = origin.y.toPx(),
        right = (origin.x + size.width).toPx(),
        bottom = (origin.y + size.height).toPx(),
        cornerRadius = CornerRadius(12.dp.toPx()),
    )
    clipPath(Path().apply { addRoundRect(hole) }, ClipOp.Difference) { drawRect(ScreenBrush) }
}

/**
 * Programme guide of one channel, day by day. Past programmes of a channel with catch-up are played
 * from the provider's recordings; the programme on air opens the live channel.
 */
@Composable
fun EpgScreen(vm: MainViewModel) {
    val epg by vm.epg.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var focused by remember { mutableIntStateOf(-1) }

    LaunchedEffect(epg.version) {
        if (epg.programmes.isEmpty()) return@LaunchedEffect
        val target = if (epg.nowIndex >= 0) epg.nowIndex else 0
        listState.scrollToItem((target - 2).coerceAtLeast(0))
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (origin, size) = LiveLayout.preview(maxWidth)
        Row(Modifier.fillMaxSize().previewHole(origin, size)) {
            Column(
                Modifier
                    .weight(LiveLayout.GROUPS + LiveLayout.CHANNELS)
                    .fillMaxHeight()
                    .padding(start = 32.dp, top = 20.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (epg.logo != null) {
                        AsyncImage(model = epg.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(width = 64.dp, height = 40.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text(epg.channelName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            if (epg.catchupDays > 0) stringResource(R.string.live_replay, epg.catchupDays) else stringResource(R.string.epg_title),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(epg.days) { index, day ->
                        FocusableRow(
                            onClick = { vm.selectEpgDay(index) },
                            selected = index == epg.dayIndex,
                            modifier = Modifier.height(40.dp),
                        ) {
                            Text(dayLabel(index - todayIndex(epg.days), day), maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (epg.programmes.isEmpty() && !epg.loading) {
                    Text(stringResource(R.string.epg_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
                val nowSec = System.currentTimeMillis() / 1_000
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(epg.programmes, key = { _, p -> p.startSec }) { index, p ->
                        val onAir = p.startSec <= nowSec && p.stopSec > nowSec
                        val recorded = p.stopSec <= nowSec && epg.catchupDays > 0 && p.startSec >= nowSec - epg.catchupDays * 86_400L
                        FocusableRow(
                            onClick = { vm.playProgramme(index) },
                            selected = onAir,
                            focusRequester = if (index == (if (epg.nowIndex >= 0) epg.nowIndex else 0)) focus else null,
                            onFocused = { focused = index },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(
                                timeFormat.format(Date(p.startSec * 1_000)),
                                color = if (p.stopSec <= nowSec) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                                modifier = Modifier.width(72.dp),
                            )
                            Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            when {
                                onAir -> Badge(stringResource(R.string.epg_live_badge), Color(0xFFE5245E))
                                recorded -> Badge(stringResource(R.string.epg_replay_badge), MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f - LiveLayout.GROUPS - LiveLayout.CHANNELS)
                    .fillMaxHeight()
                    .padding(start = 8.dp, end = LiveLayout.margin),
            ) {
                Spacer(Modifier.height(origin.y + size.height + 12.dp))
                val p = epg.programmes.getOrNull(focused)
                if (p != null) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0x66000000), RoundedCornerShape(12.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(p.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${timeFormat.format(Date(p.startSec * 1_000))} - ${timeFormat.format(Date(p.stopSec * 1_000))}" +
                                (p.category?.let { "  ·  $it" } ?: ""),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        p.subtitle?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        p.description?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(Glyph.GUIDE, MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.epg_title), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .padding(start = 8.dp)
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
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

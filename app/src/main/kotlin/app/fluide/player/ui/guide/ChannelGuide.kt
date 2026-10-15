package app.fluide.player.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.fluide.player.R
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRow
import app.fluide.player.ui.GuideGroup
import app.fluide.player.ui.GuideState
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.FocusableRow
import coil.compose.AsyncImage

/**
 * Channel list over the video (the right part of the picture stays visible): groups on the left,
 * channels in the middle. The channel list is virtualised (see RowPager): 100 000 channels scroll
 * as smoothly as 100.
 */
@Composable
fun ChannelGuide(vm: MainViewModel, guide: GuideState, playingId: Long) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Opening the guide (or changing list) puts the focus on the channel playing.
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color(0xF205070D),
                    0.7f to Color(0xE605070D),
                    1f to Color(0x0005070D),
                ),
            ),
    ) {
        GroupColumn(
            groups = guide.groups,
            selected = guide.selected,
            onSelect = vm::selectFilter,
            onSources = vm::openSources,
            modifier = Modifier.weight(0.28f).fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .weight(0.47f)
                .fillMaxHeight()
                .padding(vertical = 24.dp, horizontal = 8.dp),
        ) {
            OutlinedTextField(
                value = guide.query,
                onValueChange = vm::search,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.guide_search_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (guide.size == 0) {
                Text(
                    stringResource(R.string.guide_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(count = guide.size, key = { index -> vm.pager.idAt(index) ?: -index.toLong() - 1 }) { index ->
                        val row = vm.pager.rowAt(index)
                        ChannelItem(
                            row = row,
                            playing = row != null && row.id == playingId,
                            focusRequester = if (index == guide.focusIndex) focusRequester else null,
                            onClick = { vm.playFromGuide(index) },
                            onFavorite = { vm.toggleFavorite(index) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.weight(0.25f))
    }
}

@Composable
private fun GroupColumn(
    groups: List<GuideGroup>,
    selected: ChannelFilter,
    onSelect: (ChannelFilter) -> Unit,
    onSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(vertical = 24.dp, horizontal = 12.dp)) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp, bottom = 16.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(groups, key = { it.filter.toString() }) { group ->
                FocusableRow(
                    onClick = { onSelect(group.filter) },
                    selected = group.filter == selected,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(
                        text = groupLabel(group),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (group.count >= 0) {
                        Text(
                            group.count.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        FocusableRow(onClick = onSources, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(48.dp)) {
            Text(stringResource(R.string.guide_sources), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun groupLabel(group: GuideGroup): String = when (val filter = group.filter) {
    ChannelFilter.All -> stringResource(R.string.guide_all_channels)
    ChannelFilter.Favorites -> "★ " + stringResource(R.string.guide_favorites)
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
) {
    FocusableRow(
        onClick = onClick,
        onLongClick = onFavorite,
        focusRequester = focusRequester,
        selected = playing,
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
    ) {
        Text(
            text = row?.number?.toString() ?: "",
            style = MaterialTheme.typography.titleMedium,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Box(Modifier.size(width = 64.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            if (row?.logo != null) {
                AsyncImage(model = row.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = row?.name ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val title = row?.nowTitle
            if (row != null && title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val nowSec = System.currentTimeMillis() / 1_000
                val span = (row.nowStopSec - row.nowStartSec).coerceAtLeast(1)
                val fraction = ((nowSec - row.nowStartSec).toFloat() / span).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp))
            }
        }
        if (row?.favorite == true) {
            Text("★", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

package app.fluide.player.ui.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.MediaFilter
import app.fluide.player.data.MediaItem
import app.fluide.player.data.MediaSort
import app.fluide.player.data.Section
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.MediaGroup
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FocusableRow
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import coil.compose.AsyncImage
import java.util.Locale

/**
 * Movies or series: categories on the left, a poster grid on the right (search and sort above it).
 * [gridState] and [lastFocused] live in the caller so that coming back from a details page returns to
 * the same poster.
 */
@Composable
fun MediaScreen(vm: MainViewModel, section: Section, gridState: LazyGridState, lastFocused: MutableIntState) {
    val browser = if (section == Section.SERIES) vm.series else vm.movies
    val state by browser.browser.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }

    LaunchedEffect(state.version) {
        if (state.size == 0) return@LaunchedEffect
        val index = lastFocused.intValue.coerceIn(0, state.size - 1)
        if (gridState.firstVisibleItemIndex > index || index - gridState.firstVisibleItemIndex > 12) gridState.scrollToItem(index)
        withFrameNanos { }
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    ScreenSurface {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.22f).fillMaxHeight().padding(vertical = 20.dp, horizontal = 12.dp)) {
                FocusableRow(onClick = vm::openHome, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    AppIcon(Glyph.BACK, MaterialTheme.colorScheme.primary, size = 20.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (section == Section.SERIES) R.string.home_series else R.string.home_movies),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(state.groups, key = { it.filter.toString() }) { group ->
                        FocusableRow(
                            onClick = {
                                lastFocused.intValue = 0
                                vm.selectMediaFilter(section, group.filter)
                            },
                            selected = group.filter == state.selected,
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                        ) {
                            if (group.locked) {
                                AppIcon(Glyph.LOCK, MaterialTheme.colorScheme.onSurfaceVariant, size = 16.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(groupLabel(group), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (group.count >= 0) {
                                Text(group.count.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(0.78f).fillMaxHeight().padding(top = 20.dp, end = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = { browser.search(it) },
                        singleLine = true,
                        placeholder = {
                            Text(stringResource(if (section == Section.SERIES) R.string.media_search_series else R.string.media_search_movies))
                        },
                        leadingIcon = { AppIcon(Glyph.SEARCH, MaterialTheme.colorScheme.onSurfaceVariant, size = 20.dp) },
                        modifier = Modifier.weight(1f),
                    )
                    TvButton(
                        stringResource(R.string.media_sort, sortLabel(state.sort)),
                        onClick = { browser.setSort(MediaSort.entries[(state.sort.ordinal + 1) % MediaSort.entries.size]) },
                        glyph = Glyph.LIST,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (state.size == 0) {
                    Text(stringResource(R.string.media_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(150.dp),
                        state = gridState,
                        contentPadding = PaddingValues(bottom = 24.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(count = state.size, key = { index -> browser.pager.idAt(index) ?: (-index.toLong() - 1) }) { index ->
                            PosterCard(
                                item = browser.pager.itemAt(index),
                                focusRequester = if (index == lastFocused.intValue.coerceIn(0, state.size - 1)) focus else null,
                                onFocused = { lastFocused.intValue = index },
                                onClick = { if (section == Section.SERIES) vm.openSeriesAt(index) else vm.openMovieAt(index) },
                                onFavorite = { vm.toggleMediaFavorite(section, index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun groupLabel(group: MediaGroup): String = when (val filter = group.filter) {
    MediaFilter.All -> stringResource(R.string.media_all)
    MediaFilter.ContinueWatching -> stringResource(R.string.media_continue)
    MediaFilter.Favorites -> "★ " + stringResource(R.string.media_favorites)
    MediaFilter.RecentlyAdded -> stringResource(R.string.media_recent)
    is MediaFilter.Group -> filter.name ?: stringResource(R.string.media_no_group)
    is MediaFilter.Search -> filter.query
}

@Composable
private fun sortLabel(sort: MediaSort): String = stringResource(
    when (sort) {
        MediaSort.DEFAULT -> R.string.sort_default
        MediaSort.ADDED -> R.string.sort_added
        MediaSort.NAME_ASC -> R.string.sort_name_asc
        MediaSort.NAME_DESC -> R.string.sort_name_desc
        MediaSort.RATING -> R.string.sort_rating
        MediaSort.YEAR -> R.string.sort_year
    },
)

private val PlaceholderBrush = Brush.linearGradient(listOf(Color(0xFF26304F), Color(0xFF151B33)))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    item: MediaItem?,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "poster")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused()
                focused = it.isFocused
            }
            .onKeyEvent { event ->
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    onFavorite()
                    true
                } else {
                    false
                }
            }
            .combinedClickable(onLongClick = onFavorite, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(shape)
                .background(PlaceholderBrush, shape)
                .border(3.dp, if (focused) Color.White else Color.Transparent, shape),
        ) {
            if (item?.poster == null) {
                Text(
                    item?.name ?: "",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.align(Alignment.Center).padding(10.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                AsyncImage(model = item.poster, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            val rating = item?.rating
            if (rating != null) {
                Text(
                    "★ " + String.format(Locale.ROOT, "%.1f", if (rating > 10) rating / 10 else rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (item?.favorite == true) {
                Text("♥", color = Color(0xFFFF5C7A), modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            val progress = item?.progress ?: -1f
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item?.name ?: "",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (focused) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item?.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

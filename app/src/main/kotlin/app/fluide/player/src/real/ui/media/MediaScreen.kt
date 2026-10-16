package app.fluide.player.ui.media

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.MediaFilter
import app.fluide.player.data.MediaItem
import app.fluide.player.data.MediaSort
import app.fluide.player.data.Section
import app.fluide.player.ui.BrowserState
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.MediaGroup
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton

/**
 * Movies or series: a glass panel of categories on the left, a poster grid on the right (title, search
 * and sort above it). [gridState] and [lastFocused] live in the caller so that coming back from a
 * details page returns to the same poster.
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

    val widthDp = LocalConfiguration.current.screenWidthDp
    val wide = widthDp >= 840
    val compact = widthDp < 600
    val gutter = if (wide) 48.dp else 16.dp
    val panelWidth = when {
        wide -> 272.dp
        compact -> (widthDp * 0.36f).dp
        else -> 220.dp
    }
    val glyph = sectionGlyph(section)

    ScreenSurface {
        Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = if (wide) 28.dp else 16.dp)) {
            MediaTopBar(
                section = section,
                state = state,
                compact = compact,
                onBack = vm::openHome,
                onQuery = { browser.search(it) },
                onSort = { browser.setSort(MediaSort.entries[(state.sort.ordinal + 1) % MediaSort.entries.size]) },
            )
            Spacer(Modifier.height(if (wide) 20.dp else 12.dp))
            Row(Modifier.fillMaxWidth().weight(1f)) {
                CategoryPanel(
                    groups = state.groups,
                    selected = state.selected,
                    allGlyph = glyph,
                    onSelect = { filter ->
                        lastFocused.intValue = 0
                        vm.selectMediaFilter(section, filter)
                    },
                    modifier = Modifier.width(panelWidth).fillMaxHeight().padding(bottom = if (wide) 28.dp else 12.dp),
                )
                Spacer(Modifier.width(if (wide) 24.dp else 10.dp))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        state.size > 0 -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(if (compact) 112.dp else 150.dp),
                            state = gridState,
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
                            verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(count = state.size, key = { index -> browser.pager.idAt(index) ?: (-index.toLong() - 1) }) { index ->
                                PosterCard(
                                    item = browser.pager.itemAt(index),
                                    fallbackGlyph = glyph,
                                    focusRequester = if (index == lastFocused.intValue.coerceIn(0, state.size - 1)) focus else null,
                                    onFocused = { lastFocused.intValue = index },
                                    onClick = { if (section == Section.SERIES) vm.openSeriesAt(index) else vm.openMovieAt(index) },
                                    onFavorite = { vm.toggleMediaFavorite(section, index) },
                                )
                            }
                        }
                        // Categories not loaded yet: a still skeleton of the grid.
                        state.version == 0 -> SkeletonGrid(compact)
                        else -> EmptyState(glyph, Modifier.fillMaxWidth().padding(top = 48.dp))
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ top bar

@Composable
private fun MediaTopBar(
    section: Section,
    state: BrowserState,
    compact: Boolean,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onSort: () -> Unit,
) {
    val title = stringResource(if (section == Section.SERIES) R.string.home_series else R.string.home_movies)
    val placeholder = stringResource(if (section == Section.SERIES) R.string.media_search_series else R.string.media_search_movies)
    val sortText = stringResource(R.string.media_sort, sortLabel(state.sort))
    val subtitle = filterLabel(state.selected)
    if (compact) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Glyph.BACK, onClick = onBack, diameter = 44.dp)
                Spacer(Modifier.width(12.dp))
                TitleBlock(title, subtitle, state.size, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TvButton(sortText, onClick = onSort, glyph = Glyph.LIST)
            }
            Spacer(Modifier.height(10.dp))
            SearchField(state.query, onQuery, placeholder, Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Glyph.BACK, onClick = onBack)
            Spacer(Modifier.width(16.dp))
            TitleBlock(title, subtitle, state.size, Modifier.widthIn(max = 300.dp))
            Spacer(Modifier.width(28.dp))
            SearchField(state.query, onQuery, placeholder, Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            TvButton(sortText, onClick = onSort, glyph = Glyph.LIST)
        }
    }
}

/** Section title, with the current category and its number of titles under it. */
@Composable
private fun TitleBlock(title: String, subtitle: String, count: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            title,
            color = Color.White,
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentBar(width = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                subtitle,
                color = MediaPalette.Muted,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (count > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    count.toString(),
                    color = MediaPalette.Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .background(MediaPalette.Cyan.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Search pill: glass at rest, cyan ring while focused. OK on the keyboard moves down to the results. */
@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(24.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(MediaPalette.Cyan),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.moveFocus(FocusDirection.Down) }),
        modifier = modifier
            .height(48.dp)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxSize()
                    .mediaFocusGlow(focused, 24.dp)
                    .clip(shape)
                    .background(if (focused) Color(0x1FFFFFFF) else MediaPalette.Glass, shape)
                    .border(if (focused) 2.dp else 1.dp, if (focused) MediaPalette.Cyan else MediaPalette.GlassBorder, shape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(Glyph.SEARCH, if (focused) MediaPalette.Cyan else MediaPalette.Muted, size = 20.dp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = MediaPalette.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            }
        },
    )
}

// ------------------------------------------------------------------ categories

private val CategoryShape = RoundedCornerShape(24.dp)
private val CategoryFocusBrush = Brush.horizontalGradient(listOf(MediaPalette.Blue.copy(alpha = 0.85f), MediaPalette.Cyan.copy(alpha = 0.55f)))
private val CategorySelectedBrush = Brush.horizontalGradient(listOf(MediaPalette.Blue.copy(alpha = 0.34f), MediaPalette.Cyan.copy(alpha = 0.12f)))
private val CategoryIdleBrush = SolidColor(Color.Transparent)

@Composable
private fun CategoryPanel(
    groups: List<MediaGroup>,
    selected: MediaFilter,
    allGlyph: Glyph,
    onSelect: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .mediaGlass(shape)
            .padding(top = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Glyph.LIST, MediaPalette.Cyan, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.media_categories).uppercase(),
                color = MediaPalette.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(groups, key = { it.filter.toString() }) { group ->
                CategoryItem(
                    label = groupLabel(group),
                    glyph = filterGlyph(group.filter, allGlyph),
                    count = group.count,
                    locked = group.locked,
                    selected = group.filter == selected,
                    onClick = { onSelect(group.filter) },
                )
            }
        }
    }
}

/** One category: a pill, tinted when selected, filled with the fluid gradient while focused. */
@Composable
private fun CategoryItem(label: String, glyph: Glyph?, count: Int, locked: Boolean, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "category")
    val fill = when {
        focused -> CategoryFocusBrush
        selected -> CategorySelectedBrush
        else -> CategoryIdleBrush
    }
    val borderColor = when {
        focused -> Color.White
        selected -> MediaPalette.Cyan.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    val textColor = if (focused || selected) Color.White else MediaPalette.Soft
    val iconColor = when {
        focused -> Color.White
        selected -> MediaPalette.Cyan
        else -> MediaPalette.Muted
    }
    // Same modifier chain in every state (only values change): the focus node is never recreated.
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CategoryShape)
            .background(fill, CategoryShape)
            .border(if (focused) 2.dp else 1.dp, borderColor, CategoryShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when {
                locked -> AppIcon(Glyph.LOCK, if (focused) Color.White else MediaPalette.Violet.copy(alpha = 0.95f), size = 16.dp)
                glyph != null -> AppIcon(glyph, iconColor, size = 16.dp)
                else -> Box(Modifier.size(6.dp).background(if (selected || focused) iconColor else MediaPalette.Muted.copy(alpha = 0.45f), CircleShape))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count >= 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                color = if (focused) Color.White else if (selected) MediaPalette.Cyan else MediaPalette.Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier
                    .background(Color.White.copy(alpha = if (focused) 0.18f else 0.07f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

private fun filterGlyph(filter: MediaFilter, allGlyph: Glyph): Glyph? = when (filter) {
    MediaFilter.All -> allGlyph
    MediaFilter.ContinueWatching -> Glyph.PLAY
    MediaFilter.Favorites -> Glyph.STAR
    MediaFilter.RecentlyAdded -> Glyph.GUIDE
    is MediaFilter.Search -> Glyph.SEARCH
    is MediaFilter.Group -> null
}

@Composable
private fun groupLabel(group: MediaGroup): String = filterLabel(group.filter)

@Composable
private fun filterLabel(filter: MediaFilter): String = when (filter) {
    MediaFilter.All -> stringResource(R.string.media_all)
    MediaFilter.ContinueWatching -> stringResource(R.string.media_continue)
    MediaFilter.Favorites -> stringResource(R.string.media_favorites)
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

// ------------------------------------------------------------------ grid

private val PosterShape = RoundedCornerShape(16.dp)

/**
 * A poster: shimmer while its tile loads, picture with a bottom gradient carrying the title, the
 * rating chip, the favorite heart and the progress. OK opens it; long OK and Menu toggle the favorite.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    item: MediaItem?,
    fallbackGlyph: Glyph,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "poster")
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .zIndex(if (focused) 1f else 0f)
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
            .mediaFocusGlow(focused, 16.dp)
            .clip(PosterShape)
            .combinedClickable(onLongClick = onFavorite, onClick = onClick)
            .background(MediaArtPlaceholder, PosterShape)
            .border(if (focused) 3.dp else 1.dp, if (focused) MediaFocusBorder else MediaRestBorder, PosterShape),
    ) {
        if (item == null) {
            ShimmerBlock(Modifier.fillMaxSize(), PosterShape)
        } else {
            MediaArt(item.poster, item.name, fallbackGlyph, Modifier.fillMaxSize())
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(if (focused) 0.62f else 0.5f)
                    .background(MediaBottomScrim),
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    item.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.year?.let {
                    Text(it.toString(), color = if (focused) MediaPalette.Soft else MediaPalette.Muted, fontSize = 12.sp, maxLines = 1)
                }
                if (item.progress > 0f) {
                    Spacer(Modifier.height(6.dp))
                    ProgressStripe(item.progress, Modifier.fillMaxWidth(), height = 3.dp)
                }
            }
            RatingChip(item.rating, Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (item.favorite) FavoriteBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
    }
}

/** Placeholder grid shown while the categories load (nothing focusable in it). */
@Composable
private fun SkeletonGrid(compact: Boolean) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (compact) 112.dp else 150.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 22.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(count = 12) {
            ShimmerBlock(Modifier.fillMaxWidth().aspectRatio(2f / 3f), PosterShape)
        }
    }
}

@Composable
private fun EmptyState(glyph: Glyph, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(76.dp)
                .background(
                    Brush.linearGradient(listOf(MediaPalette.Blue.copy(alpha = 0.28f), MediaPalette.Violet.copy(alpha = 0.22f))),
                    CircleShape,
                )
                .border(1.dp, MediaPalette.GlassBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(glyph, MediaPalette.Cyan, size = 32.dp)
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.media_empty), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.media_empty_hint),
            color = MediaPalette.Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

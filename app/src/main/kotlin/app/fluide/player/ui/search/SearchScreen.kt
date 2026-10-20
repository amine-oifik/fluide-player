package app.fluide.player.ui.search

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.MediaItem
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.theme.blend
import app.fluide.player.ui.theme.themed
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.util.Locale

// ------------------------------------------------------------------ palette (shared design language)

// Brand colours follow the colour theme chosen in the settings (getters / values rebuilt per theme).
private val Blue: Color get() = FluideColors.Blue
private val Cyan: Color get() = FluideColors.Cyan
private val Violet: Color get() = FluideColors.Violet
private val Warm: Color get() = FluideColors.Warm
private val Amber = Color(0xFFFFC857)
private val Rose = Color(0xFFFF5C7A)
private val Soft: Color get() = FluideColors.Soft
private val Muted: Color get() = FluideColors.Muted
private val GlassFill = Color.White.copy(alpha = 0.07f)
private val GlassBorder = Color.White.copy(alpha = 0.10f)
private val GlassBrush = SolidColor(GlassFill)
private val FluidBrush: Brush get() = FluideGradient
private val ChannelFocusBrush: Brush by themed {
    Brush.horizontalGradient(listOf(blend(it.navy, it.blue, 0.48f), blend(it.navy, it.azure, 0.28f)))
}
private val LiveBar: Brush by themed { Brush.horizontalGradient(listOf(it.warm, Color(0xFFFFB13D))) }
private val ProgressBar: Brush by themed { Brush.horizontalGradient(listOf(it.blue, it.cyan)) }
private val LiveAccent: Brush by themed { Brush.verticalGradient(listOf(Color(0xFFFF8A3D), it.warm)) }
private val MoviesAccent: Brush by themed { Brush.verticalGradient(listOf(blend(it.blue, Color.White, 0.16f), it.blue)) }
private val SeriesAccent: Brush by themed { Brush.verticalGradient(listOf(it.violet, blend(it.violet, it.blue, 0.58f))) }
private val PosterPlaceholder: Brush by themed {
    Brush.linearGradient(
        listOf(blend(it.dialogTop, it.blue, 0.08f), blend(it.night, it.dialogTop, 0.8f), blend(it.night, it.violet, 0.16f)),
    )
}
private val LogoWell: Brush by themed {
    Brush.linearGradient(listOf(blend(it.surface, it.blue, 0.12f), blend(it.navy, it.raised, 0.7f)))
}
private val PosterShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))
private val BrandRing: List<Color> by themed { listOf(it.blue, it.cyan, it.violet, it.blue) }
private val MutedRing: List<Color> by themed {
    listOf(it.violet.copy(alpha = 0.7f), Color.White.copy(alpha = 0.18f), it.violet.copy(alpha = 0.7f))
}
private val MatchStyle: SpanStyle by themed { SpanStyle(color = it.cyan, fontWeight = FontWeight.ExtraBold) }

private val CardShape = RoundedCornerShape(16.dp)
private val WellShape = RoundedCornerShape(12.dp)
private val PillShape = RoundedCornerShape(24.dp)

/** [GlobalSearch] keeps at most this many results per section: a full row gets a "see all" card. */
private const val RESULT_CAP = 40

// ------------------------------------------------------------------ state

private enum class Kind(val prefix: String) { CHANNELS("c"), MOVIES("m"), SERIES("s") }

/** Card sizes and screen padding: TV (48 dp gutters) or phone (16 dp). */
private class Dims(val compact: Boolean, val gutter: Dp, val channelWidth: Dp, val channelHeight: Dp, val posterWidth: Dp)

/**
 * Where the user was in the results, kept while the app runs: coming back from a details page lands
 * on the same card at the same scroll positions. Only used by the same view model and the same query.
 */
private object SearchMemory {
    var owner = 0
    var query = ""
    var focusKey: String? = null
    var list = intArrayOf(0, 0)
    val rows = HashMap<Kind, IntArray>()
}

/** Scroll states of the page and of the three rows. A new set is made for every new result set. */
private class RowStates(
    val list: LazyListState,
    val channels: LazyListState,
    val movies: LazyListState,
    val series: LazyListState,
) {
    fun of(kind: Kind): LazyListState = when (kind) {
        Kind.CHANNELS -> channels
        Kind.MOVIES -> movies
        Kind.SERIES -> series
    }

    companion object {
        fun create(restore: Boolean): RowStates {
            fun state(saved: IntArray?): LazyListState {
                val index = if (restore) (saved?.getOrNull(0) ?: 0) else 0
                val offset = if (restore) (saved?.getOrNull(1) ?: 0) else 0
                return LazyListState(index, offset)
            }
            return RowStates(
                list = state(SearchMemory.list),
                channels = state(SearchMemory.rows[Kind.CHANNELS]),
                movies = state(SearchMemory.rows[Kind.MOVIES]),
                series = state(SearchMemory.rows[Kind.SERIES]),
            )
        }
    }
}

private class ResultTarget(val kind: Kind, val key: String)

/** First card of the page: the first channel, else the first movie, else the first series. */
private fun firstResult(s: SearchState): ResultTarget? = when {
    s.channels.isNotEmpty() -> ResultTarget(Kind.CHANNELS, Kind.CHANNELS.prefix + s.channels[0].id)
    s.movies.isNotEmpty() -> ResultTarget(Kind.MOVIES, Kind.MOVIES.prefix + s.movies[0].id)
    s.series.isNotEmpty() -> ResultTarget(Kind.SERIES, Kind.SERIES.prefix + s.series[0].id)
    else -> null
}

/**
 * Focus bookkeeping of the screen: which card has the focus, the requesters of the field, of the
 * cards and of the "new search" button, and the moves the screen makes by itself (to the first
 * result, back to the field). Plain fields where only callbacks and coroutines read them, so a focus
 * move never recomposes the page.
 */
private class SearchFocus(private val vm: MainViewModel, private val owner: Int) {
    val fieldRequester = FocusRequester()
    val retryRequester = FocusRequester()
    private val cards = HashMap<String, FocusRequester>()
    var rows: RowStates? = null
    var focusedCard: String? = null
    var pageFocused = false
    var retryFocused = false

    /** Set by the keyboard "search" key and by the examples: focus the first result once it is there. */
    var pending = false
    var fieldFocused by mutableStateOf(false)

    fun requester(key: String): FocusRequester = cards.getOrPut(key) { FocusRequester() }

    fun onField(focused: Boolean) {
        fieldFocused = focused
        if (focused) SearchMemory.focusKey = null
    }

    fun onCard(key: String, focused: Boolean) {
        if (focused) {
            focusedCard = key
            SearchMemory.owner = owner
            SearchMemory.query = vm.globalSearch.state.value.query
            SearchMemory.focusKey = key
        } else if (focusedCard == key) {
            focusedCard = null
        }
    }

    suspend fun focusField(): Boolean = focusSoon(fieldRequester) { fieldFocused }

    suspend fun focusRetry(): Boolean = focusSoon(retryRequester) { retryFocused }

    suspend fun focusCard(key: String): Boolean = focusSoon(requester(key)) { focusedCard == key }

    /** Scrolls back to the start of the page and of the first row, then focuses its first card. */
    suspend fun focusFirstResult(): Boolean {
        withFrameNanos { }
        val target = firstResult(vm.globalSearch.state.value) ?: return false
        val current = rows ?: return false
        // A list never laid out waits for its first layout before scrolling: never wait forever.
        withTimeoutOrNull(800L) {
            current.list.toStart()
            current.of(target.kind).toStart()
        }
        return focusCard(target.key)
    }

    fun save() {
        val current = rows ?: return
        SearchMemory.owner = owner
        SearchMemory.query = vm.globalSearch.state.value.query
        SearchMemory.list = current.list.position()
        SearchMemory.rows[Kind.CHANNELS] = current.channels.position()
        SearchMemory.rows[Kind.MOVIES] = current.movies.position()
        SearchMemory.rows[Kind.SERIES] = current.series.position()
    }
}

// ------------------------------------------------------------------ screen

/**
 * Global search: one field for the whole catalogue of the active playlist (channels, movies and
 * series), results in three rows while the user types. Remote: the field has the focus when the
 * screen opens, ↓ (or the keyboard "search" key) goes to the first result, ↑ from the first row comes
 * back to the field, OK opens, Back clears the query then leaves. Touch works everywhere.
 */
@Composable
fun SearchScreen(vm: MainViewModel) {
    val state by vm.globalSearch.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == ui.activeSourceId }

    // Lazy rows need unique keys: never trust the data layer for that.
    val channels = remember(state.channels) { state.channels.distinctBy { it.id } }
    val movies = remember(state.movies) { state.movies.distinctBy { it.id } }
    val series = remember(state.series) { state.series.distinctBy { it.id } }
    val hasResults = channels.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty()
    val query = state.query

    // Focus and scroll memory.
    val owner = System.identityHashCode(vm)
    val initialQuery = remember { vm.globalSearch.state.value.query }
    val sameSession = remember {
        initialQuery.isNotBlank() && SearchMemory.owner == owner && SearchMemory.query == initialQuery
    }
    val restoreKey = remember { if (sameSession) SearchMemory.focusKey else null }
    val focus = remember { SearchFocus(vm, owner) }
    val firstBuild = remember { booleanArrayOf(true) }
    val rows = remember(state.channels, state.movies, state.series) {
        val restore = sameSession && firstBuild[0]
        firstBuild[0] = false
        RowStates.create(restore)
    }
    SideEffect { focus.rows = rows }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    val numbers = remember { NumberFormat.getIntegerInstance(Locale.getDefault()) }
    val catalog = source?.takeIf { it.channelCount + it.movieCount + it.seriesCount > 0 }?.let {
        stringResource(
            R.string.search_catalog,
            numbers.format(it.channelCount.toLong()),
            numbers.format(it.movieCount.toLong()),
            numbers.format(it.seriesCount.toLong()),
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1_000
        }
    }
    LaunchedEffect(Unit) {
        val target = restoreKey
        val restored = target != null && focus.focusCard(target)
        if (!restored) focus.focusField()
        // Then follow the results: the remote must never be left without a focused element.
        var shown = vm.globalSearch.state.value
        vm.globalSearch.state.collectLatest { s ->
            val newSet = s.channels !== shown.channels || s.movies !== shown.movies || s.series !== shown.series
            shown = s
            if (s.searching) return@collectLatest
            // A new result set replaces the card that had the focus.
            if (newSet && focus.focusedCard != null && focus.focusFirstResult()) return@collectLatest
            if (focus.pending) {
                focus.pending = false
                if (!focus.focusFirstResult() && !focus.pageFocused) focus.focusRetry()
                return@collectLatest
            }
            withFrameNanos { }
            withFrameNanos { }
            if (!focus.pageFocused && !focus.focusFirstResult()) {
                if (s.query.isBlank() || !focus.focusRetry()) focus.focusField()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { focus.save() }
    }

    ScreenSurface {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            val dims = Dims(
                compact = compact,
                gutter = if (compact) 16.dp else 48.dp,
                channelWidth = if (compact) 248.dp else 300.dp,
                channelHeight = if (compact) 84.dp else 96.dp,
                posterWidth = if (compact) 112.dp else 140.dp,
            )
            Column(Modifier.fillMaxSize().onFocusChanged { focus.pageFocused = it.hasFocus }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = dims.gutter, end = dims.gutter, top = if (compact) 14.dp else 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TvButton(stringResource(R.string.search_back), onClick = { vm.onBack() }, glyph = Glyph.BACK)
                    Spacer(Modifier.width(if (compact) 12.dp else 20.dp))
                    SearchField(
                        query = query,
                        focused = focus.fieldFocused,
                        requester = focus.fieldRequester,
                        onQuery = vm::searchAll,
                        onFocusChange = { f -> focus.onField(f) },
                        onDown = {
                            if (hasResults) {
                                scope.launch { focus.focusFirstResult() }
                                true
                            } else {
                                false
                            }
                        },
                        onSearch = {
                            keyboard?.hide()
                            val searching = vm.globalSearch.state.value.searching
                            // Results still coming: the first one gets the focus when it arrives.
                            if (searching) focus.pending = true
                            if (!searching) scope.launch { focus.focusFirstResult() }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (!compact) {
                        Spacer(Modifier.width(24.dp))
                        Clock(settings.clock24h, showDate = false)
                    }
                }

                // Progress while searching, then the number of results.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = dims.gutter, end = dims.gutter, top = 12.dp)
                        .height(3.dp),
                ) {
                    if (state.searching && query.isNotBlank()) FlowBar(Modifier.fillMaxSize())
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .padding(horizontal = dims.gutter),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isNotBlank() && state.searching) {
                        Text(stringResource(R.string.search_searching), color = Muted, fontSize = 13.sp, maxLines = 1)
                    } else if (query.isNotBlank() && hasResults) {
                        val total = channels.size + movies.size + series.size
                        Text(
                            pluralStringResource(R.plurals.search_results, total, total, query.trim()),
                            color = Soft,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        query.isBlank() -> HintPanel(
                            dims = dims,
                            catalog = catalog,
                            hasSource = source != null,
                            onExample = { word ->
                                focus.pending = true
                                vm.searchAll(word)
                            },
                            onAddSource = vm::openSources,
                        )
                        hasResults -> ResultsList(
                            channels = channels,
                            movies = movies,
                            series = series,
                            query = query,
                            rows = rows,
                            focus = focus,
                            dims = dims,
                            stale = state.searching,
                            nowSec = nowSec,
                            onChannel = { row -> vm.playChannelFromHome(row.id) },
                            onMovie = { item -> vm.openMovieById(item.id) },
                            onSeries = { item -> vm.openSeriesById(item.id) },
                            onSeeAll = { kind -> seeAll(vm, kind) },
                        )
                        state.searching -> SkeletonResults(dims)
                        else -> EmptyPanel(
                            query = query.trim(),
                            dims = dims,
                            focus = focus,
                            onRetry = {
                                vm.searchAll("")
                                scope.launch { focus.focusField() }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The full lists behind a row that reached [RESULT_CAP]: the section's own screen, with the same query. */
private fun seeAll(vm: MainViewModel, kind: Kind) {
    val query = vm.globalSearch.state.value.query.trim()
    when (kind) {
        Kind.CHANNELS -> {
            vm.openLive()
            vm.search(query)
        }
        Kind.MOVIES -> {
            vm.movies.search(query)
            vm.openMovies()
        }
        Kind.SERIES -> {
            vm.series.search(query)
            vm.openSeries()
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun SearchField(
    query: String,
    focused: Boolean,
    requester: FocusRequester,
    onQuery: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onDown: () -> Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier
            .focusHalo(focused, Cyan, 24.dp)
            .focusRequester(requester)
            .onFocusChanged { onFocusChange(it.isFocused) }
            // ↓ goes to the first result card (not to the geometrically closest one).
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown && onDown()
            },
        textStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
        placeholder = {
            Text(stringResource(R.string.search_field_hint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = { AppIcon(Glyph.SEARCH, if (focused) Cyan else Muted, size = 22.dp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = PillShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Soft,
            focusedContainerColor = FluideColors.Raised,
            unfocusedContainerColor = GlassFill,
            cursorColor = Cyan,
            focusedBorderColor = Cyan,
            unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
            focusedPlaceholderColor = Muted,
            unfocusedPlaceholderColor = Muted,
        ),
    )
}

/** Thin bar under the field while searching: a blue to cyan wave flowing from left to right. */
@Composable
private fun FlowBar(modifier: Modifier = Modifier) {
    val phase = rememberInfiniteTransition(label = "flowBar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_100, easing = LinearEasing)),
        label = "flowPhase",
    )
    Box(
        modifier
            .clip(RoundedCornerShape(2.dp))
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.06f))
                val segment = size.width * 0.32f
                val x = -segment + (size.width + segment) * phase.value
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Blue, Cyan, Color.Transparent),
                        startX = x,
                        endX = x + segment,
                    ),
                    topLeft = Offset(x, 0f),
                    size = Size(segment, size.height),
                )
            },
    )
}

// ------------------------------------------------------------------ results

@Composable
private fun ResultsList(
    channels: List<ChannelRow>,
    movies: List<MediaItem>,
    series: List<MediaItem>,
    query: String,
    rows: RowStates,
    focus: SearchFocus,
    dims: Dims,
    stale: Boolean,
    nowSec: Long,
    onChannel: (ChannelRow) -> Unit,
    onMovie: (MediaItem) -> Unit,
    onSeries: (MediaItem) -> Unit,
    onSeeAll: (Kind) -> Unit,
) {
    // Results of the previous query stay visible, dimmed, until the new ones arrive.
    val fade by animateFloatAsState(if (stale) 0.5f else 1f, label = "staleResults")
    val first = when {
        channels.isNotEmpty() -> Kind.CHANNELS
        movies.isNotEmpty() -> Kind.MOVIES
        else -> Kind.SERIES
    }
    val channelsTitle = stringResource(R.string.search_section_channels)
    val moviesTitle = stringResource(R.string.search_section_movies)
    val seriesTitle = stringResource(R.string.search_section_series)
    val channelsCount = countLabel(channels.size)
    val moviesCount = countLabel(movies.size)
    val seriesCount = countLabel(series.size)
    val watchHint = if (dims.compact) null else stringResource(R.string.search_action_watch)
    val detailsHint = if (dims.compact) null else stringResource(R.string.search_action_details)
    val noProgramme = stringResource(R.string.search_no_programme)
    val seeAll = stringResource(R.string.search_see_all)
    val seeAllLive = stringResource(R.string.search_see_all_live)
    val seeAllMovies = stringResource(R.string.search_see_all_movies)
    val seeAllSeries = stringResource(R.string.search_see_all_series)

    LazyColumn(
        state = rows.list,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade },
        contentPadding = PaddingValues(top = 2.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (channels.isNotEmpty()) {
            item(key = "channels") {
                // ↑ from the first row goes back to the search field (not to the back button).
                val up = if (first == Kind.CHANNELS) focus.fieldRequester else null
                ResultSection(channelsTitle, channelsCount, Glyph.TV, LiveAccent, Warm, watchHint, dims.gutter) {
                    LazyRow(
                        state = rows.channels,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = dims.gutter, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(channels, key = { it.id }) { row ->
                            val key = Kind.CHANNELS.prefix + row.id
                            ChannelResultCard(
                                row = row,
                                query = query,
                                nowSec = nowSec,
                                width = dims.channelWidth,
                                height = dims.channelHeight,
                                noProgramme = noProgramme,
                                focusRequester = focus.requester(key),
                                upTarget = up,
                                onFocusChange = { f -> focus.onCard(key, f) },
                                onClick = { onChannel(row) },
                            )
                        }
                        if (channels.size >= RESULT_CAP) {
                            item(key = "all") {
                                val key = Kind.CHANNELS.prefix + "all"
                                SeeAllCard(
                                    label = seeAll,
                                    caption = seeAllLive,
                                    width = dims.channelWidth,
                                    height = dims.channelHeight,
                                    horizontal = true,
                                    focusRequester = focus.requester(key),
                                    upTarget = up,
                                    onFocusChange = { f -> focus.onCard(key, f) },
                                    onClick = { onSeeAll(Kind.CHANNELS) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (movies.isNotEmpty()) {
            item(key = "movies") {
                val up = if (first == Kind.MOVIES) focus.fieldRequester else null
                ResultSection(moviesTitle, moviesCount, Glyph.MOVIE, MoviesAccent, Blue, detailsHint, dims.gutter) {
                    MediaResultsRow(
                        kind = Kind.MOVIES,
                        entries = movies,
                        state = rows.movies,
                        query = query,
                        dims = dims,
                        glow = Blue,
                        placeholder = Glyph.MOVIE,
                        seeAll = seeAll,
                        seeAllCaption = seeAllMovies,
                        focus = focus,
                        upTarget = up,
                        onOpen = onMovie,
                        onSeeAll = { onSeeAll(Kind.MOVIES) },
                    )
                }
            }
        }
        if (series.isNotEmpty()) {
            item(key = "series") {
                val up = if (first == Kind.SERIES) focus.fieldRequester else null
                ResultSection(seriesTitle, seriesCount, Glyph.SERIES, SeriesAccent, Violet, detailsHint, dims.gutter) {
                    MediaResultsRow(
                        kind = Kind.SERIES,
                        entries = series,
                        state = rows.series,
                        query = query,
                        dims = dims,
                        glow = Violet,
                        placeholder = Glyph.SERIES,
                        seeAll = seeAll,
                        seeAllCaption = seeAllSeries,
                        focus = focus,
                        upTarget = up,
                        onOpen = onSeries,
                        onSeeAll = { onSeeAll(Kind.SERIES) },
                    )
                }
            }
        }
    }
}

@Composable
private fun countLabel(size: Int): String =
    if (size >= RESULT_CAP) stringResource(R.string.search_count_capped, RESULT_CAP) else size.toString()

/** Title of a result row: accent bar, icon, title, count pill and (TV) what OK does. */
@Composable
private fun ResultSection(
    title: String,
    count: String,
    glyph: Glyph,
    accent: Brush,
    tint: Color,
    action: String?,
    gutter: Dp,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
            AppIcon(glyph, tint, size = 20.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Text(
                count,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .background(GlassFill, PillShape)
                    .border(1.dp, GlassBorder, PillShape)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
            if (action != null) {
                Spacer(Modifier.weight(1f))
                Text(action, color = Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
        content()
    }
}

@Composable
private fun MediaResultsRow(
    kind: Kind,
    entries: List<MediaItem>,
    state: LazyListState,
    query: String,
    dims: Dims,
    glow: Color,
    placeholder: Glyph,
    seeAll: String,
    seeAllCaption: String,
    focus: SearchFocus,
    upTarget: FocusRequester?,
    onOpen: (MediaItem) -> Unit,
    onSeeAll: () -> Unit,
) {
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = dims.gutter, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(entries, key = { it.id }) { item ->
            val key = kind.prefix + item.id
            PosterResultCard(
                item = item,
                query = query,
                width = dims.posterWidth,
                glow = glow,
                placeholder = placeholder,
                focusRequester = focus.requester(key),
                upTarget = upTarget,
                onFocusChange = { f -> focus.onCard(key, f) },
                onClick = { onOpen(item) },
            )
        }
        if (entries.size >= RESULT_CAP) {
            item(key = "all") {
                val key = kind.prefix + "all"
                SeeAllCard(
                    label = seeAll,
                    caption = seeAllCaption,
                    width = dims.posterWidth,
                    height = dims.posterWidth * 1.5f,
                    horizontal = false,
                    focusRequester = focus.requester(key),
                    upTarget = upTarget,
                    onFocusChange = { f -> focus.onCard(key, f) },
                    onClick = onSeeAll,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ cards

/** A channel: logo, name (matched letters highlighted), programme on air and its progress. */
@Composable
private fun ChannelResultCard(
    row: ChannelRow,
    query: String,
    nowSec: Long,
    width: Dp,
    height: Dp,
    noProgramme: String,
    focusRequester: FocusRequester,
    upTarget: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var failed by remember(row.logo) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "channelCard")
    val interaction = remember { MutableInteractionSource() }
    val title = remember(row.name, query) { highlight(row.name, query) }
    val fraction = liveFraction(row, nowSec)
    val well = height - 20.dp
    Row(
        Modifier
            .width(width)
            .height(height)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusHalo(focused, Cyan, 16.dp)
            .clip(CardShape)
            .background(if (focused) ChannelFocusBrush else GlassBrush)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else GlassBorder, CardShape)
            .tvCard(interaction, focusRequester, upTarget, { f ->
                focused = f
                onFocusChange(f)
            }, onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(well)
                .clip(WellShape)
                .background(LogoWell),
            contentAlignment = Alignment.Center,
        ) {
            val logo = row.logo
            if (!logo.isNullOrBlank() && !failed) {
                AsyncImage(
                    model = logo,
                    contentDescription = row.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    onError = { failed = true },
                )
            } else {
                Box(
                    Modifier
                        .size(well * 0.62f)
                        .background(FluidBrush, WellShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initials(row.name), color = FluideColors.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
            val number = row.number
            if (number != null) {
                Text(
                    number.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), PillShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(Warm, CircleShape),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (row.catchupDays > 0) {
                    Spacer(Modifier.width(6.dp))
                    AppIcon(Glyph.REPLAY, Cyan, size = 14.dp)
                }
                if (row.favorite) {
                    Spacer(Modifier.width(6.dp))
                    AppIcon(Glyph.STAR, Amber, size = 13.dp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                row.nowTitle?.takeIf { it.isNotBlank() } ?: noProgramme,
                color = if (focused) Soft else Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (fraction != null) {
                Spacer(Modifier.height(8.dp))
                Stripe(fraction, LiveBar, Modifier.fillMaxWidth())
            }
        }
    }
}

/** A movie or a series: 2:3 poster with its rating, name (matched letters highlighted), year and group. */
@Composable
private fun PosterResultCard(
    item: MediaItem,
    query: String,
    width: Dp,
    glow: Color,
    placeholder: Glyph,
    focusRequester: FocusRequester,
    upTarget: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var failed by remember(item.poster) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "posterCard")
    val interaction = remember { MutableInteractionSource() }
    val title = remember(item.name, query) { highlight(item.name, query) }
    val rating = remember(item.rating) { ratingLabel(item.rating) }
    val meta = remember(item.year, item.group) {
        listOfNotNull(item.year?.toString(), item.group?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }
    Column(
        Modifier
            .width(width)
            .zIndex(if (focused) 1f else 0f)
            .tvCard(interaction, focusRequester, upTarget, { f ->
                focused = f
                onFocusChange(f)
            }, onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .focusHalo(focused, glow, 16.dp)
                .clip(CardShape)
                .background(PosterPlaceholder)
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else GlassBorder, CardShape),
        ) {
            val poster = item.poster
            if (poster.isNullOrBlank() || failed) {
                Column(
                    Modifier.align(Alignment.Center).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(placeholder, Muted, size = 28.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        item.name,
                        color = Soft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                AsyncImage(
                    model = poster,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { failed = true },
                )
            }
            if (item.progress > 0.01f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(PosterShade),
                )
                Stripe(
                    item.progress,
                    ProgressBar,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp),
                )
            }
            if (rating != null) RatingBadge(rating, Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (item.favorite) {
                Text(
                    "♥",
                    color = Rose,
                    fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            color = if (focused) Color.White else Soft,
            fontSize = 14.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotEmpty()) {
            Text(meta, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Last card of a full row: opens the section's own screen with the same query. */
@Composable
private fun SeeAllCard(
    label: String,
    caption: String,
    width: Dp,
    height: Dp,
    horizontal: Boolean,
    focusRequester: FocusRequester,
    upTarget: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "seeAll")
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .width(width)
            .height(height)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusHalo(focused, Cyan, 16.dp)
            .clip(CardShape)
            .background(if (focused) FluidBrush else GlassBrush)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else GlassBorder, CardShape)
            .tvCard(interaction, focusRequester, upTarget, { f ->
                focused = f
                onFocusChange(f)
            }, onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Over the full gradient while focused: white, or dark on the light accents of some themes.
        val captionColor = if (focused) FluideColors.OnAccent else Muted
        val labelColor = if (focused) FluideColors.OnAccent else Color.White
        if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeeAllBubble(labelColor)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(label, color = labelColor, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(caption, color = captionColor, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SeeAllBubble(labelColor)
                Spacer(Modifier.height(10.dp))
                Text(label, color = labelColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(caption, color = captionColor, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
private fun SeeAllBubble(tint: Color = Color.White) {
    Box(
        Modifier
            .size(40.dp)
            .background(Color.White.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(Glyph.FORWARD, tint, size = 18.dp)
    }
}

@Composable
private fun RatingBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.6f), PillShape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(Glyph.STAR, Amber, size = 11.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Stripe(fraction: Float, brush: Brush, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                .background(brush),
        )
    }
}

// ------------------------------------------------------------------ hint, empty and loading states

/** Vertically centred, scrollable column for the states without results. */
@Composable
private fun CenteredPanel(dims: Dims, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = dims.gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** Empty query: what can be searched, the size of the catalogue and a few examples to try. */
@Composable
private fun HintPanel(
    dims: Dims,
    catalog: String?,
    hasSource: Boolean,
    onExample: (String) -> Unit,
    onAddSource: () -> Unit,
) {
    val examples = stringArrayResource(R.array.search_examples)
    CenteredPanel(dims) {
        SearchOrb(if (dims.compact) 96.dp else 128.dp, muted = false)
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.search_hint_title),
            color = Color.White,
            fontSize = if (dims.compact) 22.sp else 28.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.search_hint_body),
            color = Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 620.dp),
        )
        if (catalog != null) {
            Spacer(Modifier.height(10.dp))
            Text(catalog, color = Cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScopeTag(Glyph.TV, stringResource(R.string.search_section_channels), Warm)
            ScopeTag(Glyph.MOVIE, stringResource(R.string.search_section_movies), Blue)
            ScopeTag(Glyph.SERIES, stringResource(R.string.search_section_series), Violet)
        }
        Spacer(Modifier.height(26.dp))
        if (hasSource) {
            Text(
                stringResource(R.string.search_try).uppercase(),
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                examples.forEach { word ->
                    TvButton(word, onClick = { onExample(word) }, glyph = Glyph.SEARCH)
                }
            }
            if (!dims.compact) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.search_remote_hint),
                    color = Muted.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                stringResource(R.string.search_no_source),
                color = Soft,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            Spacer(Modifier.height(16.dp))
            TvButton(stringResource(R.string.search_add_source), onClick = onAddSource, glyph = Glyph.PLAYLIST, primary = true)
        }
    }
}

@Composable
private fun ScopeTag(glyph: Glyph, label: String, tint: Color) {
    Row(
        Modifier
            .background(GlassFill, PillShape)
            .border(1.dp, GlassBorder, PillShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(glyph, tint, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Soft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** No result: a friendly message and a button that clears the query and goes back to the field. */
@Composable
private fun EmptyPanel(query: String, dims: Dims, focus: SearchFocus, onRetry: () -> Unit) {
    CenteredPanel(dims) {
        SearchOrb(if (dims.compact) 88.dp else 112.dp, muted = true)
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(R.string.search_empty_title, query),
            color = Color.White,
            fontSize = if (dims.compact) 20.sp else 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.search_empty_body),
            color = Muted,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(24.dp))
        TvButton(
            stringResource(R.string.search_empty_action),
            onClick = onRetry,
            modifier = Modifier.onFocusChanged { focus.retryFocused = it.isFocused },
            glyph = Glyph.SEARCH,
            primary = true,
            focusRequester = focus.retryRequester,
        )
    }
}

/** First search, nothing to show yet: two pulsing placeholder rows (not focusable). */
@Composable
private fun SkeletonResults(dims: Dims) {
    val pulse = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 850), RepeatMode.Reverse),
        label = "skeletonPulse",
    )
    Column(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer { alpha = pulse.value },
    ) {
        SkeletonHeader(dims.gutter)
        LazyRow(
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = dims.gutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(6) {
                Box(
                    Modifier
                        .width(dims.channelWidth)
                        .height(dims.channelHeight)
                        .background(GlassFill, CardShape)
                        .border(1.dp, GlassBorder, CardShape),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        SkeletonHeader(dims.gutter)
        LazyRow(
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = dims.gutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(10) {
                Box(
                    Modifier
                        .width(dims.posterWidth)
                        .aspectRatio(2f / 3f)
                        .background(GlassFill, CardShape)
                        .border(1.dp, GlassBorder, CardShape),
                )
            }
        }
    }
}

@Composable
private fun SkeletonHeader(gutter: Dp) {
    Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(22.dp)
                .background(GlassFill, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .width(150.dp)
                .height(16.dp)
                .background(GlassFill, RoundedCornerShape(8.dp)),
        )
    }
}

/** The search mark of the empty states: a glowing disc in a slowly turning gradient ring. */
@Composable
private fun SearchOrb(diameter: Dp, muted: Boolean) {
    val spin = rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 9_000, easing = LinearEasing)),
        label = "orbSpin",
    )
    val glow = if (muted) Violet else Blue
    val ring = if (muted) MutedRing else BrandRing
    Box(
        Modifier
            .size(diameter)
            .drawBehind {
                val radius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(listOf(glow.copy(alpha = 0.32f), Color.Transparent), center = center, radius = radius),
                    radius = radius,
                    center = center,
                )
                rotate(spin.value, center) {
                    drawCircle(
                        brush = Brush.sweepGradient(ring, center = center),
                        radius = radius * 0.8f,
                        center = center,
                        style = Stroke(width = 3.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(diameter * 0.56f)
                .clip(CircleShape)
                .background(if (muted) GlassBrush else FluidBrush),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(Glyph.SEARCH, if (muted) Soft else FluideColors.OnAccent, size = diameter * 0.24f)
        }
    }
}

// ------------------------------------------------------------------ helpers

/** D-pad focus (with an optional ↑ destination), OK and touch, without ripple: cards draw their own focus. */
private fun Modifier.tvCard(
    interaction: MutableInteractionSource,
    focusRequester: FocusRequester,
    upTarget: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
): Modifier = this
    .focusRequester(focusRequester)
    .focusProperties { if (upTarget != null) up = upTarget }
    .onFocusChanged { onFocusChange(it.isFocused) }
    .clickable(interactionSource = interaction, indication = null, onClick = onClick)

/** Soft halo around a focused element (drawn on every API level, unlike colored shadows). */
private fun Modifier.focusHalo(active: Boolean, color: Color, corner: Dp): Modifier = drawBehind {
    if (!active) return@drawBehind
    val radius = corner.toPx()
    for (i in 1..3) {
        val spread = i * 4.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.24f / i),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(radius + spread),
        )
    }
}

/**
 * Requests the focus over a few frames: lazy rows compose their items during layout, so the target
 * may not be attached yet on the first frame. Stops as soon as [done] is true.
 */
private suspend fun focusSoon(requester: FocusRequester, done: () -> Boolean): Boolean {
    repeat(8) {
        withFrameNanos { }
        if (done()) return true
        runCatching { requester.requestFocus() }
    }
    withFrameNanos { }
    return done()
}

private suspend fun LazyListState.toStart() {
    if (firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset != 0) scrollToItem(0)
}

private fun LazyListState.position(): IntArray = intArrayOf(firstVisibleItemIndex, firstVisibleItemScrollOffset)

/** [text] with the words of [query] found in it (case-insensitive) in bold cyan. */
private fun highlight(text: String, query: String): AnnotatedString {
    val words = query.trim().split(' ', '\t').filter { it.isNotEmpty() }
    val marked = BooleanArray(text.length)
    for (word in words) {
        var from = 0
        while (from < text.length) {
            val at = text.indexOf(word, from, ignoreCase = true)
            if (at < 0) break
            val end = minOf(text.length, at + word.length)
            for (i in at until end) marked[i] = true
            from = end
        }
    }
    return buildAnnotatedString {
        var start = 0
        while (start < text.length) {
            val on = marked[start]
            var end = start
            while (end < text.length && marked[end] == on) end++
            val part = text.substring(start, end)
            if (on) {
                withStyle(MatchStyle) { append(part) }
            } else {
                append(part)
            }
            start = end
        }
    }
}

/** Part of the programme on air already elapsed, null without guide data. */
private fun liveFraction(row: ChannelRow, nowSec: Long): Float? =
    if (row.nowStopSec > row.nowStartSec && nowSec >= row.nowStartSec && nowSec < row.nowStopSec) {
        (nowSec - row.nowStartSec).toFloat() / (row.nowStopSec - row.nowStartSec)
    } else {
        null
    }

/** "7,4" (ratings out of 100 are brought back to 10), null without a rating. */
private fun ratingLabel(rating: Double?): String? {
    if (rating == null || rating.isNaN() || rating <= 0.0) return null
    val value = if (rating > 10.0) rating / 10.0 else rating
    return String.format(Locale.getDefault(), "%.1f", value)
}

private fun initials(name: String): String =
    name.split(' ', '-', '_', '|', '.', ':')
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "TV" }

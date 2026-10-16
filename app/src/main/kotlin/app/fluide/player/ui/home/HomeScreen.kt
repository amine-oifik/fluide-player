package app.fluide.player.ui.home

import android.os.Build
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.ImportPhase
import app.fluide.player.data.ImportProgress
import app.fluide.player.data.MediaFilter
import app.fluide.player.data.MediaItem
import app.fluide.player.data.Section
import app.fluide.player.data.SourceInfo
import app.fluide.player.data.SourceType
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideLogo
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ palette (shared design language)

private val Navy = Color(0xFF070B1C)
private val Blue = Color(0xFF3D7BFF)
private val Cyan = Color(0xFF22D3EE)
private val Violet = Color(0xFF7B3DFF)
private val Warm = Color(0xFFFF6A3D)
private val Mint = Color(0xFF34D399)
private val Soft = Color(0xFFDCE3F2)
private val Muted = Color(0xFFB7C0D6)
private val GlassFill = Color.White.copy(alpha = 0.07f)
private val GlassBorder = Color.White.copy(alpha = 0.10f)
private val GlassBrush = SolidColor(GlassFill)

/** Opaque surface of a focused glass element (the glow is drawn behind it). */
private val FocusedSurface = Brush.linearGradient(listOf(Color(0xFF1C2D63), Color(0xFF141E47)))
private val FluidBrush = Brush.linearGradient(listOf(Blue, Cyan))
private val PrimaryBrush = Brush.linearGradient(listOf(Blue, Color(0xFF2F63E0)))
private val ProgressBrush = Brush.horizontalGradient(listOf(Blue, Cyan))
private val WarmBar = Brush.horizontalGradient(listOf(Warm, Color(0xFFFFB13D)))
private val LiveBrush = Brush.linearGradient(listOf(Color(0xFFFF8A3D), Warm, Color(0xFFE0306A)))
private val MoviesBrush = Brush.linearGradient(listOf(Color(0xFF5A93FF), Blue, Color(0xFF2341B5)))
private val SeriesBrush = Brush.linearGradient(listOf(Violet, Color(0xFF5560F2), Cyan))
private val ReplayBrush = Brush.linearGradient(listOf(Color(0xFF17C3D8), Color(0xFF0F7FB8), Color(0xFF283C9E)))
private val ExitBrush = Brush.linearGradient(listOf(Warm, Color(0xFFE0306A)))
private val PosterPlaceholder = Brush.linearGradient(listOf(Color(0xFF1A2550), Color(0xFF151A3A), Color(0xFF241445)))
private val WelcomeBrush = Brush.linearGradient(listOf(Color(0xFF15245A), Color(0xFF0D1534), Color(0xFF1E1042)))

// ------------------------------------------------------------------ focus keys

private const val KEY_LIVE = "tile:live"
private const val KEY_MOVIES = "tile:movies"
private const val KEY_SERIES = "tile:series"
private const val KEY_REPLAY = "tile:replay"
private const val KEY_SEARCH = "top:search"
private const val KEY_PLAYLIST = "top:playlist"
private const val KEY_HERO = "hero:open"
private const val KEY_HERO_ALL = "hero:all"
private const val KEY_WELCOME = "welcome:add"
private const val KEY_UTIL_SOURCES = "util:sources"
private const val KEY_UTIL_SETTINGS = "util:settings"
private const val KEY_UTIL_REFRESH = "util:refresh"
private const val KEY_UTIL_EXIT = "util:exit"
private const val KEY_ACCOUNT = "footer:account"

private const val ROW_CONTINUE = "continue"
private const val ROW_FAVORITES = "favorites"
private const val ROW_RECENT_CHANNELS = "recentChannels"
private const val ROW_MOVIES = "recentMovies"
private const val ROW_SERIES = "recentSeries"

/** Hero and welcome card: on a 960 x 540 dp TV, the top bar, the hero and the tiles fit on the first screen. */
private val HeroHeight = 260.dp

/** Index of the section tiles in the page (the hero or the welcome card comes first). */
private const val TILES_INDEX = 1

/**
 * Where the user was on the home screen, kept while the app runs: coming back from a details page,
 * the player, the search or the settings lands on the same card, at the same scroll position. Only
 * used by the same view model (a new start of the app opens on the Live TV tile) and the same playlist.
 */
private object HomeMemory {
    var owner = 0
    var sourceId = -1L
    var focusKey: String? = null
    var listIndex = 0
    var listOffset = 0
    val rows = HashMap<String, IntArray>()
}

/** A movie or a series of a home row. */
private data class HomeEntry(val item: MediaItem, val series: Boolean) {
    val key: String get() = (if (series) "s" else "m") + item.id
}

private class TileSpec(
    val key: String,
    val title: String,
    val caption: String?,
    val glyph: Glyph,
    val brush: Brush,
    val glow: Color,
    val focusRequester: FocusRequester?,
    val live: Boolean,
    val weight: Float,
    val onClick: () -> Unit,
)

/**
 * Home: a hero banner (the movie being watched, else the latest one), the four sections as large
 * tiles, the personalised rows (continue watching, favorite and recent channels, recently added
 * movies and series), the shortcuts and the account of the active playlist. Everything is reachable
 * with the D-pad; the Live TV tile has the focus when the screen opens.
 */
@Composable
fun HomeScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val feed by vm.homeFeed.state.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == ui.activeSourceId }
    val running = progress?.takeIf { it.sourceId == ui.activeSourceId && !it.done }

    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    val expiresInDays = source?.expiresAtSec?.let { expires ->
        val left = expires - nowSec
        if (left >= 0) (left / 86_400L).toInt() else null
    }
    val expiringSoon = expiresInDays != null && expiresInDays <= 7

    // Focus and scroll memory.
    val owner = System.identityHashCode(vm)
    val sameSession = remember { HomeMemory.owner == owner && HomeMemory.sourceId == vm.ui.value.activeSourceId }
    val restoreKey = remember { if (sameSession) HomeMemory.focusKey else null }
    val restoreFocus = remember { FocusRequester() }
    val liveFocus = remember { FocusRequester() }
    val pageFocused = remember { mutableStateOf(false) }
    val focusedKey = remember { mutableStateOf<String?>(null) }
    // Read only by AmbientLight: a focus move never recomposes the whole page.
    val accent = remember { mutableStateOf(Warm) }
    val requester: (String) -> FocusRequester? = remember {
        { key: String -> if (key == restoreKey) restoreFocus else null }
    }
    val onFocus: (String, Color) -> Unit = remember {
        { key: String, color: Color ->
            focusedKey.value = key
            accent.value = color
            HomeMemory.owner = owner
            HomeMemory.sourceId = vm.ui.value.activeSourceId
            HomeMemory.focusKey = key
        }
    }

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (sameSession) HomeMemory.listIndex else 0,
        initialFirstVisibleItemScrollOffset = if (sameSession) HomeMemory.listOffset else 0,
    )
    val continueState = rememberRowState(ROW_CONTINUE, sameSession)
    val favoritesState = rememberRowState(ROW_FAVORITES, sameSession)
    val recentChannelsState = rememberRowState(ROW_RECENT_CHANNELS, sameSession)
    val moviesState = rememberRowState(ROW_MOVIES, sameSession)
    val seriesState = rememberRowState(ROW_SERIES, sameSession)

    val continueItems = remember(feed.continueMovies, feed.continueSeries) {
        (feed.continueMovies.map { HomeEntry(it, series = false) } + feed.continueSeries.map { HomeEntry(it, series = true) })
            .distinctBy { it.key }
    }
    val favoriteChannels = remember(feed.favoriteChannels) { feed.favoriteChannels.distinctBy { it.id } }
    val recentChannels = remember(feed.recentChannels) { feed.recentChannels.distinctBy { it.id } }
    val recentMovies = remember(feed.recentMovies) { feed.recentMovies.map { HomeEntry(it, series = false) }.distinctBy { it.key } }
    val recentSeries = remember(feed.recentSeries) { feed.recentSeries.map { HomeEntry(it, series = true) }.distinctBy { it.key } }

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1_000
        }
    }
    LaunchedEffect(Unit) {
        vm.refreshHome()
        val target = restoreKey
        val restored = target != null && target != KEY_LIVE &&
            focusSoon(restoreFocus) { pageFocused.value && focusedKey.value == target }
        if (!restored) focusLive(listState, liveFocus) { pageFocused.value && focusedKey.value == KEY_LIVE }
        // A refresh can remove the focused card (a movie watched to the end leaves "continue
        // watching"): the remote must never be left without a focused element.
        vm.homeFeed.state.collect {
            withFrameNanos { }
            withFrameNanos { }
            if (!pageFocused.value) focusLive(listState, liveFocus) { pageFocused.value }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            HomeMemory.owner = owner
            HomeMemory.sourceId = vm.ui.value.activeSourceId
            HomeMemory.listIndex = listState.firstVisibleItemIndex
            HomeMemory.listOffset = listState.firstVisibleItemScrollOffset
            HomeMemory.rows[ROW_CONTINUE] = continueState.position()
            HomeMemory.rows[ROW_FAVORITES] = favoritesState.position()
            HomeMemory.rows[ROW_RECENT_CHANNELS] = recentChannelsState.position()
            HomeMemory.rows[ROW_MOVIES] = moviesState.position()
            HomeMemory.rows[ROW_SERIES] = seriesState.position()
        }
    }

    ScreenSurface {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            val gutter = if (compact) 16.dp else 48.dp
            val posterWidth = if (compact) 116.dp else 132.dp
            val channelWidth = if (compact) 168.dp else 204.dp

            AmbientLight(accent)

            Column(Modifier.fillMaxSize().onFocusChanged { pageFocused.value = it.hasFocus }) {
                TopBar(
                    playlistName = source?.name,
                    status = when {
                        running != null -> Cyan
                        expiringSoon -> Warm
                        source != null -> Mint
                        else -> Muted
                    },
                    pulsing = running != null,
                    expiresInDays = expiresInDays?.takeIf { expiringSoon },
                    compact = compact,
                    gutter = gutter,
                    clock24h = settings.clock24h,
                    requester = requester,
                    onFocus = onFocus,
                    onPlaylists = vm::openSources,
                    onSearch = vm::openSearch,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item(key = "hero") {
                        val hero = feed.hero
                        if (hero != null) {
                            HeroBanner(
                                hero = hero,
                                compact = compact,
                                gutter = gutter,
                                requester = requester,
                                onFocus = onFocus,
                                onOpen = { vm.openMovieById(hero.id) },
                                onAllMovies = vm::openMovies,
                            )
                        } else {
                            WelcomeCard(
                                source = source,
                                compact = compact,
                                gutter = gutter,
                                requester = requester,
                                onFocus = onFocus,
                                onAddPlaylist = vm::openSources,
                            )
                        }
                    }
                    item(key = "tiles") {
                        SectionTiles(
                            source = source,
                            compact = compact,
                            gutter = gutter,
                            liveFocus = liveFocus,
                            requester = requester,
                            onFocus = onFocus,
                            onLive = { vm.openLive() },
                            onMovies = vm::openMovies,
                            onSeries = vm::openSeries,
                            onReplay = { vm.openLive(ChannelFilter.Catchup) },
                        )
                    }
                    if (continueItems.isNotEmpty()) {
                        item(key = ROW_CONTINUE) {
                            MediaRow(
                                title = stringResource(R.string.home_row_continue),
                                entries = continueItems,
                                state = continueState,
                                gutter = gutter,
                                cardWidth = posterWidth,
                                accent = FluidBrush,
                                keyPrefix = "continue:",
                                showBadge = true,
                                requester = requester,
                                onFocus = onFocus,
                                onOpen = { entry -> if (entry.series) vm.openSeriesById(entry.item.id) else vm.openMovieById(entry.item.id) },
                                onSeeAll = null,
                            )
                        }
                    }
                    if (favoriteChannels.isNotEmpty()) {
                        item(key = ROW_FAVORITES) {
                            ChannelsRow(
                                title = stringResource(R.string.home_row_favorite_channels),
                                rows = favoriteChannels,
                                state = favoritesState,
                                gutter = gutter,
                                cardWidth = channelWidth,
                                nowSec = nowSec,
                                keyPrefix = "fav:",
                                requester = requester,
                                onFocus = onFocus,
                                onPlay = { row -> vm.playChannelFromHome(row.id) },
                                onSeeAll = { vm.openLive(ChannelFilter.Favorites) },
                            )
                        }
                    }
                    if (recentChannels.isNotEmpty()) {
                        item(key = ROW_RECENT_CHANNELS) {
                            ChannelsRow(
                                title = stringResource(R.string.home_row_recent_channels),
                                rows = recentChannels,
                                state = recentChannelsState,
                                gutter = gutter,
                                cardWidth = channelWidth,
                                nowSec = nowSec,
                                keyPrefix = "recent:",
                                requester = requester,
                                onFocus = onFocus,
                                onPlay = { row -> vm.playChannelFromHome(row.id) },
                                onSeeAll = { vm.openLive(ChannelFilter.Recent) },
                            )
                        }
                    }
                    if (recentMovies.isNotEmpty()) {
                        item(key = ROW_MOVIES) {
                            MediaRow(
                                title = stringResource(R.string.home_row_recent_movies),
                                entries = recentMovies,
                                state = moviesState,
                                gutter = gutter,
                                cardWidth = posterWidth,
                                accent = MoviesBrush,
                                keyPrefix = "movies:",
                                showBadge = false,
                                requester = requester,
                                onFocus = onFocus,
                                onOpen = { entry -> vm.openMovieById(entry.item.id) },
                                onSeeAll = {
                                    vm.openMovies()
                                    vm.selectMediaFilter(Section.MOVIES, MediaFilter.RecentlyAdded)
                                },
                            )
                        }
                    }
                    if (recentSeries.isNotEmpty()) {
                        item(key = ROW_SERIES) {
                            MediaRow(
                                title = stringResource(R.string.home_row_recent_series),
                                entries = recentSeries,
                                state = seriesState,
                                gutter = gutter,
                                cardWidth = posterWidth,
                                accent = SeriesBrush,
                                keyPrefix = "series:",
                                showBadge = false,
                                requester = requester,
                                onFocus = onFocus,
                                onOpen = { entry -> vm.openSeriesById(entry.item.id) },
                                onSeeAll = {
                                    vm.openSeries()
                                    vm.selectMediaFilter(Section.SERIES, MediaFilter.RecentlyAdded)
                                },
                            )
                        }
                    }
                    item(key = "utilities") {
                        UtilityRow(
                            gutter = gutter,
                            requester = requester,
                            onFocus = onFocus,
                            onPlaylists = vm::openSources,
                            onSettings = vm::openSettings,
                            onRefresh = vm::refreshActive,
                            onExit = { vm.onBack() },
                        )
                    }
                    item(key = "footer") {
                        AccountPanel(
                            source = source,
                            running = running,
                            expiresInDays = expiresInDays,
                            gutter = gutter,
                            focusRequester = requester(KEY_ACCOUNT),
                            onFocused = { onFocus(KEY_ACCOUNT, Cyan) },
                            onClick = vm::openSources,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ focus helpers

@Composable
private fun rememberRowState(key: String, restore: Boolean): LazyListState {
    val saved = if (restore) HomeMemory.rows[key] else null
    return rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.getOrNull(0) ?: 0,
        initialFirstVisibleItemScrollOffset = saved?.getOrNull(1) ?: 0,
    )
}

private fun LazyListState.position(): IntArray = intArrayOf(firstVisibleItemIndex, firstVisibleItemScrollOffset)

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

/** Focus on the Live TV tile, scrolling back to the top of the page when it is out of the list window. */
private suspend fun focusLive(list: LazyListState, live: FocusRequester, done: () -> Boolean): Boolean {
    if (list.firstVisibleItemIndex > TILES_INDEX) list.scrollToItem(0)
    return focusSoon(live, done)
}

/** D-pad focus, OK and touch, without the default ripple: the cards draw their own focus state. */
private fun Modifier.tvFocusable(
    interaction: MutableInteractionSource,
    focusRequester: FocusRequester?,
    onFocusChange: (Boolean) -> Unit,
    onClick: () -> Unit,
): Modifier = this
    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
    .onFocusChanged { onFocusChange(it.isFocused) }
    .clickable(interactionSource = interaction, indication = null, onClick = onClick)

/** Soft halo around a focused element (drawn on every API level, unlike colored shadows). */
private fun Modifier.focusGlow(active: Boolean, color: Color, corner: Dp): Modifier = drawBehind {
    if (!active) return@drawBehind
    val radius = corner.toPx()
    for (i in 1..3) {
        val spread = i * 4.dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.26f / i),
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(radius + spread),
        )
    }
}

/**
 * Ambient light at the top right of the page: takes the color of the focused section, slowly. It
 * completes the blue and violet glows of [ScreenSurface].
 */
@Composable
private fun AmbientLight(accent: State<Color>) {
    val glow by animateColorAsState(targetValue = accent.value, animationSpec = tween(durationMillis = 700), label = "ambient")
    Box(Modifier.fillMaxSize().drawBehind { drawAmbient(glow) })
}

private fun DrawScope.drawAmbient(accent: Color) {
    val center = Offset(size.width * 0.88f, size.height * 0.02f)
    val radius = size.maxDimension.coerceAtLeast(1f) * 0.5f
    drawCircle(
        brush = Brush.radialGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center,
    )
}

private fun ratingText(rating: Double?): String? {
    if (rating == null || rating <= 0.0) return null
    val value = if (rating > 10) rating / 10 else rating
    return "★ " + String.format(Locale.getDefault(), "%.1f", value)
}

private fun initials(name: String): String =
    name.split(' ', '-', '_', '|', '.')
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "TV" }

// ------------------------------------------------------------------ top bar

@Composable
private fun TopBar(
    playlistName: String?,
    status: Color,
    pulsing: Boolean,
    expiresInDays: Int?,
    compact: Boolean,
    gutter: Dp,
    clock24h: Boolean,
    requester: (String) -> FocusRequester?,
    onFocus: (String, Color) -> Unit,
    onPlaylists: () -> Unit,
    onSearch: () -> Unit,
) {
    val name = playlistName ?: stringResource(R.string.home_no_playlist)
    val warning = if (expiresInDays != null) stringResource(R.string.home_pill_expires, expiresInDays) else null
    val searchLabel = stringResource(R.string.home_search)
    Column(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = if (compact) 12.dp else 24.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FluideLogo(size = 36.dp, withWordmark = !compact)
            if (!compact) {
                Spacer(Modifier.width(24.dp))
                PlaylistPill(name, warning, status, pulsing, requester(KEY_PLAYLIST), { onFocus(KEY_PLAYLIST, Cyan) }, onPlaylists)
            }
            Spacer(Modifier.weight(1f))
            TvButton(
                text = searchLabel,
                onClick = onSearch,
                modifier = Modifier.onFocusChanged { if (it.isFocused) onFocus(KEY_SEARCH, Cyan) },
                glyph = Glyph.SEARCH,
                focusRequester = requester(KEY_SEARCH),
            )
            Spacer(Modifier.width(if (compact) 14.dp else 24.dp))
            Clock(clock24h, showDate = !compact)
        }
        if (compact) {
            Spacer(Modifier.height(12.dp))
            PlaylistPill(name, warning, status, pulsing, requester(KEY_PLAYLIST), { onFocus(KEY_PLAYLIST, Cyan) }, onPlaylists)
        }
    }
}

/** Active playlist in a glass pill; the dot tells its state (importing, expiring soon, ready). OK opens the playlists. */
@Composable
private fun PlaylistPill(
    name: String,
    warning: String?,
    status: Color,
    pulsing: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "pill")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .widthIn(max = 380.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusGlow(focused, Cyan, 24.dp)
            .tvFocusable(interaction, focusRequester, { f ->
                if (f && !focused) onFocused()
                focused = f
            }, onClick)
            .clip(shape)
            .background(if (focused) FocusedSurface else GlassBrush)
            .border(if (focused) 2.dp else 1.dp, if (focused) Cyan else GlassBorder, shape)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(status, pulsing)
        Spacer(Modifier.width(10.dp))
        AppIcon(Glyph.PLAYLIST, Color.White.copy(alpha = 0.85f), size = 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (warning != null) {
            Spacer(Modifier.width(10.dp))
            Text(warning, color = Warm, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    if (pulsing) {
        val transition = rememberInfiniteTransition(label = "status")
        val pulse by transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(animation = tween(durationMillis = 700), repeatMode = RepeatMode.Reverse),
            label = "statusPulse",
        )
        Box(
            Modifier
                .size(10.dp)
                .graphicsLayer { alpha = pulse }
                .background(color, CircleShape),
        )
    } else {
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

// ------------------------------------------------------------------ hero

@Composable
private fun HeroBanner(
    hero: MediaItem,
    compact: Boolean,
    gutter: Dp,
    requester: (String) -> FocusRequester?,
    onFocus: (String, Color) -> Unit,
    onOpen: () -> Unit,
    onAllMovies: () -> Unit,
) {
    val resume = hero.progress > 0f
    val percent = (hero.progress * 100).toInt().coerceIn(1, 99)
    val tag = stringResource(if (resume) R.string.home_hero_tag_resume else R.string.home_hero_tag_new)
    val action = stringResource(if (resume) R.string.home_hero_resume else R.string.home_hero_watch)
    val allMovies = stringResource(R.string.home_hero_all_movies)
    val watched = if (resume) stringResource(R.string.home_hero_watched, percent) else null
    val meta = listOfNotNull(hero.year?.toString(), ratingText(hero.rating), hero.group?.takeIf { it.isNotBlank() }, watched)
        .joinToString("  ·  ")
    // Before Android 12 the blur is a no-op: a denser scrim keeps the text readable over the sharp poster.
    val sharpBackground = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .padding(horizontal = gutter)
            .fillMaxWidth()
            .height(HeroHeight)
            .clip(shape)
            .background(Navy)
            .border(1.dp, GlassBorder, shape),
    ) {
        if (hero.poster != null) {
            AsyncImage(
                model = hero.poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.2f
                        scaleY = 1.2f
                    }
                    .blur(28.dp),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(
                        Navy.copy(alpha = if (sharpBackground) 0.97f else 0.9f),
                        Navy.copy(alpha = if (sharpBackground) 0.84f else 0.55f),
                        Navy.copy(alpha = if (sharpBackground) 0.64f else 0.18f),
                    ),
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Navy.copy(alpha = 0.75f)))))
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Blue.copy(alpha = 0.2f), Color.Transparent, Violet.copy(alpha = 0.16f)))))
        Row(Modifier.fillMaxSize().padding(if (compact) 20.dp else 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                TagChip(tag)
                Spacer(Modifier.height(10.dp))
                Text(
                    hero.name,
                    color = Color.White,
                    fontSize = if (compact) 22.sp else 28.sp,
                    lineHeight = if (compact) 27.sp else 33.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (meta.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(meta, color = Soft, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (resume) {
                    Spacer(Modifier.height(10.dp))
                    ProgressStripe(hero.progress, ProgressBrush, Modifier.width(200.dp))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PillButton(
                        text = action,
                        glyph = Glyph.PLAY,
                        onClick = onOpen,
                        onFocused = { onFocus(KEY_HERO, Cyan) },
                        focusRequester = requester(KEY_HERO),
                        primary = true,
                    )
                    if (!compact) {
                        PillButton(
                            text = allMovies,
                            glyph = Glyph.MOVIE,
                            onClick = onAllMovies,
                            onFocused = { onFocus(KEY_HERO_ALL, Blue) },
                            focusRequester = requester(KEY_HERO_ALL),
                        )
                    }
                }
            }
            if (!compact && hero.poster != null) {
                Spacer(Modifier.width(28.dp))
                val posterShape = RoundedCornerShape(16.dp)
                Box(
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(2f / 3f)
                        .clip(posterShape)
                        .background(PosterPlaceholder)
                        .border(1.dp, Color.White.copy(alpha = 0.18f), posterShape),
                ) {
                    AsyncImage(model = hero.poster, contentDescription = hero.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** No movie to feature: a greeting on the brand gradient, with the way to add a playlist when there is none. */
@Composable
private fun WelcomeCard(
    source: SourceInfo?,
    compact: Boolean,
    gutter: Dp,
    requester: (String) -> FocusRequester?,
    onFocus: (String, Color) -> Unit,
    onAddPlaylist: () -> Unit,
) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        },
    )
    val title = stringResource(R.string.home_welcome_title)
    val body = stringResource(if (source == null) R.string.home_welcome_no_source else R.string.home_welcome_text)
    val add = stringResource(R.string.home_add_playlist)
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .padding(horizontal = gutter)
            .fillMaxWidth()
            .height(HeroHeight)
            .clip(shape)
            .background(WelcomeBrush)
            .border(1.dp, GlassBorder, shape),
    ) {
        // Fainter on phones, where the text spans the whole card.
        FlowLines(Modifier.fillMaxSize(), strength = if (compact) 0.45f else 1f)
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(if (compact) 1f else 0.62f)
                .padding(if (compact) 20.dp else 30.dp),
        ) {
            Text(greeting, color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                color = Color.White,
                fontSize = if (compact) 24.sp else 28.sp,
                lineHeight = if (compact) 29.sp else 33.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(body, color = Soft, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (source == null) {
                Spacer(Modifier.height(16.dp))
                PillButton(
                    text = add,
                    glyph = Glyph.PLAYLIST,
                    onClick = onAddPlaylist,
                    onFocused = { onFocus(KEY_WELCOME, Cyan) },
                    focusRequester = requester(KEY_WELCOME),
                    primary = true,
                )
            }
        }
    }
}

/** The brand's flowing lines, drawn on the right of the welcome card. */
@Composable
private fun FlowLines(modifier: Modifier = Modifier, strength: Float = 1f) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val brush = Brush.linearGradient(listOf(Blue.copy(alpha = 0f), Blue, Cyan), start = Offset(w * 0.35f, h), end = Offset(w, 0f))
        for (i in 0 until 5) {
            val y = h * (0.18f + i * 0.16f)
            val path = Path().apply {
                moveTo(w * 0.34f, y + h * 0.2f)
                cubicTo(w * 0.52f, y - h * 0.28f, w * 0.74f, y + h * 0.34f, w * 1.04f, y - h * 0.12f)
            }
            drawPath(
                path = path,
                brush = brush,
                alpha = (0.55f - i * 0.08f) * strength,
                style = Stroke(width = (5f - i * 0.7f).dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun TagChip(text: String) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .background(Cyan.copy(alpha = 0.14f), shape)
            .border(1.dp, Cyan.copy(alpha = 0.4f), shape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(Cyan, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

// ------------------------------------------------------------------ section tiles

@Composable
private fun SectionTiles(
    source: SourceInfo?,
    compact: Boolean,
    gutter: Dp,
    liveFocus: FocusRequester,
    requester: (String) -> FocusRequester?,
    onFocus: (String, Color) -> Unit,
    onLive: () -> Unit,
    onMovies: () -> Unit,
    onSeries: () -> Unit,
    onReplay: () -> Unit,
) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val channels = source?.channelCount ?: 0
    val movies = source?.movieCount ?: 0
    val series = source?.seriesCount ?: 0
    val liveCaption = if (channels > 0) pluralStringResource(R.plurals.home_tile_channels, channels, numbers.format(channels.toLong())) else null
    val moviesCaption = if (movies > 0) pluralStringResource(R.plurals.home_tile_movies, movies, numbers.format(movies.toLong())) else null
    val seriesCaption = if (series > 0) pluralStringResource(R.plurals.home_tile_series, series, numbers.format(series.toLong())) else null
    val liveTitle = stringResource(R.string.home_live)
    val moviesTitle = stringResource(R.string.home_movies)
    val seriesTitle = stringResource(R.string.home_series)
    val replayTitle = stringResource(R.string.home_catchup)
    val replayCaption = stringResource(R.string.home_tile_replay_hint)
    val liveBadge = stringResource(R.string.epg_live_badge)
    val specs = listOf(
        TileSpec(KEY_LIVE, liveTitle, liveCaption, Glyph.TV, LiveBrush, Warm, liveFocus, live = true, weight = 1.3f, onClick = onLive),
        TileSpec(KEY_MOVIES, moviesTitle, moviesCaption, Glyph.MOVIE, MoviesBrush, Blue, requester(KEY_MOVIES), live = false, weight = 1f, onClick = onMovies),
        TileSpec(KEY_SERIES, seriesTitle, seriesCaption, Glyph.SERIES, SeriesBrush, Violet, requester(KEY_SERIES), live = false, weight = 1f, onClick = onSeries),
        TileSpec(KEY_REPLAY, replayTitle, replayCaption, Glyph.REPLAY, ReplayBrush, Cyan, requester(KEY_REPLAY), live = false, weight = 1f, onClick = onReplay),
    )
    val tileHeight = if (compact) 112.dp else 150.dp
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(horizontal = gutter), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for (pair in specs.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (spec in pair) {
                        SectionTile(spec, liveBadge, compact, { onFocus(spec.key, spec.glow) }, Modifier.weight(1f).height(tileHeight))
                    }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            for (spec in specs) {
                SectionTile(spec, liveBadge, compact, { onFocus(spec.key, spec.glow) }, Modifier.weight(spec.weight).height(tileHeight))
            }
        }
    }
}

@Composable
private fun SectionTile(spec: TileSpec, liveBadge: String, compact: Boolean, onFocused: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tile")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusGlow(focused, spec.glow, 20.dp)
            .tvFocusable(interaction, spec.focusRequester, { f ->
                if (f && !focused) onFocused()
                focused = f
            }, spec.onClick)
            .clip(shape)
            .background(spec.brush)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color.White.copy(alpha = 0.14f), shape),
    ) {
        // Gloss on top, shade at the bottom for the text.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.Transparent, Color.Black.copy(alpha = 0.22f))),
            ),
        )
        AppIcon(
            spec.glyph,
            Color.White.copy(alpha = 0.14f),
            Modifier.align(Alignment.BottomEnd).offset(x = 22.dp, y = 22.dp),
            size = if (compact) 96.dp else 118.dp,
        )
        Box(
            Modifier
                .align(Alignment.TopStart)
                .padding(if (compact) 14.dp else 18.dp)
                .size(if (compact) 40.dp else 48.dp)
                .background(Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(spec.glyph, Color.White, size = if (compact) 22.dp else 26.dp)
        }
        if (spec.live) LiveBadge(liveBadge, Modifier.align(Alignment.TopEnd).padding(if (compact) 12.dp else 16.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(if (compact) 14.dp else 18.dp)) {
            Text(
                spec.title,
                color = Color.White,
                fontSize = if (compact) 18.sp else 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (spec.caption != null) {
                Text(
                    spec.caption,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = if (compact) 12.sp else 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveBadge(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900), repeatMode = RepeatMode.Reverse),
        label = "livePulse",
    )
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse }
                .background(Color.White, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

// ------------------------------------------------------------------ content rows

@Composable
private fun RowHeader(title: String, accent: Brush, gutter: Dp) {
    Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(12.dp))
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MediaRow(
    title: String,
    entries: List<HomeEntry>,
    state: LazyListState,
    gutter: Dp,
    cardWidth: Dp,
    accent: Brush,
    keyPrefix: String,
    showBadge: Boolean,
    requester: (String) -> FocusRequester?,
    onFocus: (String, Color) -> Unit,
    onOpen: (HomeEntry) -> Unit,
    onSeeAll: (() -> Unit)?,
) {
    val movieLabel = stringResource(R.string.home_badge_movie)
    val seriesLabel = stringResource(R.string.home_badge_series)
    val seeAll = stringResource(R.string.home_see_all)
    Column(Modifier.fillMaxWidth()) {
        RowHeader(title, accent, gutter)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(entries, key = { it.key }) { entry ->
                val key = keyPrefix + entry.key
                val color = if (entry.series) Violet else Blue
                PosterCard(
                    item = entry.item,
                    badge = if (!showBadge) null else if (entry.series) seriesLabel else movieLabel,
                    width = cardWidth,
                    glowColor = color,
                    focusRequester = requester(key),
                    onFocused = { onFocus(key, color) },
                    onClick = { onOpen(entry) },
                )
            }
            if (onSeeAll != null) {
                item(key = "all") {
                    val key = keyPrefix + "all"
                    SeeAllCard(
                        label = seeAll,
                        width = cardWidth,
                        aspect = 2f / 3f,
                        focusRequester = requester(key),
                        onFocused = { onFocus(key, Cyan) },
                        onClick = onSeeAll,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelsRow(
    title: String,
    rows: List<ChannelRow>,
    state: LazyListState,
    gutter: Dp,
    cardWidth: Dp,
    nowSec: Long,
    keyPrefix: String,
    requester: (String) -> FocusRequester?,
    onFocus: (String, Color) -> Unit,
    onPlay: (ChannelRow) -> Unit,
    onSeeAll: () -> Unit,
) {
    val noProgramme = stringResource(R.string.home_no_programme)
    val seeAll = stringResource(R.string.home_see_all)
    Column(Modifier.fillMaxWidth()) {
        RowHeader(title, LiveBrush, gutter)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(rows, key = { it.id }) { row ->
                val key = keyPrefix + row.id
                ChannelCard(
                    row = row,
                    nowSec = nowSec,
                    width = cardWidth,
                    noProgramme = noProgramme,
                    focusRequester = requester(key),
                    onFocused = { onFocus(key, Warm) },
                    onClick = { onPlay(row) },
                )
            }
            item(key = "all") {
                val key = keyPrefix + "all"
                SeeAllCard(
                    label = seeAll,
                    width = cardWidth,
                    aspect = 16f / 9f,
                    focusRequester = requester(key),
                    onFocused = { onFocus(key, Cyan) },
                    onClick = onSeeAll,
                )
            }
        }
    }
}

@Composable
private fun PosterCard(
    item: MediaItem,
    badge: String?,
    width: Dp,
    glowColor: Color,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var failed by remember(item.poster) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "poster")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val meta = listOfNotNull(item.year?.toString(), ratingText(item.rating)).joinToString("  ·  ")
    Column(
        Modifier
            .width(width)
            .tvFocusable(interaction, focusRequester, { f ->
                if (f && !focused) onFocused()
                focused = f
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
                .focusGlow(focused, glowColor, 16.dp)
                .clip(shape)
                .background(PosterPlaceholder)
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else GlassBorder, shape),
        ) {
            val poster = item.poster
            if (poster == null || failed) {
                Text(
                    item.name,
                    color = Soft,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(12.dp),
                )
            } else {
                AsyncImage(
                    model = poster,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { failed = true },
                )
            }
            if (item.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
                )
                ProgressStripe(
                    item.progress,
                    ProgressBrush,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                )
            }
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (item.favorite) {
                Text("♥", color = Color(0xFFFF5C7A), fontSize = 15.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            item.name,
            color = if (focused) Color.White else Soft,
            fontSize = 14.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChannelCard(
    row: ChannelRow,
    nowSec: Long,
    width: Dp,
    noProgramme: String,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var failed by remember(row.logo) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "channel")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val fraction = if (row.nowStopSec > row.nowStartSec && nowSec >= row.nowStartSec && nowSec < row.nowStopSec) {
        (nowSec - row.nowStartSec).toFloat() / (row.nowStopSec - row.nowStartSec)
    } else {
        null
    }
    Column(
        Modifier
            .width(width)
            .tvFocusable(interaction, focusRequester, { f ->
                if (f && !focused) onFocused()
                focused = f
            }, onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .focusGlow(focused, Cyan, 16.dp)
                .clip(shape)
                .background(if (focused) FocusedSurface else GlassBrush)
                .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else GlassBorder, shape),
            contentAlignment = Alignment.Center,
        ) {
            val logo = row.logo
            if (logo != null && !failed) {
                AsyncImage(
                    model = logo,
                    contentDescription = row.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp),
                    onError = { failed = true },
                )
            } else {
                Box(Modifier.size(50.dp).background(FluidBrush, CircleShape), contentAlignment = Alignment.Center) {
                    Text(initials(row.name), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            val number = row.number
            if (number != null) {
                Text(
                    number.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(11.dp)
                    .size(8.dp)
                    .background(Warm, CircleShape),
            )
            if (fraction != null) {
                ProgressStripe(
                    fraction,
                    WarmBar,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            row.name,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            row.nowTitle?.takeIf { it.isNotBlank() } ?: noProgramme,
            color = if (focused) Soft else Muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Last card of a row: opens the full list behind it. Same footprint as the cards of the row. */
@Composable
private fun SeeAllCard(
    label: String,
    width: Dp,
    aspect: Float,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "seeAll")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .width(width)
            .aspectRatio(aspect)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusGlow(focused, Cyan, 16.dp)
            .tvFocusable(interaction, focusRequester, { f ->
                if (f && !focused) onFocused()
                focused = f
            }, onClick)
            .clip(shape)
            .background(if (focused) FluidBrush else GlassBrush)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else GlassBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(44.dp).background(Color.White.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(Glyph.FORWARD, Color.White, size = 20.dp)
            }
            Spacer(Modifier.height(10.dp))
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun ProgressStripe(fraction: Float, brush: Brush, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.22f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.03f, 1f))
                .background(brush),
        )
    }
}

// ------------------------------------------------------------------ shortcuts and account

@Composable
private fun UtilityRow(
    gutter: Dp,
    requester: (String) -> FocusRequester?,
    onFocus: (String, Color) -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
) {
    val playlists = stringResource(R.string.home_playlists)
    val settings = stringResource(R.string.home_settings)
    val refresh = stringResource(R.string.home_refresh)
    val exit = stringResource(R.string.home_exit)
    val pill = Modifier.widthIn(min = 168.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PillButton(
            text = playlists,
            glyph = Glyph.PLAYLIST,
            onClick = onPlaylists,
            onFocused = { onFocus(KEY_UTIL_SOURCES, Cyan) },
            modifier = pill,
            focusRequester = requester(KEY_UTIL_SOURCES),
        )
        PillButton(
            text = settings,
            glyph = Glyph.SETTINGS,
            onClick = onSettings,
            onFocused = { onFocus(KEY_UTIL_SETTINGS, Cyan) },
            modifier = pill,
            focusRequester = requester(KEY_UTIL_SETTINGS),
        )
        PillButton(
            text = refresh,
            glyph = Glyph.REFRESH,
            onClick = onRefresh,
            onFocused = { onFocus(KEY_UTIL_REFRESH, Cyan) },
            modifier = pill,
            focusRequester = requester(KEY_UTIL_REFRESH),
        )
        PillButton(
            text = exit,
            glyph = Glyph.EXIT,
            onClick = onExit,
            onFocused = { onFocus(KEY_UTIL_EXIT, Warm) },
            modifier = pill,
            focusRequester = requester(KEY_UTIL_EXIT),
            focusBrush = ExitBrush,
            glowColor = Warm,
        )
    }
}

/** Glass pill button (24 dp corners) with the brand gradient when focused. */
@Composable
private fun PillButton(
    text: String,
    glyph: Glyph?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    primary: Boolean = false,
    focusBrush: Brush = FluidBrush,
    glowColor: Color = Cyan,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "pillButton")
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(24.dp)
    val background = when {
        focused -> focusBrush
        primary -> PrimaryBrush
        else -> GlassBrush
    }
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusGlow(focused, glowColor, 24.dp)
            .tvFocusable(interaction, focusRequester, { f ->
                if (f && !focused) onFocused()
                focused = f
            }, onClick)
            .clip(shape)
            .background(background)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else GlassBorder, shape)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            AppIcon(glyph, Color.White, size = 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Account of the active playlist: counts, Xtream subscription, import in progress, expiry warning.
 * Focusable (OK opens the playlists) so that the remote can scroll down to it: it is the last item of
 * the page.
 */
@Composable
private fun AccountPanel(
    source: SourceInfo?,
    running: ImportProgress?,
    expiresInDays: Int?,
    gutter: Dp,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    if (source == null && running == null) return
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(horizontal = gutter)
            .fillMaxWidth()
            .focusGlow(focused, Cyan, 16.dp)
            .tvFocusable(interaction, focusRequester, { f ->
                if (f && !focused) onFocused()
                focused = f
            }, onClick)
            .clip(shape)
            .background(if (focused) FocusedSurface else GlassBrush)
            .border(if (focused) 2.dp else 1.dp, if (focused) Cyan else GlassBorder, shape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        if (source != null) {
            val type = stringResource(
                when (source.type) {
                    SourceType.XTREAM -> R.string.source_type_xtream
                    SourceType.URL -> R.string.source_type_m3u
                    SourceType.FILE -> R.string.source_type_file
                },
            )
            val parts = ArrayList<String>(6)
            parts += stringResource(R.string.home_counts, source.channelCount, source.movieCount, source.seriesCount)
            if (source.type == SourceType.XTREAM) {
                val expires = source.expiresAtSec
                parts += if (expires != null) {
                    stringResource(R.string.home_expires, DateFormat.getDateInstance(DateFormat.LONG).format(Date(expires * 1_000)))
                } else {
                    stringResource(R.string.home_unlimited)
                }
                val status = source.accountStatus
                if (status != null) parts += stringResource(R.string.home_status, status)
                val active = source.activeConnections
                if (active != null) parts += stringResource(R.string.home_connections, active, source.maxConnections)
            }
            parts += if (source.updatedAt > 0) {
                stringResource(R.string.source_updated, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(source.updatedAt)))
            } else {
                stringResource(R.string.source_never_updated)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    type,
                    color = Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier
                        .background(Cyan.copy(alpha = 0.14f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(source.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Text(parts.joinToString("   ·   "), color = Muted, fontSize = 14.sp)
        }
        if (running != null) {
            if (source != null) Spacer(Modifier.height(14.dp))
            Text(
                if (running.phase == ImportPhase.PLAYLIST) {
                    stringResource(R.string.import_running, running.count)
                } else {
                    stringResource(R.string.import_epg_running, running.count)
                },
                color = Cyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Cyan,
                trackColor = Color.White.copy(alpha = 0.10f),
            )
        }
        if (expiresInDays != null && expiresInDays <= 7) {
            val warningShape = RoundedCornerShape(12.dp)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(warningShape)
                    .background(Warm.copy(alpha = 0.14f))
                    .border(1.dp, Warm.copy(alpha = 0.5f), warningShape)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(22.dp).background(Warm, CircleShape), contentAlignment = Alignment.Center) {
                    Text("!", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.home_expires_soon, expiresInDays), color = Color(0xFFFFD2C2), fontSize = 14.sp)
            }
        }
    }
}

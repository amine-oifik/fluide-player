package app.fluide.player.ui.home

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ImportPhase
import app.fluide.player.data.ImportProgress
import app.fluide.player.data.MediaItem
import app.fluide.player.data.SourceInfo
import app.fluide.player.data.SourceType
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.FluideLogo
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.fluideGlass
import app.fluide.player.ui.theme.blend
import app.fluide.player.ui.theme.themed
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Calendar
import java.util.Date

// ------------------------------------------------------------------ focus keys

private const val KEY_LIVE = "live"
private const val KEY_MOVIES = "movies"
private const val KEY_SERIES = "series"
private const val KEY_REPLAY = "replay"
private const val KEY_GUIDE = "guide"
private const val KEY_MULTI = "multi"
private const val KEY_SEARCH = "search"
private const val KEY_ACCOUNT = "account"
private const val KEY_SWITCH = "switch"
private const val KEY_SETTINGS = "settings"
private const val KEY_REFRESH = "refresh"
private const val KEY_EXIT = "exit"
private const val KEY_ADD = "add"
private const val CONTINUE_PREFIX = "continue:"

/** Posters of the "continue watching" strip. */
private const val CONTINUE_MAX = 8

/** The subscription is flagged (warm colour, warning line) this many days before it expires. */
private const val WARN_DAYS = 7

/** Width of the big Live TV tile, in small tiles (its height is two small tiles). */
private const val BIG_RATIO = 1.7f

/** Kept free above and below the tiles for the focused tile (scale and glow). */
private val FOCUS_ROOM = 20.dp

// ------------------------------------------------------------------ shapes and themed fills

private val TileCorner = 14.dp
private val TileShape = RoundedCornerShape(TileCorner)
private val ButtonCorner = 12.dp
private val ButtonShape = RoundedCornerShape(ButtonCorner)
private val PosterCorner = 10.dp
private val PosterShape = RoundedCornerShape(PosterCorner)
private val PillShape = RoundedCornerShape(28.dp)

/**
 * Grid tiles: the accent deepened toward the background. Less on the light accents (amber, emerald),
 * whose tiles carry dark text, so that the text stays readable.
 */
private val GridFill: Brush by themed { p ->
    val depth = if (p.onAccent.luminance() < 0.5f) 0.16f else 0.36f
    Brush.linearGradient(listOf(blend(p.blue, p.navy, depth), blend(p.azure, p.navy, depth + 0.14f)))
}

/** Unfocused primary button (add a playlist). */
private val PrimaryFill: Brush by themed { p ->
    Brush.linearGradient(listOf(p.blue.copy(alpha = 0.55f), p.cyan.copy(alpha = 0.32f)))
}

private val GlassFill: Brush by themed { p -> SolidColor(p.glass) }

/** Behind a poster that is loading or missing. */
private val PosterFill: Brush by themed { p ->
    Brush.linearGradient(listOf(blend(p.blue, p.navy, 0.72f), blend(p.violet, p.navy, 0.78f)))
}

/** Soft light on the top of the tiles (neutral: drawn over every accent). */
private val TileGloss = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent))

private val PosterShade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))

// ------------------------------------------------------------------ models

/**
 * Where the focus was on the home screen, kept while the app runs: coming back from a section lands on
 * its tile. Only for the same view model and the same playlist (a new start opens on the Live TV tile).
 */
private object HomeMemory {
    var owner = 0
    var sourceId = -1L
    var focusKey: String? = null
}

/** A movie or a series of the "continue watching" strip. */
private data class HomeEntry(val item: MediaItem, val series: Boolean) {
    val key: String get() = (if (series) "s" else "m") + item.id
}

private class TileSpec(
    val key: String,
    val title: String,
    val caption: String?,
    val glyph: Glyph,
    val onClick: () -> Unit,
)

private class ActionSpec(
    val key: String,
    val label: String,
    val glyph: Glyph,
    val warm: Boolean = false,
    val onClick: () -> Unit,
)

/** Subscription end of the active playlist. [daysLeft] null when unknown, unlimited or [expired]. */
private class Expiry(val daysLeft: Int?, val expired: Boolean) {
    val warn: Boolean get() = expired || (daysLeft != null && daysLeft <= WARN_DAYS)
}

private fun expiryOf(expiresAtSec: Long?, nowSec: Long): Expiry {
    if (expiresAtSec == null) return Expiry(daysLeft = null, expired = false)
    val left = expiresAtSec - nowSec
    return if (left < 0) Expiry(daysLeft = null, expired = true) else Expiry(daysLeft = (left / 86_400L).toInt(), expired = false)
}

/** Everything the layouts draw, resolved once per composition of the screen. */
private class HomeModel(
    val source: SourceInfo?,
    /** No playlist (and the playlists are loaded): the welcome card replaces the tiles. */
    val welcome: Boolean,
    val live: TileSpec,
    val liveBadge: String,
    val lastChannel: String?,
    val grid: List<TileSpec>,
    val actions: List<ActionSpec>,
    val continueItems: List<HomeEntry>,
    val running: ImportProgress?,
    val expiry: Expiry,
)

/**
 * Sizes of the wide (TV) layout, from the space left between the top row and the bottom strip: the
 * big Live TV tile, the 3 x 2 grid of square tiles and the column of action buttons, side by side.
 */
private class TileMetrics(
    val tile: Dp,
    val gap: Dp,
    val outer: Dp,
    val bigWidth: Dp,
    val buttonsWidth: Dp,
    val buttonHeight: Dp,
    val buttonGap: Dp,
    val buttonFont: TextUnit,
) {
    val blockHeight: Dp get() = tile * 2 + gap
    val gridWidth: Dp get() = tile * 3 + gap * 2

    companion object {
        fun of(width: Dp, height: Dp): TileMetrics {
            val gap = if (width >= 760.dp) 16.dp else 12.dp
            val outer = gap + 8.dp
            val buttons = (width * 0.26f).coerceIn(190.dp, 280.dp)
            val byWidth = (width - buttons - outer * 2 - gap * 2) / (3f + BIG_RATIO)
            // Room above and below for the focused tile, which grows and glows over its neighbours.
            val byHeight = (height - gap - FOCUS_ROOM * 2) / 2f
            val tile = min(byWidth, byHeight).coerceIn(64.dp, 200.dp)
            val block = tile * 2 + gap
            val buttonGap = (block / 28f).coerceIn(6.dp, 14.dp)
            val buttonHeight = (block - buttonGap * 4) / 5f
            return TileMetrics(
                tile = tile,
                gap = gap,
                outer = outer,
                bigWidth = tile * BIG_RATIO,
                buttonsWidth = buttons,
                buttonHeight = buttonHeight,
                buttonGap = buttonGap,
                buttonFont = (buttonHeight.value * 0.34f).coerceIn(13f, 17f).sp,
            )
        }
    }
}

// ------------------------------------------------------------------ screen

/**
 * Home: big tiles readable from the couch. The Live TV tile (with the last channel watched), a grid of
 * six sections (movies, series, replay, TV guide, multi-screen, search), the account and playlist
 * actions, and a compact "continue watching" strip. Everything is reachable with the D-pad; the Live
 * TV tile has the focus when the app opens, and coming back from a section lands on its tile.
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
    // While the playlists load at start-up: neither the tiles nor the welcome card (no flash of "add a playlist").
    val welcome = source == null && !ui.loading

    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    val expiry = expiryOf(source?.expiresAtSec, nowSec)

    val continueItems = remember(feed.continueMovies, feed.continueSeries) {
        (feed.continueMovies.map { HomeEntry(it, series = false) } + feed.continueSeries.map { HomeEntry(it, series = true) })
            .distinctBy { it.key }
            .take(CONTINUE_MAX)
    }

    // Focus: one requester per element, the last focused element remembered.
    val owner = System.identityHashCode(vm)
    val restoreKey = remember {
        if (HomeMemory.owner == owner && HomeMemory.sourceId == vm.ui.value.activeSourceId) HomeMemory.focusKey else null
    }
    val requesters = remember { HashMap<String, FocusRequester>() }
    val requester: (String) -> FocusRequester = remember { { key: String -> requesters.getOrPut(key) { FocusRequester() } } }
    val pageFocused = remember { mutableStateOf(false) }
    val focusedKey = remember { mutableStateOf<String?>(null) }
    val onFocus: (String) -> Unit = remember {
        { key: String ->
            focusedKey.value = key
            HomeMemory.owner = owner
            HomeMemory.sourceId = vm.ui.value.activeSourceId
            HomeMemory.focusKey = key
        }
    }
    val mainKey = when {
        source != null -> KEY_LIVE
        welcome -> KEY_ADD
        else -> null
    }
    val currentMainKey by rememberUpdatedState(mainKey)

    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            nowSec = System.currentTimeMillis() / 1_000
        }
    }
    LaunchedEffect(Unit) {
        vm.refreshHome()
        val target = restoreKey
        val restored = target != null && focusSoon(requester(target)) { pageFocused.value && focusedKey.value == target }
        if (!restored) {
            currentMainKey?.let { key -> focusSoon(requester(key)) { pageFocused.value && focusedKey.value == key } }
        }
        // A refresh can remove the focused poster (a movie watched to the end leaves "continue
        // watching"): the remote must never be left without a focused element.
        vm.homeFeed.state.collect {
            withFrameNanos { }
            withFrameNanos { }
            val key = currentMainKey
            if (!pageFocused.value && key != null) focusSoon(requester(key)) { pageFocused.value }
        }
    }
    // The tiles (or the welcome card) appear once the playlists are loaded.
    LaunchedEffect(mainKey) {
        val key = mainKey ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (!pageFocused.value) focusSoon(requester(key)) { pageFocused.value }
    }

    val numbers = remember { NumberFormat.getIntegerInstance() }
    val live = TileSpec(
        key = KEY_LIVE,
        title = stringResource(R.string.home_live),
        caption = countCaption(source?.channelCount ?: 0, R.plurals.home_tile_channels, numbers),
        glyph = Glyph.TV,
        onClick = { vm.openLive() },
    )
    val lastChannel = settings.lastChannelName
        ?.takeIf { it.isNotBlank() && source != null }
        ?.let { stringResource(R.string.home_last_channel, it) }
    val moviesCaption = countCaption(source?.movieCount ?: 0, R.plurals.home_tile_movies, numbers)
    val seriesCaption = countCaption(source?.seriesCount ?: 0, R.plurals.home_tile_series, numbers)
    val grid = listOf(
        TileSpec(KEY_MOVIES, stringResource(R.string.home_movies), moviesCaption, Glyph.MOVIE) { vm.openMovies() },
        TileSpec(KEY_SERIES, stringResource(R.string.home_series), seriesCaption, Glyph.SERIES) { vm.openSeries() },
        TileSpec(KEY_REPLAY, stringResource(R.string.home_catchup), null, Glyph.REPLAY) { vm.openLive(ChannelFilter.Catchup) },
        TileSpec(KEY_GUIDE, stringResource(R.string.home_tile_guide), null, Glyph.GUIDE) { vm.openTvGuide() },
        TileSpec(KEY_MULTI, stringResource(R.string.home_tile_multiview), null, Glyph.MULTISCREEN) { vm.openMultiView() },
        TileSpec(KEY_SEARCH, stringResource(R.string.home_tile_search), null, Glyph.SEARCH) { vm.openSearch() },
    )
    val settingsAction = ActionSpec(KEY_SETTINGS, stringResource(R.string.home_settings), Glyph.SETTINGS) { vm.openSettings() }
    // Same exit path as the back key on the home screen: the exit confirmation.
    val exitAction = ActionSpec(KEY_EXIT, stringResource(R.string.home_exit), Glyph.EXIT, warm = true) { vm.onBack() }
    val actions = when {
        source != null -> listOf(
            ActionSpec(KEY_ACCOUNT, stringResource(R.string.home_action_account), Glyph.USER) { vm.openAccount() },
            ActionSpec(KEY_SWITCH, stringResource(R.string.home_action_switch_playlist), Glyph.SWITCH) { vm.openSources() },
            settingsAction,
            ActionSpec(KEY_REFRESH, stringResource(R.string.home_refresh), Glyph.REFRESH) { vm.refreshActive() },
            exitAction,
        )
        welcome -> listOf(settingsAction, exitAction)
        else -> emptyList()
    }
    val model = HomeModel(
        source = source,
        welcome = welcome,
        live = live,
        liveBadge = stringResource(R.string.epg_live_badge),
        lastChannel = lastChannel,
        grid = grid,
        actions = actions,
        continueItems = continueItems,
        running = running,
        expiry = expiry,
    )
    val onOpen: (HomeEntry) -> Unit = remember(vm) {
        { entry: HomeEntry -> if (entry.series) vm.openSeriesById(entry.item.id) else vm.openMovieById(entry.item.id) }
    }
    val onAdd: () -> Unit = remember(vm) { { vm.openSources() } }

    ScreenSurface {
        BoxWithConstraints(Modifier.fillMaxSize().onFocusChanged { pageFocused.value = it.hasFocus }) {
            AccentGlow()
            // TV and tablets in landscape: tiles side by side. Phones (and narrow windows): one scrolling column.
            val wide = maxWidth >= 720.dp && maxHeight >= 400.dp && maxWidth > maxHeight
            if (wide) {
                WideHome(model, settings.clock24h, maxWidth, maxHeight, requester, onFocus, onOpen, onAdd)
            } else {
                StackedHome(model, settings.clock24h, maxWidth, requester, onFocus, onOpen, onAdd)
            }
        }
    }
}

@Composable
private fun countCaption(count: Int, plurals: Int, numbers: NumberFormat): String? =
    if (count > 0) pluralStringResource(plurals, count, numbers.format(count.toLong())) else null

// ------------------------------------------------------------------ layouts

@Composable
private fun WideHome(
    model: HomeModel,
    clock24h: Boolean,
    width: Dp,
    height: Dp,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onOpen: (HomeEntry) -> Unit,
    onAdd: () -> Unit,
) {
    val gutter = if (width >= 840.dp) 40.dp else 28.dp
    val posterHeight = (height * 0.14f).coerceIn(60.dp, 110.dp)
    Column(Modifier.fillMaxSize().padding(start = gutter, end = gutter, top = 18.dp, bottom = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(64.dp)) {
            Clock(clock24h, Modifier.align(Alignment.CenterStart), showDate = true)
            FluideLogo(Modifier.align(Alignment.Center), size = 56.dp, withWordmark = true)
            StatusPill(
                model,
                Modifier
                    .align(Alignment.CenterEnd)
                    .widthIn(max = ((width - gutter * 2 - 320.dp) / 2f).coerceAtLeast(160.dp)),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val metrics = TileMetrics.of(maxWidth, maxHeight)
            if (model.source != null) {
                TileBlock(model, metrics, requester, onFocus)
            } else if (model.welcome) {
                WelcomeBlock(model, metrics, requester, onFocus, onAdd)
            }
        }
        BottomArea(model, posterWidth = posterHeight * (2f / 3f), stacked = false, requester, onFocus, onOpen)
    }
}

/** Phones and narrow windows: the same elements in one scrolling column. */
@Composable
private fun StackedHome(
    model: HomeModel,
    clock24h: Boolean,
    width: Dp,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onOpen: (HomeEntry) -> Unit,
    onAdd: () -> Unit,
) {
    val gutter = 16.dp
    val gap = 12.dp
    val roomy = width >= 560.dp
    val columns = if (roomy) 6 else 3
    val tile = (width - gutter * 2 - gap * (columns - 1)) / columns.toFloat()
    val perRow = if (roomy) 3 else 2
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FluideLogo(size = 34.dp, withWordmark = width >= 340.dp)
            Spacer(Modifier.weight(1f))
            Clock(clock24h, showDate = false)
        }
        StatusPill(model, Modifier.fillMaxWidth())
        if (model.source != null) {
            BigTile(
                spec = model.live,
                badge = model.liveBadge,
                lastChannel = model.lastChannel,
                modifier = Modifier.fillMaxWidth().height(150.dp),
                unit = 100.dp,
                landscape = true,
                focusRequester = requester(KEY_LIVE),
                onFocus = onFocus,
            )
            TileGrid(model.grid, columns, tile, gap, requester, onFocus)
        } else if (model.welcome) {
            WelcomeCard(Modifier.fillMaxWidth(), large = false, requester(KEY_ADD), onFocus, onAdd)
        }
        if (model.actions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (row in model.actions.chunked(perRow)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (action in row) {
                            ActionButton(action, Modifier.weight(1f).height(52.dp), 14.sp, requester(action.key), onFocus)
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        BottomArea(model, posterWidth = 64.dp, stacked = true, requester, onFocus, onOpen)
    }
}

/** Soft glow of the accent behind the centre of the screen, over the theme's background. */
@Composable
private fun AccentGlow() {
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val center = Offset(size.width * 0.5f, size.height * 0.52f)
                val radius = size.maxDimension.coerceAtLeast(1f) * 0.45f
                val accent = FluideColors.Blue
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.20f), accent.copy(alpha = 0.06f), Color.Transparent),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            },
    )
}

// ------------------------------------------------------------------ top row

/** Active playlist and its subscription: "Expire le …", "Expiré" or "Sans expiration" (Xtream). */
@Composable
private fun StatusPill(model: HomeModel, modifier: Modifier = Modifier) {
    val source = model.source
    val expiry = model.expiry
    val warn = source != null && expiry.warn
    val name = source?.name ?: stringResource(R.string.home_no_playlist)
    val expires = source?.expiresAtSec
    val status = when {
        source == null -> null
        expiry.expired -> stringResource(R.string.home_status_expired)
        expires != null -> stringResource(R.string.home_expires, formatDate(expires))
        source.type == SourceType.XTREAM -> stringResource(R.string.home_status_unlimited)
        source.type == SourceType.FILE -> stringResource(R.string.source_type_file)
        else -> stringResource(R.string.source_type_m3u)
    }
    val importing = model.running != null
    val dot = when {
        importing -> FluideColors.Cyan
        warn -> FluideColors.Warm
        source != null -> FluideColors.Aqua
        else -> FluideColors.Muted
    }
    Row(
        modifier
            .background(FluideColors.Glass, PillShape)
            .border(1.dp, if (warn) FluideColors.Warm.copy(alpha = 0.6f) else FluideColors.GlassBorder, PillShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(dot, pulsing = importing)
        Spacer(Modifier.width(10.dp))
        AppIcon(Glyph.PLAYLIST, FluideColors.Soft, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                name,
                color = FluideColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                Text(
                    status,
                    color = if (warn) FluideColors.Warm else FluideColors.Muted,
                    fontSize = 13.sp,
                    fontWeight = if (warn) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun formatDate(epochSec: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSec * 1_000))

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

// ------------------------------------------------------------------ tiles

@Composable
private fun TileBlock(model: HomeModel, m: TileMetrics, requester: (String) -> FocusRequester, onFocus: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BigTile(
            spec = model.live,
            badge = model.liveBadge,
            lastChannel = model.lastChannel,
            modifier = Modifier.width(m.bigWidth).height(m.blockHeight),
            unit = m.tile,
            landscape = false,
            focusRequester = requester(KEY_LIVE),
            onFocus = onFocus,
        )
        Spacer(Modifier.width(m.outer))
        TileGrid(model.grid, columns = 3, tile = m.tile, gap = m.gap, requester = requester, onFocus = onFocus)
        if (model.actions.isNotEmpty()) {
            Spacer(Modifier.width(m.outer))
            ActionColumn(model.actions, m, requester, onFocus)
        }
    }
}

@Composable
private fun TileGrid(
    tiles: List<TileSpec>,
    columns: Int,
    tile: Dp,
    gap: Dp,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for (row in tiles.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (spec in row) SquareTile(spec, tile, requester(spec.key), onFocus)
            }
        }
    }
}

/**
 * Focusable tile: accent fill, 14 dp corners, slightly transparent at rest; focused it grows (1.06),
 * gets a 3 dp light border and a glow. OK and touch run [onClick].
 */
@Composable
private fun TileFrame(
    key: String,
    fill: Brush,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocus: (String) -> Unit,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tileScale")
    val tileAlpha by animateFloatAsState(if (focused) 1f else 0.88f, label = "tileAlpha")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = tileAlpha
            }
            .focusGlow(focused, FluideColors.Cyan, TileCorner)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused && !focused) onFocus(key)
                focused = state.isFocused
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clip(TileShape)
            .background(fill)
            .background(TileGloss)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) Color.White else Color.White.copy(alpha = 0.12f),
                shape = TileShape,
            ),
        content = content,
    )
}

/** Live TV: the accent gradient, the TV glyph, the channel count and the last channel watched. */
@Composable
private fun BigTile(
    spec: TileSpec,
    badge: String,
    lastChannel: String?,
    modifier: Modifier,
    unit: Dp,
    landscape: Boolean,
    focusRequester: FocusRequester,
    onFocus: (String) -> Unit,
) {
    val on = FluideColors.OnAccent
    val pad = (unit * 0.14f).coerceIn(12.dp, 22.dp)
    TileFrame(spec.key, FluideGradient, modifier, focusRequester, onFocus, spec.onClick) {
        if (landscape) {
            Row(Modifier.fillMaxSize().padding(pad), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(spec.glyph, on, size = unit * 0.62f)
                Spacer(Modifier.width(pad))
                Column(Modifier.weight(1f)) {
                    LiveBadge(badge)
                    Spacer(Modifier.height(8.dp))
                    BigTileTexts(spec, lastChannel, unit)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(pad)) {
                LiveBadge(badge)
                // The glyph takes the room the texts leave (none on the smallest screens).
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
                    val icon = min(unit * 0.56f, maxHeight - pad)
                    if (icon >= 28.dp) AppIcon(spec.glyph, on, Modifier.padding(bottom = pad * 0.7f), size = icon)
                }
                BigTileTexts(spec, lastChannel, unit)
            }
        }
    }
}

@Composable
private fun BigTileTexts(spec: TileSpec, lastChannel: String?, unit: Dp) {
    val on = FluideColors.OnAccent
    val title = (unit.value * 0.19f).coerceIn(18f, 30f).sp
    Text(
        spec.title,
        color = on,
        fontSize = title,
        lineHeight = title * 1.12f,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val caption = spec.caption
    if (caption != null) {
        Text(
            caption,
            color = on.copy(alpha = 0.88f),
            fontSize = (unit.value * 0.115f).coerceIn(12f, 18f).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (lastChannel != null) {
        Spacer(Modifier.height(2.dp))
        Text(
            lastChannel,
            color = on.copy(alpha = 0.8f),
            fontSize = (unit.value * 0.105f).coerceIn(12f, 16f).sp,
            maxLines = if (unit < 110.dp) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Square section tile: a large glyph, the label and, for movies and series, the count. */
@Composable
private fun SquareTile(spec: TileSpec, size: Dp, focusRequester: FocusRequester, onFocus: (String) -> Unit) {
    val on = FluideColors.OnAccent
    val label = (size.value * 0.13f).coerceIn(12f, 22f).sp
    TileFrame(spec.key, GridFill, Modifier.size(size), focusRequester, onFocus, spec.onClick) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = size * 0.05f, vertical = size * 0.08f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(spec.glyph, on, size = size * 0.34f)
            Spacer(Modifier.height(size * 0.07f))
            Text(
                spec.title,
                color = on,
                fontSize = label,
                lineHeight = label * 1.15f,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val caption = spec.caption
            if (caption != null) {
                Text(
                    caption,
                    color = on.copy(alpha = 0.82f),
                    fontSize = (size.value * 0.095f).coerceIn(11f, 16f).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LiveBadge(label: String) {
    val transition = rememberInfiniteTransition(label = "live")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900), repeatMode = RepeatMode.Reverse),
        label = "livePulse",
    )
    val on = FluideColors.OnAccent
    Row(
        Modifier
            .background(on.copy(alpha = 0.16f), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = pulse }
                .background(FluideColors.Warm, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = on, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
    }
}

// ------------------------------------------------------------------ action buttons

@Composable
private fun ActionColumn(actions: List<ActionSpec>, m: TileMetrics, requester: (String) -> FocusRequester, onFocus: (String) -> Unit) {
    Column(Modifier.width(m.buttonsWidth), verticalArrangement = Arrangement.spacedBy(m.buttonGap)) {
        for (action in actions) {
            ActionButton(action, Modifier.fillMaxWidth().height(m.buttonHeight), m.buttonFont, requester(action.key), onFocus)
        }
    }
}

/** Wide outlined button: glass with an accent border at rest, filled with the accent gradient when focused. */
@Composable
private fun ActionButton(
    spec: ActionSpec,
    modifier: Modifier,
    fontSize: TextUnit,
    focusRequester: FocusRequester,
    onFocus: (String) -> Unit,
    primary: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "actionScale")
    val interaction = remember { MutableInteractionSource() }
    val content = if (focused) FluideColors.OnAccent else FluideColors.Text
    val iconTint = when {
        focused -> FluideColors.OnAccent
        spec.warm -> FluideColors.Warm
        else -> FluideColors.Cyan
    }
    val fill = when {
        focused -> FluideGradient
        primary -> PrimaryFill
        else -> GlassFill
    }
    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusGlow(focused, FluideColors.Cyan, ButtonCorner, steps = 2)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused && !focused) onFocus(spec.key)
                focused = state.isFocused
            }
            .clickable(interactionSource = interaction, indication = null, onClick = spec.onClick)
            .clip(ButtonShape)
            .background(fill)
            .border(
                width = if (focused) 2.dp else 1.5.dp,
                color = if (focused) Color.White else FluideColors.Cyan.copy(alpha = 0.55f),
                shape = ButtonShape,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (primary) Arrangement.Center else Arrangement.Start,
    ) {
        AppIcon(spec.glyph, iconTint, size = 20.dp)
        Spacer(Modifier.width(10.dp))
        // Long labels ("Changer de playlist") wrap rather than lose their end.
        Text(
            spec.label,
            color = content,
            fontSize = fontSize,
            lineHeight = fontSize * 1.1f,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ no playlist yet

/** No playlist: the welcome card (with the way to add one) takes the place of the tiles. */
@Composable
private fun WelcomeBlock(
    model: HomeModel,
    m: TileMetrics,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WelcomeCard(
            Modifier.width(m.bigWidth + m.outer + m.gridWidth).heightIn(min = m.blockHeight),
            large = true,
            focusRequester = requester(KEY_ADD),
            onFocus = onFocus,
            onAdd = onAdd,
        )
        if (model.actions.isNotEmpty()) {
            Spacer(Modifier.width(m.outer))
            ActionColumn(model.actions, m, requester, onFocus)
        }
    }
}

@Composable
private fun WelcomeCard(
    modifier: Modifier,
    large: Boolean,
    focusRequester: FocusRequester,
    onFocus: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        },
    )
    val add = ActionSpec(KEY_ADD, stringResource(R.string.home_add_playlist), Glyph.PLAYLIST, onClick = onAdd)
    Column(
        modifier
            .fluideGlass(TileShape)
            .padding(if (large) 28.dp else 20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(greeting, color = FluideColors.Cyan, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.home_welcome_title),
            color = FluideColors.Text,
            fontSize = if (large) 28.sp else 22.sp,
            lineHeight = if (large) 33.sp else 27.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_welcome_no_source),
            color = FluideColors.Soft,
            fontSize = 15.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))
        ActionButton(add, Modifier.widthIn(min = 240.dp).height(50.dp), 16.sp, focusRequester, onFocus, primary = true)
    }
}

// ------------------------------------------------------------------ bottom: continue watching, import, expiry

@Composable
private fun BottomArea(
    model: HomeModel,
    posterWidth: Dp,
    stacked: Boolean,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onOpen: (HomeEntry) -> Unit,
) {
    val running = model.running
    val warning = model.source != null && model.expiry.warn
    if (model.continueItems.isEmpty() && running == null && !warning) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (stacked) 0.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (model.continueItems.isNotEmpty()) ContinueStrip(model.continueItems, posterWidth, stacked, requester, onFocus, onOpen)
        if (running != null) ImportLine(running)
        if (warning) ExpiryWarning(model.expiry)
    }
}

/** One compact strip of small posters (movies and series started), with their progress. */
@Composable
private fun ContinueStrip(
    entries: List<HomeEntry>,
    posterWidth: Dp,
    stacked: Boolean,
    requester: (String) -> FocusRequester,
    onFocus: (String) -> Unit,
    onOpen: (HomeEntry) -> Unit,
) {
    var focusedName by remember { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.home_row_continue)
    val shape = RoundedCornerShape(16.dp)
    val stripModifier = Modifier
        .fillMaxWidth()
        .onFocusChanged { if (!it.hasFocus) focusedName = null }
        .fluideGlass(shape)
    val onPosterFocused: (HomeEntry) -> Unit = { entry ->
        focusedName = entry.item.name
        onFocus(CONTINUE_PREFIX + entry.key)
    }
    if (stacked) {
        Column(stripModifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            ContinueTitle(title)
            ContinuePosters(entries, posterWidth, Modifier.fillMaxWidth(), requester, onPosterFocused, onOpen)
        }
    } else {
        Row(stripModifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(170.dp)) {
                ContinueTitle(title)
                val name = focusedName
                if (name != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(name, color = FluideColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            ContinuePosters(entries, posterWidth, Modifier.weight(1f), requester, onPosterFocused, onOpen)
        }
    }
}

@Composable
private fun ContinueTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Glyph.PLAY, FluideColors.Cyan, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            color = FluideColors.Text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ContinuePosters(
    entries: List<HomeEntry>,
    posterWidth: Dp,
    modifier: Modifier,
    requester: (String) -> FocusRequester,
    onFocused: (HomeEntry) -> Unit,
    onOpen: (HomeEntry) -> Unit,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        items(entries, key = { it.key }) { entry ->
            PosterThumb(
                entry = entry,
                width = posterWidth,
                focusRequester = requester(CONTINUE_PREFIX + entry.key),
                onFocused = { onFocused(entry) },
                onClick = { onOpen(entry) },
            )
        }
    }
}

@Composable
private fun PosterThumb(
    entry: HomeEntry,
    width: Dp,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val item = entry.item
    var focused by remember { mutableStateOf(false) }
    var failed by remember(item.poster) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "poster")
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .width(width)
            .aspectRatio(2f / 3f)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusGlow(focused, FluideColors.Cyan, PosterCorner, steps = 2, step = 3.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused && !focused) onFocused()
                focused = state.isFocused
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clip(PosterShape)
            .background(PosterFill)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color.White else FluideColors.GlassBorder,
                shape = PosterShape,
            ),
    ) {
        val poster = item.poster
        if (poster == null || failed) {
            Text(
                initials(item.name),
                color = FluideColors.Soft,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.align(Alignment.Center),
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
                    .height(22.dp)
                    .background(PosterShade),
            )
            ProgressStripe(
                item.progress,
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ProgressStripe(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                .background(FluideGradient),
        )
    }
}

@Composable
private fun ImportLine(running: ImportProgress) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(FluideColors.Cyan, pulsing = true)
        Spacer(Modifier.width(10.dp))
        Text(
            if (running.phase == ImportPhase.PLAYLIST) {
                stringResource(R.string.import_running, running.count)
            } else {
                stringResource(R.string.import_epg_running, running.count)
            },
            color = FluideColors.Cyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(14.dp))
        LinearProgressIndicator(
            modifier = Modifier
                .width(180.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = FluideColors.Cyan,
            trackColor = Color.White.copy(alpha = 0.10f),
        )
    }
}

@Composable
private fun ExpiryWarning(expiry: Expiry) {
    val days = expiry.daysLeft
    val text = when {
        expiry.expired || days == null -> stringResource(R.string.home_warning_expired)
        days == 0 -> stringResource(R.string.home_warning_today)
        else -> pluralStringResource(R.plurals.home_warning_days, days, days)
    }
    val warm = FluideColors.Warm
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(warm.copy(alpha = 0.14f))
            .border(1.dp, warm.copy(alpha = 0.5f), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).background(warm, CircleShape), contentAlignment = Alignment.Center) {
            Text("!", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = blend(warm, Color.White, 0.6f), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ helpers

/**
 * Requests the focus over a few frames: the tiles are composed during layout (BoxWithConstraints), so
 * the target may not be attached yet on the first frame. Stops as soon as [done] is true.
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

/** Soft halo around a focused element (drawn on every API level, unlike coloured shadows). */
private fun Modifier.focusGlow(active: Boolean, color: Color, corner: Dp, steps: Int = 3, step: Dp = 4.dp): Modifier =
    if (!active) {
        this
    } else {
        drawBehind {
            val radius = corner.toPx()
            for (i in 1..steps) {
                val spread = i * step.toPx()
                drawRoundRect(
                    color = color.copy(alpha = 0.26f / i),
                    topLeft = Offset(-spread, -spread),
                    size = Size(size.width + spread * 2, size.height + spread * 2),
                    cornerRadius = CornerRadius(radius + spread),
                )
            }
        }
    }

private fun initials(name: String): String =
    name.split(' ', '-', '_', '|', '.', ':')
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

package app.fluide.player.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.Episode
import app.fluide.player.data.MediaDetailsUi
import app.fluide.player.data.Section
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.components.formatClock
import app.fluide.player.ui.components.formatDuration
import coil.compose.AsyncImage

/**
 * Details page of a movie (play, resume, favorite, trailer) or of a series (seasons and episodes):
 * blurred full-bleed backdrop, poster, information chips, actions.
 */
@Composable
fun DetailsScreen(vm: MainViewModel) {
    val details by vm.details.collectAsStateWithLifecycle()
    val d = details ?: return
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }
    var pageHasFocus by remember { mutableStateOf(false) }
    val widthDp = LocalConfiguration.current.screenWidthDp
    val wide = widthDp >= 840
    val gutter = if (wide) 48.dp else 16.dp

    ScreenSurface {
        Backdrop(d.backdrop ?: d.poster)
        Row(
            Modifier
                .fillMaxSize()
                .onFocusChanged { pageHasFocus = it.hasFocus }
                .padding(start = gutter, end = gutter, top = if (wide) 36.dp else 16.dp),
        ) {
            if (widthDp >= 600) {
                PosterFrame(d, Modifier.width(if (wide) 230.dp else 170.dp))
                Spacer(Modifier.width(if (wide) 40.dp else 24.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp),
            ) {
                Kicker(d.section)
                Spacer(Modifier.height(10.dp))
                Text(
                    d.name,
                    fontSize = if (wide) 38.sp else 28.sp,
                    lineHeight = if (wide) 44.sp else 33.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                DetailChips(d)
                Spacer(Modifier.height(16.dp))
                if (d.loading) {
                    LoadingBlock(withLines = d.plot == null)
                    Spacer(Modifier.height(10.dp))
                }
                d.plot?.let {
                    Text(
                        it,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        color = MediaPalette.Soft,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 780.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                InfoLine(stringResource(R.string.details_director), d.director)
                InfoLine(stringResource(R.string.details_cast), d.cast)
                InfoLine(stringResource(R.string.details_released), d.releaseDate)
                InfoLine(stringResource(R.string.details_country), d.country)
                Spacer(Modifier.height(18.dp))
                ActionRow(d, vm, context, playFocus)
                if (d.section == Section.SERIES) {
                    Spacer(Modifier.height(22.dp))
                    SeasonsAndEpisodes(d, vm, context)
                }
            }
        }
    }
    LaunchedEffect(d.id, d.section) { runCatching { playFocus.requestFocus() } }
    // Series: the Play button stays disabled (not focusable) until the episodes are known. Give it the
    // focus once it is enabled, unless the user has already moved somewhere on the page.
    val seriesReady = d.section == Section.SERIES && d.continueEpisode != null
    LaunchedEffect(d.id, seriesReady) {
        if (seriesReady && !pageHasFocus) runCatching { playFocus.requestFocus() }
    }
}

/** Room left on both sides of the scrolling rows for the enlarged focused item and its glow. */
private val RowBleed = 18.dp

// ------------------------------------------------------------------ header

/** Blurred backdrop under a strong left-to-right and bottom scrim, tinted with the brand colors. */
@Composable
private fun Backdrop(url: String?) {
    // Before Android 12 the blur is a no-op: a dimmer picture and denser scrims keep the text readable.
    val sharp = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Slightly enlarged: the soft edges of the blur stay off screen.
                    scaleX = 1.12f
                    scaleY = 1.12f
                    alpha = if (sharp) 0.32f else 0.62f
                }
                .blur(20.dp),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to MediaPalette.Navy.copy(alpha = 0.97f),
                    0.45f to MediaPalette.Navy.copy(alpha = if (sharp) 0.9f else 0.8f),
                    1f to MediaPalette.Navy.copy(alpha = if (sharp) 0.62f else 0.3f),
                ),
            ),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to MediaPalette.Navy.copy(alpha = 0.4f),
                    0.35f to Color.Transparent,
                    1f to MediaPalette.Navy.copy(alpha = 0.97f),
                ),
            ),
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(MediaPalette.Blue.copy(alpha = 0.14f), Color.Transparent, MediaPalette.Violet.copy(alpha = 0.16f)),
                ),
            ),
    )
}

@Composable
private fun PosterFrame(d: MediaDetailsUi, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .aspectRatio(2f / 3f)
            .shadow(elevation = 24.dp, shape = shape, ambientColor = MediaPalette.Blue, spotColor = MediaPalette.Cyan)
            .clip(shape)
            .background(MediaArtPlaceholder, shape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
    ) {
        MediaArt(d.poster, d.name, sectionGlyph(d.section), Modifier.fillMaxSize(), glyphSize = 56.dp)
    }
}

/** "FILM" / "SÉRIE" above the title. */
@Composable
private fun Kicker(section: Section) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(sectionGlyph(section), MediaPalette.Cyan, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(if (section == Section.SERIES) R.string.media_kicker_series else R.string.media_kicker_movie).uppercase(),
            color = MediaPalette.Cyan,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.width(10.dp))
        AccentBar(width = 28.dp)
    }
}

/** Year, genre, duration, rating and category as glass pills. */
@Composable
private fun DetailChips(d: MediaDetailsUi) {
    val year = d.year?.toString()
    val genre = d.genre?.takeIf { it.isNotBlank() }
    val duration = d.durationSec?.takeIf { it > 0 }?.let { formatDuration(it) }
    val rating = ratingLabel(d.rating)
    val group = d.group?.takeIf { it.isNotBlank() }
    if (year == null && genre == null && duration == null && rating == null && group == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (year != null) MetaChip(year)
        if (genre != null) MetaChip(genre, Modifier.weight(1f, fill = false))
        if (duration != null) MetaChip(duration)
        if (rating != null) MetaChip("★ $rating", accent = true)
        if (group != null) MetaChip(group, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun LoadingBlock(withLines: Boolean) {
    Text(stringResource(R.string.details_loading), color = MediaPalette.Muted, fontSize = 14.sp)
    if (withLines) {
        val lineShape = RoundedCornerShape(6.dp)
        for (i in 0 until 3) {
            Spacer(Modifier.height(10.dp))
            ShimmerBlock(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(if (i == 2) 0.55f else 0.92f)
                    .height(12.dp),
                lineShape,
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp).widthIn(max = 780.dp)) {
        Text(
            label.uppercase(),
            color = MediaPalette.Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(124.dp).padding(top = 2.dp),
        )
        Text(value, color = MediaPalette.Soft, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ actions

@Composable
private fun ActionRow(d: MediaDetailsUi, vm: MainViewModel, context: Context, playFocus: FocusRequester) {
    // Scrolls sideways when the buttons do not fit (the focused one is always brought into view).
    Row(
        Modifier
            .horizontalBleed(RowBleed)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = RowBleed, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (d.section == Section.MOVIES) {
            if (d.resumeMs > 0) {
                val totalMs = if (d.durationMs > 0) d.durationMs else (d.durationSec ?: 0L) * 1_000
                val remaining = if (totalMs > d.resumeMs) {
                    stringResource(R.string.media_remaining, formatDuration((totalMs - d.resumeMs) / 1_000))
                } else {
                    null
                }
                WithProgress(fraction = if (totalMs > 0) d.resumeMs.toFloat() / totalMs else null, caption = remaining) {
                    TvButton(
                        stringResource(R.string.details_resume, formatClock(d.resumeMs)),
                        onClick = { vm.playMovie(fromStart = false) },
                        glyph = Glyph.PLAY,
                        primary = true,
                        focusRequester = playFocus,
                    )
                }
                TvButton(stringResource(R.string.details_restart), onClick = { vm.playMovie(fromStart = true) }, glyph = Glyph.REPLAY)
            } else {
                TvButton(
                    stringResource(R.string.details_play),
                    onClick = { vm.playMovie(fromStart = true) },
                    glyph = Glyph.PLAY,
                    primary = true,
                    focusRequester = playFocus,
                )
            }
        } else {
            val next = d.continueEpisode
            val label = if (next != null && (next.progress > 0f || next.positionMs > 0)) {
                stringResource(R.string.details_continue, next.season, next.number)
            } else {
                stringResource(R.string.details_play)
            }
            WithProgress(fraction = if (next != null && next.progress > 0f) next.progress else null) {
                TvButton(
                    label,
                    onClick = { next?.let { vm.playEpisode(it) } },
                    glyph = Glyph.PLAY,
                    primary = true,
                    enabled = next != null,
                    focusRequester = playFocus,
                )
            }
        }
        TvButton(
            stringResource(if (d.favorite) R.string.details_favorite_remove else R.string.details_favorite_add),
            onClick = vm::toggleDetailsFavorite,
            glyph = Glyph.STAR,
        )
        val trailer = d.trailer
        if (!trailer.isNullOrBlank()) {
            TvButton(stringResource(R.string.details_trailer), onClick = { openTrailer(context, trailer, vm) }, glyph = Glyph.MOVIE)
        }
        if (d.section == Section.MOVIES) {
            TvButton(
                stringResource(R.string.details_external),
                onClick = { vm.externalUrl(null) { url -> openExternal(context, url, d.name, vm) } },
                glyph = Glyph.EXIT,
            )
        }
    }
}

/** [button] with, when [fraction] is known, a thin progress bar (and an optional [caption]) under it. */
@Composable
private fun WithProgress(fraction: Float?, caption: String? = null, button: @Composable () -> Unit) {
    if (fraction == null || fraction <= 0f) {
        button()
        return
    }
    Column(Modifier.width(IntrinsicSize.Max)) {
        button()
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressStripe(fraction, Modifier.weight(1f), height = 3.dp)
            if (caption != null) {
                Spacer(Modifier.width(8.dp))
                Text(caption, color = MediaPalette.Muted, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ seasons and episodes

private val SeasonShape = RoundedCornerShape(24.dp)
private val SeasonSelectedBrush = Brush.horizontalGradient(listOf(MediaPalette.Blue.copy(alpha = 0.4f), MediaPalette.Cyan.copy(alpha = 0.18f)))
private val EpisodeShape = RoundedCornerShape(16.dp)

@Composable
private fun SeasonsAndEpisodes(d: MediaDetailsUi, vm: MainViewModel, context: Context) {
    if (d.seasons.isEmpty()) {
        if (!d.loading) Text(stringResource(R.string.details_no_episodes), color = MediaPalette.Muted)
        return
    }
    val initialSeason = d.continueEpisode?.season ?: d.seasons.first().number
    var season by remember(d.id) { mutableIntStateOf(initialSeason) }
    val current = d.seasons.firstOrNull { it.number == season } ?: d.seasons.first()
    Row(verticalAlignment = Alignment.CenterVertically) {
        AccentBar(width = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.details_episodes), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
    LazyRow(
        modifier = Modifier.horizontalBleed(RowBleed),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = RowBleed, vertical = 8.dp),
    ) {
        items(d.seasons, key = { it.number }) { s ->
            SeasonPill(
                label = s.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.details_season, s.number),
                episodes = s.episodes.size,
                selected = s.number == current.number,
                onClick = { season = s.number },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    val listState = rememberLazyListState()
    LaunchedEffect(current.number, d.continueEpisode?.key) {
        val index = current.episodes.indexOfFirst { it.key == d.continueEpisode?.key }
        if (index > 0) listState.scrollToItem(index)
    }
    val continueKey = d.continueEpisode?.key
    LazyRow(
        state = listState,
        modifier = Modifier.horizontalBleed(RowBleed),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = RowBleed, end = RowBleed + 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        items(current.episodes, key = { it.key }) { episode ->
            val status = when {
                episode.key != continueKey -> null
                episode.progress > 0f || episode.positionMs > 0 -> stringResource(R.string.media_episode_current)
                else -> stringResource(R.string.media_episode_next)
            }
            EpisodeCard(episode, d.poster, status, onClick = { vm.playEpisode(episode) }, onLongClick = {
                vm.externalUrl(episode) { url -> openExternal(context, url, d.name, vm) }
            })
        }
    }
}

@Composable
private fun SeasonPill(label: String, episodes: Int, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "season")
    val fill = when {
        focused -> MediaFluidBrush
        selected -> SeasonSelectedBrush
        else -> MediaGlassBrush
    }
    val borderColor = when {
        focused -> Color.White
        selected -> MediaPalette.Cyan.copy(alpha = 0.5f)
        else -> MediaPalette.GlassBorder
    }
    // Same modifier chain in every state (only values change): the focus node is never recreated.
    Row(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .mediaFocusGlow(focused, 24.dp)
            .clip(SeasonShape)
            .background(fill, SeasonShape)
            .border(if (focused) 2.dp else 1.dp, borderColor, SeasonShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 15.sp,
            // Weight follows the selection only: the pill keeps its width when the focus moves.
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
        if (episodes > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.media_season_episodes, episodes),
                color = if (focused || selected) Color.White.copy(alpha = 0.82f) else MediaPalette.Muted,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}

/**
 * An episode: 16:9 picture with its number, duration, progress and, for the episode to continue
 * with, a status badge. OK plays it; long OK opens it in another player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(episode: Episode, fallbackImage: String?, status: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "episode")
    val interaction = remember { MutableInteractionSource() }
    val title = if (episode.title.isBlank()) stringResource(R.string.media_episode_label, episode.number) else episode.title
    Column(
        Modifier
            .width(264.dp)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            // Long OK: open the episode in another player. The card draws its own focus state.
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .mediaFocusGlow(focused, 16.dp)
                .clip(EpisodeShape)
                .background(MediaArtPlaceholder, EpisodeShape)
                .border(if (focused) 3.dp else 1.dp, if (focused) MediaFocusBorder else MediaRestBorder, EpisodeShape),
        ) {
            MediaArt(episode.image ?: fallbackImage, null, Glyph.SERIES, Modifier.fillMaxSize(), glyphSize = 36.dp)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.55f)
                    .background(MediaBottomScrim),
            )
            MediaBadge("E${episode.number}", Modifier.align(Alignment.TopStart).padding(8.dp))
            if (status != null) {
                MediaBadge(
                    status,
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    fill = SolidColor(Color.White),
                    color = MediaPalette.Navy,
                )
            }
            val started = episode.progress > 0f
            val duration = episode.durationSec
            if (duration != null && duration > 0) {
                Text(
                    formatDuration(duration),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = if (started) 18.dp else 9.dp),
                )
            }
            if (started) {
                ProgressStripe(
                    episode.progress,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                    height = 3.dp,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = if (focused) Color.White else MediaPalette.Soft,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        episode.plot?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, color = MediaPalette.Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ other apps

/** YouTube trailer: an id ("dQw4w9WgXcQ") or a full link, opened in the YouTube app or a browser. */
private fun openTrailer(context: Context, trailer: String, vm: MainViewModel) {
    val url = if (trailer.startsWith("http")) trailer else "https://www.youtube.com/watch?v=${trailer.trim()}"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        vm.notifyNoExternalPlayer()
    }
}

/** Hands the stream to another installed player (VLC, MX Player...). */
fun openExternal(context: Context, url: String, title: String, vm: MainViewModel) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(url), "video/*")
        .putExtra("title", title)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        vm.notifyNoExternalPlayer()
    }
}

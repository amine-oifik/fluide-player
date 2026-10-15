package app.fluide.player.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.Episode
import app.fluide.player.data.MediaDetailsUi
import app.fluide.player.data.Section
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenBrush
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.components.formatClock
import app.fluide.player.ui.components.formatDuration
import coil.compose.AsyncImage
import java.util.Locale

/** Details page of a movie (play, resume, favorite, trailer) or of a series (seasons and episodes). */
@Composable
fun DetailsScreen(vm: MainViewModel) {
    val details by vm.details.collectAsStateWithLifecycle()
    val d = details ?: return
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(ScreenBrush)) {
        val backdrop = d.backdrop ?: d.poster
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color(0xF2070B1C), Color(0xCC070B1C), Color(0x66070B1C)))),
        )
        Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            Box(
                Modifier
                    .width(220.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1A2135)),
            ) {
                if (d.poster != null) {
                    AsyncImage(model = d.poster, contentDescription = d.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(36.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(d.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                MetaLine(d)
                Spacer(Modifier.height(14.dp))
                if (d.loading) {
                    Text(stringResource(R.string.details_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(Modifier.width(240.dp).padding(top = 6.dp))
                }
                d.plot?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD7DCEA), maxLines = 6, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                }
                InfoLine(stringResource(R.string.details_director), d.director)
                InfoLine(stringResource(R.string.details_cast), d.cast)
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
}

@Composable
private fun MetaLine(d: MediaDetailsUi) {
    val parts = ArrayList<String>(4)
    d.year?.let { parts += it.toString() }
    d.genre?.let { parts += it }
    d.durationSec?.takeIf { it > 0 }?.let { parts += formatDuration(it) }
    d.rating?.let { parts += "★ " + String.format(Locale.ROOT, "%.1f", if (it > 10) it / 10 else it) }
    d.group?.let { parts += it }
    if (parts.isNotEmpty()) {
        Text(parts.joinToString("   ·   "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label : ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
        Text(value, color = Color(0xFFD7DCEA), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionRow(d: MediaDetailsUi, vm: MainViewModel, context: Context, playFocus: FocusRequester) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (d.section == Section.MOVIES) {
            if (d.resumeMs > 0) {
                TvButton(
                    stringResource(R.string.details_resume, formatClock(d.resumeMs)),
                    onClick = { vm.playMovie(fromStart = false) },
                    glyph = Glyph.PLAY,
                    primary = true,
                    focusRequester = playFocus,
                )
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
            TvButton(
                if (next != null && (next.progress > 0f || next.positionMs > 0)) {
                    stringResource(R.string.details_continue, next.season, next.number)
                } else {
                    stringResource(R.string.details_play)
                },
                onClick = { next?.let { vm.playEpisode(it) } },
                glyph = Glyph.PLAY,
                primary = true,
                enabled = next != null,
                focusRequester = playFocus,
            )
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
            TvButton(stringResource(R.string.details_external), onClick = {
                vm.externalUrl(null) { url -> openExternal(context, url, d.name, vm) }
            })
        }
    }
}

@Composable
private fun SeasonsAndEpisodes(d: MediaDetailsUi, vm: MainViewModel, context: Context) {
    if (d.seasons.isEmpty()) {
        if (!d.loading) Text(stringResource(R.string.details_no_episodes), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val initialSeason = d.continueEpisode?.season ?: d.seasons.first().number
    var season by remember(d.id) { mutableIntStateOf(initialSeason) }
    val current = d.seasons.firstOrNull { it.number == season } ?: d.seasons.first()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(d.seasons, key = { it.number }) { s ->
            SeasonChip(
                label = s.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.details_season, s.number),
                selected = s.number == current.number,
                onClick = { season = s.number },
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    val listState = rememberLazyListState()
    LaunchedEffect(current.number, d.continueEpisode?.key) {
        val index = current.episodes.indexOfFirst { it.key == d.continueEpisode?.key }
        if (index > 0) listState.scrollToItem(index)
    }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 24.dp)) {
        items(current.episodes, key = { it.key }) { episode ->
            EpisodeCard(episode, d.poster, onClick = { vm.playEpisode(episode) }, onLongClick = {
                vm.externalUrl(episode) { url -> openExternal(context, url, d.name, vm) }
            })
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Text(
        label,
        color = Color.White,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> MaterialTheme.colorScheme.primary
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(episode: Episode, fallbackImage: String?, onClick: () -> Unit, onLongClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "episode")
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(240.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { focused = it.isFocused }
            // Long OK: open the episode in another player.
            .combinedClickable(onLongClick = onLongClick, onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(Color(0xFF1A2135), shape)
                .border(3.dp, if (focused) Color.White else Color.Transparent, shape),
        ) {
            val image = episode.image ?: fallbackImage
            if (image != null) {
                AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(
                "E${episode.number}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (episode.progress > 0f) {
                LinearProgressIndicator(
                    progress = { episode.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(episode.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (focused) Color.White else Color(0xFFD7DCEA))
        val duration = episode.durationSec
        if (duration != null && duration > 0) {
            Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        episode.plot?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

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

package app.fluide.player.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ImportPhase
import app.fluide.player.data.SourceType
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import java.text.DateFormat
import java.util.Date

private val LiveBrush = Brush.linearGradient(listOf(Color(0xFFFF6A3D), Color(0xFFE5245E)))
private val MoviesBrush = Brush.linearGradient(listOf(Color(0xFF3D7BFF), Color(0xFF7B3DFF)))
private val SeriesBrush = Brush.linearGradient(listOf(Color(0xFF13B58C), Color(0xFF0E7DB8)))
private val SmallBrush = Brush.linearGradient(listOf(Color(0xFF1C2542), Color(0xFF161D36)))

/** Home: the three sections as large tiles, then the playlist tools, the clock and the account. */
@Composable
fun HomeScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val progress by vm.importProgress.collectAsStateWithLifecycle()
    val source = sources.firstOrNull { it.id == ui.activeSourceId }
    val liveFocus = remember { FocusRequester() }

    ScreenSurface {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                    if (source != null) {
                        Text(
                            stringResource(R.string.home_playlist, source.name),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Clock(settings.clock24h)
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                BigTile(
                    title = stringResource(R.string.home_live),
                    subtitle = source?.channelCount?.takeIf { it > 0 }?.toString(),
                    glyph = Glyph.TV,
                    brush = LiveBrush,
                    onClick = { vm.openLive() },
                    modifier = Modifier.weight(1.25f).fillMaxHeight(),
                    focusRequester = liveFocus,
                )
                BigTile(
                    title = stringResource(R.string.home_movies),
                    subtitle = source?.movieCount?.takeIf { it > 0 }?.toString(),
                    glyph = Glyph.MOVIE,
                    brush = MoviesBrush,
                    onClick = vm::openMovies,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                BigTile(
                    title = stringResource(R.string.home_series),
                    subtitle = source?.seriesCount?.takeIf { it > 0 }?.toString(),
                    glyph = Glyph.SERIES,
                    brush = SeriesBrush,
                    onClick = vm::openSeries,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SmallTile(stringResource(R.string.home_catchup), Glyph.REPLAY, { vm.openLive(ChannelFilter.Catchup) }, Modifier.weight(1f))
                SmallTile(stringResource(R.string.home_playlists), Glyph.PLAYLIST, vm::openSources, Modifier.weight(1f))
                SmallTile(stringResource(R.string.home_settings), Glyph.SETTINGS, vm::openSettings, Modifier.weight(1f))
                SmallTile(stringResource(R.string.home_refresh), Glyph.REFRESH, vm::refreshActive, Modifier.weight(1f))
                SmallTile(stringResource(R.string.home_exit), Glyph.EXIT, { vm.onBack() }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            // Account line: expiry and connections (Xtream), import progress.
            val running = progress?.takeIf { it.sourceId == ui.activeSourceId && !it.done }
            if (running != null) {
                Text(
                    if (running.phase == ImportPhase.PLAYLIST) stringResource(R.string.import_running, running.count)
                    else stringResource(R.string.import_epg_running, running.count),
                    color = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            } else if (source != null) {
                val parts = ArrayList<String>(4)
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
                Text(parts.joinToString("   ·   "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                val expires = source.expiresAtSec
                if (expires != null) {
                    val days = ((expires * 1_000 - System.currentTimeMillis()) / 86_400_000L).toInt()
                    if (days in 0..7) {
                        Text(
                            stringResource(R.string.home_expires_soon, days),
                            color = Color(0xFFFFB74D),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { liveFocus.requestFocus() } }
}

@Composable
private fun BigTile(
    title: String,
    subtitle: String?,
    glyph: Glyph,
    brush: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "tile")
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(brush, shape)
            .border(3.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(26.dp),
    ) {
        AppIcon(glyph, Color.White, Modifier.align(Alignment.TopStart), size = 64.dp)
        Column(Modifier.align(Alignment.BottomStart)) {
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium)
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SmallTile(title: String, glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxHeight()
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Brush.linearGradient(listOf(Color(0xFF3D7BFF), Color(0xFF2B5BD8))) else SmallBrush, shape)
            .border(2.dp, if (focused) Color.White else Color.White.copy(alpha = 0.08f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(glyph, Color.White, size = 30.dp)
        Spacer(Modifier.width(14.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
    }
}

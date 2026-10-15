package app.fluide.player.ui.player

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluide.player.R
import app.fluide.player.data.Programme
import app.fluide.player.playback.ErrorKind
import app.fluide.player.playback.Notice
import app.fluide.player.playback.PlaybackError
import app.fluide.player.playback.PlaybackStats
import app.fluide.player.ui.BannerInfo
import app.fluide.player.ui.Toast
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Date

private val Panel = Color(0xE60B1020)
private val PanelShape = RoundedCornerShape(16.dp)

/** Delay before the buffering spinner appears: short waits stay invisible. */
private const val SPINNER_DELAY_MS = 600L

/** Discreet spinner in a corner, only if the wait lasts more than [SPINNER_DELAY_MS]. */
@Composable
fun WaitingIndicator(waitingSinceMs: Long, modifier: Modifier = Modifier) {
    var visible by remember(waitingSinceMs) { mutableStateOf(false) }
    LaunchedEffect(waitingSinceMs) {
        if (waitingSinceMs == 0L) return@LaunchedEffect
        val elapsed = SystemClock.elapsedRealtime() - waitingSinceMs
        delay((SPINNER_DELAY_MS - elapsed).coerceAtLeast(0))
        visible = true
    }
    if (visible) {
        CircularProgressIndicator(modifier = modifier.size(36.dp), strokeWidth = 3.dp, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
fun ZapBanner(banner: BannerInfo?, visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = visible && banner != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        val info = banner ?: return@AnimatedVisibility
        Row(
            modifier = Modifier
                .padding(32.dp)
                .widthIn(max = 1_100.dp)
                .fillMaxWidth()
                .background(Panel, PanelShape)
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = info.number?.toString() ?: "",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(96.dp),
            )
            AsyncImage(
                model = info.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 96.dp, height = 56.dp),
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                info.group?.let {
                    Text(
                        "$it · ${info.position}/${info.total}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(8.dp))
                ProgrammeLines(info.programmes)
            }
        }
    }
}

@Composable
private fun ProgrammeLines(programmes: List<Programme>) {
    val context = LocalContext.current
    val timeFormat = remember(context) { DateFormat.getTimeFormat(context) }
    if (programmes.isEmpty()) {
        Text(stringResource(R.string.epg_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val nowSec = System.currentTimeMillis() / 1_000
    for (programme in programmes) {
        val onAir = programme.startSec <= nowSec && programme.stopSec > nowSec
        val label = stringResource(if (onAir) R.string.epg_now else R.string.epg_next)
        val start = timeFormat.format(Date(programme.startSec * 1_000))
        val stop = timeFormat.format(Date(programme.stopSec * 1_000))
        Text(
            text = "$label  $start-$stop  ${programme.title}",
            style = if (onAir) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (onAir) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onAir) {
            val fraction = ((nowSec - programme.startSec).toFloat() / (programme.stopSec - programme.startSec)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp),
            )
        }
    }
}

@Composable
fun NumberEntry(digits: String, modifier: Modifier = Modifier) {
    Text(
        text = digits,
        fontSize = 56.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .padding(32.dp)
            .background(Panel, PanelShape)
            .padding(horizontal = 28.dp, vertical = 8.dp),
    )
}

@Composable
fun ErrorOverlay(error: PlaybackError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val title = when (error.kind) {
        ErrorKind.SIGNAL_LOST -> stringResource(R.string.error_signal_lost)
        ErrorKind.ACCESS_DENIED -> stringResource(R.string.error_access_denied, error.httpCode)
        ErrorKind.NOT_FOUND -> stringResource(R.string.error_not_found, error.httpCode)
        ErrorKind.HTTP -> stringResource(R.string.error_http, error.httpCode)
        ErrorKind.DECODER -> stringResource(R.string.error_decoder)
        ErrorKind.DRM -> stringResource(R.string.error_drm)
        ErrorKind.FORMAT -> stringResource(R.string.error_format)
        ErrorKind.NETWORK -> stringResource(R.string.error_network)
    }
    var secondsLeft by remember(error) { mutableLongStateOf(0L) }
    LaunchedEffect(error) {
        while (error.retryAtMs > 0) {
            secondsLeft = ((error.retryAtMs - SystemClock.elapsedRealtime() + 999) / 1_000).coerceAtLeast(0)
            if (secondsLeft == 0L) break
            delay(250)
        }
    }
    Column(
        modifier = modifier
            .padding(32.dp)
            .background(Panel, PanelShape)
            .clickable(onClick = onRetry)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
        if (error.retryAtMs > 0 && secondsLeft > 0) {
            Text(stringResource(R.string.error_retry_in, secondsLeft.toInt()), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.error_retry_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** "Stats for nerds": refreshed once per second, only while visible. */
@Composable
fun StatsOverlay(provider: () -> PlaybackStats?, modifier: Modifier = Modifier) {
    var stats by remember { mutableStateOf<PlaybackStats?>(null) }
    LaunchedEffect(provider) {
        while (true) {
            stats = provider()
            delay(1_000)
        }
    }
    val s = stats ?: return
    val lines = buildList {
        add("Vidéo    ${s.video}")
        add("Audio    ${s.audio}")
        s.decoder?.let { add("Décodeur $it${if (s.tunneling) " (tunnel)" else ""}") }
        add("Tampon   ${s.bufferMs} ms   vitesse ${"%.2f".format(s.speed)}x")
        if (s.liveOffsetMs > 0) add("Direct   -${s.liveOffsetMs / 1_000} s")
        add("Débit    ${s.bandwidthKbps} kb/s estimés")
        add("Images   ${s.droppedFrames} perdues")
        add("Serveur  ${s.host}  ${s.tier}  niveau ${s.ladderLevel}${if (s.backupSource) "  (secours)" else ""}")
        add("Démarrage ${s.startupMs} ms   coupures ${s.rebuffers} (${s.rebufferMs} ms)   récupérations ${s.recoveries}")
    }
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        for (line in lines) {
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFB8FFB8))
        }
    }
}

/** Short message (toast) at the top of the screen. */
@Composable
fun ToastHost(toast: Toast?, onShown: (Toast) -> Unit, modifier: Modifier = Modifier) {
    if (toast == null) return
    LaunchedEffect(toast.id) {
        delay(2_500)
        onShown(toast)
    }
    val text = if (toast.arg != null) stringResource(toast.resId, toast.arg) else stringResource(toast.resId)
    Pill(text, modifier)
}

/** Informative notice from the player (backup source in use), shown for a few seconds. */
@Composable
fun NoticeHost(notice: Notice?, noticeAtMs: Long, modifier: Modifier = Modifier) {
    if (notice == null) return
    var visible by remember(noticeAtMs) { mutableStateOf(true) }
    LaunchedEffect(noticeAtMs) {
        delay(3_000)
        visible = false
    }
    if (!visible) return
    val text = when (notice) {
        Notice.BACKUP_SOURCE -> stringResource(R.string.notice_backup_source)
    }
    Pill(text, modifier)
}

@Composable
private fun Pill(text: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(24.dp)
            .background(Panel, RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

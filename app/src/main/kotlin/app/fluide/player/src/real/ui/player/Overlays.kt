package app.fluide.player.ui.player

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.formatDuration
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ look of the overlays drawn over the video

/** Overlay palette: the glass is darker than on the screens, the video behind can be bright. */
internal object OverlayColors {
    val Soft = Color(0xFFDCE3F2)
    val Muted = Color(0xFFAEB7D3)
    val Dim = Color(0xFF8A93B0)

    /** 1 dp border of the glass panels: white 12 %. */
    val Border = Color(0x1FFFFFFF)

    /** Empty part of the progress bars. */
    val Track = Color(0x2EFFFFFF)
}

/** Deep navy glass, slightly violet at the end (panels over the video). */
internal val OverlayBrush = Brush.linearGradient(listOf(Color(0xEB101836), Color(0xEB0A1028), Color(0xEB150C2E)))

private val OverlayShape = RoundedCornerShape(16.dp)
private val BannerShape = RoundedCornerShape(20.dp)
private val BannerAccent = Brush.horizontalGradient(listOf(FluideColors.Blue, FluideColors.Cyan, Color.Transparent))
private val LiveBrush = Brush.horizontalGradient(listOf(FluideColors.Warm, Color(0xFFFF9150)))
private val ProgressBrush = Brush.horizontalGradient(listOf(FluideColors.Blue, FluideColors.Cyan))

/** Time of the day in the format chosen in the settings (24 h or 12 h). */
@Composable
internal fun rememberClockFormat(clock24h: Boolean): java.text.DateFormat {
    val locale = Locale.getDefault()
    return remember(clock24h, locale) { SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", locale) }
}

/** Small label ("Maintenant", "REPLAY"...). */
@Composable
internal fun OverlayTag(
    text: String,
    fill: Brush,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
    border: Color? = null,
    large: Boolean = false,
) {
    val shape = RoundedCornerShape(if (large) 8.dp else 6.dp)
    Box(
        modifier
            .clip(shape)
            .background(fill, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 3.dp else 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (large) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            letterSpacing = 0.6.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun OverlayProgress(fraction: Float, modifier: Modifier = Modifier, brush: Brush = ProgressBrush, height: Dp = 4.dp) {
    Box(
        modifier
            .height(height)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(color = OverlayColors.Track, cornerRadius = radius)
                val filled = size.width * fraction.coerceIn(0f, 1f)
                if (filled > 0f) {
                    // Filled from the start side: from the right in right-to-left layouts.
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - filled else 0f
                    drawRoundRect(brush = brush, topLeft = Offset(x, 0f), size = Size(filled, size.height), cornerRadius = radius)
                }
            },
    )
}

// ------------------------------------------------------------------ overlays

/** Delay before the buffering spinner appears: short waits stay invisible. */
private const val SPINNER_DELAY_MS = 600L

/** Discreet spinner (36 dp, on a small dark disc), only if the wait lasts more than [SPINNER_DELAY_MS]. */
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
        Box(
            modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0x99070B1C), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp, color = FluideColors.Cyan)
        }
    }
}

/**
 * Zap banner: a glass bar with the channel logo, its number and name, the programme on air (times,
 * time left, progress) and the next one, and the clock. [clock24h] defaults to the system setting.
 */
@Composable
fun ZapBanner(
    banner: BannerInfo?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    clock24h: Boolean = DateFormat.is24HourFormat(LocalContext.current),
) {
    AnimatedVisibility(
        visible = visible && banner != null,
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
    ) {
        val info = banner ?: return@AnimatedVisibility
        val timeFormat = rememberClockFormat(clock24h)
        val nowSec = System.currentTimeMillis() / 1_000
        val now = info.programmes.firstOrNull { it.startSec <= nowSec && it.stopSec > nowSec }
        val next = info.programmes.firstOrNull { it !== now && it.startSec >= nowSec }
        Row(
            modifier = Modifier
                .padding(horizontal = 40.dp, vertical = 28.dp)
                .widthIn(max = 1_100.dp)
                .fillMaxWidth()
                .clip(BannerShape)
                .background(OverlayBrush, BannerShape)
                .border(1.dp, OverlayColors.Border, BannerShape)
                .drawBehind { drawRect(brush = BannerAccent, size = Size(size.width, 2.dp.toPx())) }
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BannerLogo(info.logo, info.name)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    info.number?.let {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(FluideGradient, RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 3.dp),
                        ) {
                            Text(
                                it.toString(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            info.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(info.group, "${info.position}/${info.total}").joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium,
                            color = OverlayColors.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Clock(clock24h, showDate = false)
                }
                Spacer(Modifier.height(12.dp))
                if (now == null && next == null) {
                    Text(stringResource(R.string.epg_none), style = MaterialTheme.typography.bodyMedium, color = OverlayColors.Muted)
                }
                if (now != null) BannerProgramme(now, live = true, timeFormat = timeFormat, nowSec = nowSec)
                if (next != null) {
                    if (now != null) Spacer(Modifier.height(8.dp))
                    BannerProgramme(next, live = false, timeFormat = timeFormat, nowSec = nowSec)
                }
            }
        }
    }
}

@Composable
private fun BannerLogo(logo: String?, name: String) {
    val shape = RoundedCornerShape(14.dp)
    var failed by remember(logo) { mutableStateOf(false) }
    Box(
        Modifier
            .size(width = 120.dp, height = 76.dp)
            .clip(shape)
            .background(Color(0x14FFFFFF), shape)
            .border(1.dp, OverlayColors.Border, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null && !failed) {
            AsyncImage(
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                onError = { failed = true },
            )
        } else {
            val words = name.split(' ', '-', '_', '.', '|', '/').filter { it.isNotBlank() }
            Text(
                words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "TV" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = OverlayColors.Muted,
                maxLines = 1,
            )
        }
    }
}

/** "Maintenant 20:45 – 21:40 Title  Reste 23 min" + progress, or "Ensuite 21:40 – 22:30 Title". */
@Composable
private fun BannerProgramme(p: Programme, live: Boolean, timeFormat: java.text.DateFormat, nowSec: Long) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlayTag(
                stringResource(if (live) R.string.epg_now else R.string.epg_next),
                if (live) LiveBrush else SolidColor(Color(0x1FFFFFFF)),
                modifier = Modifier.widthIn(min = 100.dp),
                textColor = if (live) Color.White else OverlayColors.Soft,
                large = true,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "${timeFormat.format(Date(p.startSec * 1_000))} – ${timeFormat.format(Date(p.stopSec * 1_000))}",
                style = MaterialTheme.typography.labelLarge,
                color = if (live) Color.White else OverlayColors.Muted,
                maxLines = 1,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                p.title,
                style = if (live) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal,
                color = if (live) Color.White else OverlayColors.Soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (live) {
                Text(
                    stringResource(R.string.live_remaining, formatDuration(p.stopSec - nowSec)),
                    style = MaterialTheme.typography.labelMedium,
                    color = FluideColors.Cyan,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        if (live) {
            val span = (p.stopSec - p.startSec).coerceAtLeast(1)
            val fraction = ((nowSec - p.startSec).toFloat() / span).coerceIn(0f, 1f)
            OverlayProgress(fraction, Modifier.fillMaxWidth().padding(top = 8.dp), brush = LiveBrush)
        }
    }
}

/** Channel number being typed with the number keys of the remote. */
@Composable
fun NumberEntry(digits: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .clip(OverlayShape)
            .background(OverlayBrush, OverlayShape)
            .border(1.dp, FluideColors.Cyan.copy(alpha = 0.45f), OverlayShape)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.live_number_label),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = FluideColors.Cyan,
            letterSpacing = 1.sp,
        )
        Text(
            text = digits,
            fontSize = 56.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            letterSpacing = 4.sp,
            maxLines = 1,
        )
        Box(
            Modifier
                .width(48.dp)
                .height(3.dp)
                .background(FluideGradient, CircleShape),
        )
    }
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
            .widthIn(max = 560.dp)
            .clip(OverlayShape)
            .background(OverlayBrush, OverlayShape)
            .border(1.dp, FluideColors.Warm.copy(alpha = 0.35f), OverlayShape)
            .clickable(onClick = onRetry)
            .padding(horizontal = 32.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(FluideColors.Warm.copy(alpha = 0.16f), CircleShape)
                .border(2.dp, FluideColors.Warm, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("!", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = FluideColors.Warm)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
        if (error.retryAtMs > 0 && secondsLeft > 0) {
            Text(stringResource(R.string.error_retry_in, secondsLeft.toInt()), color = OverlayColors.Muted)
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(FluideGradient, RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(Glyph.REFRESH, Color.White, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.error_retry_hint), color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
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
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .padding(24.dp)
            .clip(shape)
            .background(Color(0xD9070B1C), shape)
            .border(1.dp, OverlayColors.Border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            Box(
                Modifier
                    .size(width = 14.dp, height = 3.dp)
                    .background(FluideGradient, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.options_stats),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = FluideColors.Cyan,
            )
        }
        for (line in lines) {
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFCDEBFF))
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
    val text = when {
        toast.text != null -> stringResource(toast.resId, toast.text)
        toast.arg != null -> stringResource(toast.resId, toast.arg)
        else -> stringResource(toast.resId)
    }
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
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier = modifier
            .padding(24.dp)
            .clip(shape)
            .background(OverlayBrush, shape)
            .border(1.dp, OverlayColors.Border, shape)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(FluideGradient, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

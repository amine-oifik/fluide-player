package app.fluide.player.ui.account

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluide.player.R
import app.fluide.player.data.SourceInfo
import app.fluide.player.data.SourceType
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.Clock
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.ScreenSurface
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.components.fluideGlass
import app.fluide.player.ui.theme.blend
import app.fluide.player.ui.tools.ServerInfo
import app.fluide.player.ui.tools.SpeedTestPanel
import app.fluide.player.ui.tools.SpeedTestState
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(20.dp)
private val TileShape = RoundedCornerShape(12.dp)

/** Scale of the remaining-time bar: a full bar is a year (or more) of subscription left. */
private const val BAR_SCALE_DAYS = 365f
private const val DAY_SECONDS = 86_400L

/**
 * The active playlist and its subscription (server, masked user name, status, expiry, connections,
 * catalogue), with a connection test against the playlist's own server. One row of buttons: the D-pad
 * only moves left / right, OK starts or stops the test; the focus starts on "Tester la connexion".
 */
@Composable
fun AccountScreen(vm: MainViewModel) {
    val ui by vm.ui.collectAsState()
    val sources by vm.sources.collectAsState()
    val settings by vm.settings.collectAsState()
    val test by vm.speedTest.state.collectAsState()
    val source = sources.firstOrNull { it.id == ui.activeSourceId }
    val sourceId = source?.id ?: 0L
    // Server and user of the first channel, read off the UI thread; reloaded after an update of the playlist.
    val server by produceState<ServerInfo?>(null, sourceId, source?.updatedAt) {
        value = if (sourceId == 0L) null else vm.speedTest.server(sourceId)
    }
    // Results of another playlist (switched since) are not shown.
    val shown = if (test.sourceId == sourceId) test else SpeedTestState()
    val testFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { testFocus.requestFocus() } }
    // Leaving the screen stops a running test and closes its connection.
    DisposableEffect(Unit) { onDispose { vm.speedTest.cancel() } }

    ScreenSurface {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val gutter = if (wide) 48.dp else 16.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = gutter, vertical = if (wide) 22.dp else 16.dp),
            ) {
                Header(clock24h = settings.clock24h, showClock = wide)
                Spacer(Modifier.height(14.dp))
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                        AccountCard(source, server, settings.clock24h, Modifier.weight(1.15f))
                        SpeedTestPanel(shown, Modifier.weight(1f), clock24h = settings.clock24h)
                    }
                } else {
                    AccountCard(source, server, settings.clock24h, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(14.dp))
                    SpeedTestPanel(shown, Modifier.fillMaxWidth(), clock24h = settings.clock24h)
                }
                Spacer(Modifier.height(16.dp))
                Actions(
                    state = shown,
                    hasSource = source != null,
                    testFocus = testFocus,
                    onTest = { if (shown.running) vm.speedTest.cancel() else vm.runSpeedTest() },
                    onSwitch = vm::openSources,
                    onRefresh = vm::refreshActive,
                    onBack = { vm.onBack() },
                )
            }
        }
    }
}

@Composable
private fun Header(clock24h: Boolean, showClock: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(46.dp)
                .background(FluideGradient, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AppIcon(Glyph.USER, FluideColors.OnAccent, size = 24.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.account_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = FluideColors.Text,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.account_subtitle),
                color = FluideColors.Muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showClock) Clock(clock24h, showDate = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    state: SpeedTestState,
    hasSource: Boolean,
    testFocus: FocusRequester,
    onTest: () -> Unit,
    onSwitch: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val testLabel = when {
            state.running -> R.string.account_action_stop
            state.finishedAt > 0 -> R.string.account_action_retest
            else -> R.string.account_action_test
        }
        // Stays enabled without a playlist: the test then explains that there is nothing to test, and the
        // initial focus always has a target.
        TvButton(
            text = stringResource(testLabel),
            onClick = onTest,
            // Same width whatever the label (start, stop, again): the other buttons do not move.
            modifier = Modifier.widthIn(min = 240.dp),
            glyph = if (state.running) Glyph.PAUSE else Glyph.SPEED,
            primary = true,
            focusRequester = testFocus,
        )
        TvButton(stringResource(R.string.account_action_switch), onSwitch, glyph = Glyph.PLAYLIST)
        TvButton(stringResource(R.string.account_action_refresh), onRefresh, glyph = Glyph.REFRESH, enabled = hasSource)
        TvButton(stringResource(R.string.account_action_back), onBack, glyph = Glyph.BACK)
    }
}

// ---------------------------------------------------------------------------------------- account card

@Composable
private fun AccountCard(source: SourceInfo?, server: ServerInfo?, clock24h: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fluideGlass(CardShape)
            .padding(horizontal = 22.dp, vertical = 16.dp),
    ) {
        if (source == null) NoSource() else AccountDetails(source, server, clock24h)
    }
}

@Composable
private fun NoSource() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Glyph.PLAYLIST, FluideColors.Muted, size = 26.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.account_no_source),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = FluideColors.Text,
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.account_no_source_hint), color = FluideColors.Muted, fontSize = 14.sp, lineHeight = 19.sp)
}

/** Content of the account card (emitted into its column). */
@Composable
private fun AccountDetails(source: SourceInfo, server: ServerInfo?, clock24h: Boolean) {
    val nowSec = remember(source) { System.currentTimeMillis() / 1_000 }
    val xtream = source.type == SourceType.XTREAM
    val expires = source.expiresAtSec
    val expired = expires != null && expires <= nowSec

    // Name, type, status.
    Text(
        source.name,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = FluideColors.Text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Chip(stringResource(typeName(source.type)), FluideColors.Violet)
        val (statusText, tone) = status(source, expired)
        Chip(statusText, toneColor(tone))
        if (server?.secure == true) Chip(stringResource(R.string.account_secure), FluideColors.Azure, glyph = Glyph.LOCK)
    }

    Spacer(Modifier.height(14.dp))
    // Server, user, connections, format.
    val unknown = stringResource(R.string.account_unknown)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Info(stringResource(R.string.account_server), server?.host ?: unknown, Modifier.weight(1.3f))
        Info(stringResource(R.string.account_user), server?.maskedUser ?: unknown, Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val active = source.activeConnections
        val connections = if (active != null) {
            stringResource(R.string.account_connections_value, active, source.maxConnections)
        } else {
            stringResource(R.string.account_connections_max, source.maxConnections)
        }
        Info(stringResource(R.string.account_connections), connections, Modifier.weight(1.3f))
        if (xtream) {
            val format = if (source.streamFormat.equals("m3u8", ignoreCase = true)) {
                stringResource(R.string.account_format_hls)
            } else {
                stringResource(R.string.account_format_ts)
            }
            Info(stringResource(R.string.account_format), format, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }

    if (xtream) {
        Spacer(Modifier.height(14.dp))
        Expiry(expires, nowSec)
    }

    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat(source.channelCount, stringResource(R.string.account_stat_channels), Modifier.weight(1f))
        if (xtream || source.movieCount > 0) {
            Stat(source.movieCount, stringResource(R.string.account_stat_movies), Modifier.weight(1f))
        }
        if (xtream || source.seriesCount > 0) {
            Stat(source.seriesCount, stringResource(R.string.account_stat_series), Modifier.weight(1f))
        }
        Stat(source.programmeCount, stringResource(R.string.account_stat_programmes), Modifier.weight(1f))
    }

    Spacer(Modifier.height(10.dp))
    val updated = if (source.updatedAt > 0) {
        stringResource(R.string.account_updated, formatDateTime(source.updatedAt, clock24h))
    } else {
        stringResource(R.string.account_never_updated)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(Glyph.REFRESH, FluideColors.Dim, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(updated, color = FluideColors.Muted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (source.epgUpdatedAt > 0) {
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Glyph.GUIDE, FluideColors.Dim, size = 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.account_epg_updated, formatDateTime(source.epgUpdatedAt, clock24h)),
                color = FluideColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val error = source.lastError
    if (!error.isNullOrBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.account_last_error, error),
            color = FluideColors.Warm,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Expiry date, days left and the remaining-time bar (Xtream accounts). */
@Composable
private fun Expiry(expiresAtSec: Long?, nowSec: Long) {
    val locale = Locale.getDefault()
    val daysLeft = expiresAtSec?.let { (it - nowSec) / DAY_SECONDS }
    val label: String
    val left: String?
    val fraction: Float
    val alert: Boolean
    when {
        expiresAtSec == null -> {
            label = stringResource(R.string.account_unlimited)
            left = null
            fraction = 1f
            alert = false
        }
        expiresAtSec <= nowSec -> {
            label = stringResource(R.string.account_expired_on, formatDate(expiresAtSec * 1_000, locale))
            left = null
            fraction = 0f
            alert = true
        }
        else -> {
            val days = (daysLeft ?: 0L).toInt()
            label = stringResource(R.string.account_expires_on, formatDate(expiresAtSec * 1_000, locale))
            left = if (days == 0) stringResource(R.string.account_last_day) else pluralStringResource(R.plurals.account_days_left, days, days)
            fraction = (days / BAR_SCALE_DAYS).coerceIn(0.02f, 1f)
            alert = days < 7
        }
    }
    // The bar fills up when the screen opens.
    val bar = remember { Animatable(0f) }
    LaunchedEffect(fraction) { bar.animateTo(fraction, tween(durationMillis = 900, easing = FastOutSlowInEasing)) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Glyph.CALENDAR, if (alert) FluideColors.Warm else FluideColors.Cyan, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.account_expiry).uppercase(locale),
                    color = FluideColors.Dim,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                )
                Text(
                    label,
                    color = if (alert) FluideColors.Warm else FluideColors.Soft,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (left != null) {
                Spacer(Modifier.width(10.dp))
                Text(
                    left,
                    color = if (alert) FluideColors.Warm else FluideColors.Cyan,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val fill: Brush = if (alert) {
            Brush.horizontalGradient(listOf(FluideColors.Warm, blend(FluideColors.Warm, Color.White, 0.3f)))
        } else {
            FluideGradient
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Color.White.copy(alpha = 0.08f), CircleShape),
        ) {
            if (bar.value > 0.001f) {
                Box(
                    Modifier
                        .fillMaxWidth(bar.value)
                        .fillMaxHeight()
                        .background(fill, CircleShape),
                )
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label.uppercase(Locale.getDefault()),
            color = FluideColors.Dim,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = FluideColors.Soft,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier = Modifier) {
    val formatted = remember(value) { NumberFormat.getIntegerInstance(Locale.getDefault()).format(value.toLong()) }
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.04f), TileShape)
            .border(1.dp, FluideColors.GlassBorder, TileShape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            formatted,
            color = FluideColors.Text,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            color = FluideColors.Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small pill: coloured dot (or [glyph]) and text, tinted by [color]. */
@Composable
private fun Chip(text: String, color: Color, glyph: Glyph? = null) {
    Row(
        Modifier
            .background(color.copy(alpha = 0.16f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            AppIcon(glyph, color, size = 12.dp)
        } else {
            Box(
                Modifier
                    .size(7.dp)
                    .background(color, CircleShape),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = blend(color, Color.White, 0.4f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

private enum class Tone { GOOD, BAD, NEUTRAL }

private fun toneColor(tone: Tone): Color = when (tone) {
    Tone.GOOD -> FluideColors.Cyan
    Tone.BAD -> FluideColors.Warm
    Tone.NEUTRAL -> FluideColors.Muted
}

private fun typeName(type: SourceType): Int = when (type) {
    SourceType.XTREAM -> R.string.account_type_xtream
    SourceType.URL -> R.string.account_type_url
    SourceType.FILE -> R.string.account_type_file
}

/** Status chip: the Xtream account status, else whether the last update worked. */
@Composable
private fun status(source: SourceInfo, expired: Boolean): Pair<String, Tone> {
    if (source.type != SourceType.XTREAM) {
        return if (source.lastError.isNullOrBlank()) {
            stringResource(R.string.account_status_ready) to Tone.GOOD
        } else {
            stringResource(R.string.account_status_error) to Tone.BAD
        }
    }
    if (expired) return stringResource(R.string.account_status_expired) to Tone.BAD
    val raw = source.accountStatus?.trim().orEmpty()
    return when (raw.lowercase(Locale.ROOT)) {
        "active" -> stringResource(R.string.account_status_active) to Tone.GOOD
        "expired" -> stringResource(R.string.account_status_expired) to Tone.BAD
        "banned" -> stringResource(R.string.account_status_banned) to Tone.BAD
        "disabled" -> stringResource(R.string.account_status_disabled) to Tone.BAD
        "refused" -> stringResource(R.string.account_status_refused) to Tone.BAD
        "trial" -> stringResource(R.string.account_status_trial) to Tone.NEUTRAL
        "" -> stringResource(R.string.account_unknown) to Tone.NEUTRAL
        else -> raw to Tone.NEUTRAL
    }
}

/** "12 mars 2027". */
private fun formatDate(ms: Long, locale: Locale): String =
    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMMyyyy"), locale).format(Date(ms))

/** "20 oct. 2026 14:05". */
private fun formatDateTime(ms: Long, clock24h: Boolean): String {
    val locale = Locale.getDefault()
    val skeleton = if (clock24h) "dMMMyyyyHHmm" else "dMMMyyyyhmma"
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}

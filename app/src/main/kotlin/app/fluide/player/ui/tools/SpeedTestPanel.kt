package app.fluide.player.ui.tools

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluide.player.R
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.fluideGlass
import app.fluide.player.ui.theme.LocalFluidePalette
import app.fluide.player.ui.theme.blend
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PanelShape = RoundedCornerShape(20.dp)
private val MetricShape = RoundedCornerShape(12.dp)

// Gauge geometry (dp): a 240 degree arc open at the bottom, quality labels outside, test progress inside.
private val GaugeWidth = 264.dp
private val GaugeHeight = 182.dp
private val GaugeStroke = 14.dp
private val GaugeLabelSpace = 18.dp
private val GaugeRadius = minOf(GaugeWidth / 2 - GaugeStroke / 2 - GaugeLabelSpace, (GaugeHeight - GaugeStroke - GaugeLabelSpace) / 1.5f)
private val GaugeCenterY = GaugeLabelSpace + GaugeStroke / 2 + GaugeRadius

private const val ARC_START = 150f
private const val ARC_SWEEP = 240f

/**
 * Speeds (kbit/s) at the even marks of the gauge: the verdict thresholds sit on the marks, so the arc
 * reads as a quality scale (SD, HD, Full HD, 4K) rather than a linear speed axis.
 */
private val SCALE_KBPS = longArrayOf(0, 1_500, 3_000, 7_000, 20_000, 50_000, 100_000)

/**
 * Result of the connection test: an animated gauge (speed on the quality scale, test progress on an
 * inner ring), the speed in Mbit/s, the latency, the data received and what the connection allows.
 * Display only: the screen showing it owns the buttons (start, stop).
 */
@Composable
fun SpeedTestPanel(state: SpeedTestState, modifier: Modifier = Modifier, clock24h: Boolean = true) {
    Column(
        modifier
            .fluideGlass(PanelShape)
            .padding(horizontal = 22.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(Glyph.SPEED, FluideColors.Cyan, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.account_speed_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = FluideColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!state.running && state.finishedAt > 0 && state.errorKind == null) {
                val time = remember(state.finishedAt, clock24h) {
                    SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(state.finishedAt))
                }
                Text(stringResource(R.string.account_speed_tested_at, time), color = FluideColors.Dim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(4.dp))
        Gauge(state)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val latency = state.latencyMs
            val (note, noteColor) = when {
                latency == null -> null to FluideColors.Muted
                latency < 80 -> stringResource(R.string.account_speed_latency_fast) to FluideColors.Cyan
                latency < 200 -> stringResource(R.string.account_speed_latency_ok) to FluideColors.Soft
                else -> stringResource(R.string.account_speed_latency_slow) to FluideColors.Warm
            }
            Metric(
                label = stringResource(R.string.account_speed_latency),
                value = latency?.let { stringResource(R.string.account_speed_latency_value, it.toInt()) }
                    ?: stringResource(R.string.account_unknown),
                note = note,
                noteColor = noteColor,
                modifier = Modifier.weight(1f),
            )
            Metric(
                label = stringResource(R.string.account_speed_data),
                value = if (state.bytes > 0) {
                    stringResource(R.string.account_speed_data_value, String.format(Locale.getDefault(), "%.1f", state.bytes / 1_048_576.0))
                } else {
                    stringResource(R.string.account_unknown)
                },
                note = null,
                noteColor = FluideColors.Muted,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        Message(state)
    }
}

/** Speed [kbps] as a 0..1 position on the gauge (piecewise linear between the marks of [SCALE_KBPS]). */
private fun scalePosition(kbps: Long): Float {
    if (kbps <= 0) return 0f
    val last = SCALE_KBPS.size - 1
    for (i in 1..last) {
        if (kbps < SCALE_KBPS[i]) {
            val within = (kbps - SCALE_KBPS[i - 1]).toFloat() / (SCALE_KBPS[i] - SCALE_KBPS[i - 1])
            return (i - 1 + within) / last
        }
    }
    return 1f
}

/** "4,8" / "18,4" / "142" (decimal separator of the locale). */
private fun formatMbps(kbps: Long): String {
    val mbps = kbps / 1_000.0
    return if (mbps >= 100) mbps.roundToInt().toString() else String.format(Locale.getDefault(), "%.1f", mbps)
}

@Composable
private fun Gauge(state: SpeedTestState) {
    val palette = LocalFluidePalette.current
    val position by animateFloatAsState(
        targetValue = scalePosition(state.downloadKbps ?: 0L),
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "speedGauge",
    )
    val progress by animateFloatAsState(
        targetValue = if (state.running) state.progress else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "speedProgress",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (state.running) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "speedRing",
    )
    val measurer = rememberTextMeasurer()
    val labels = listOf(
        stringResource(R.string.account_speed_scale_sd),
        stringResource(R.string.account_speed_scale_hd),
        stringResource(R.string.account_speed_scale_full_hd),
        stringResource(R.string.account_speed_scale_uhd),
    )
    // Theme colours, read in composition (FluideTheme recreates the content when the theme changes).
    val blue = palette.blue
    val azure = palette.azure
    val cyan = palette.cyan
    val aqua = palette.aqua
    val violet = palette.violet
    val dim = palette.dim
    val knob = palette.text
    val kbps = state.downloadKbps
    Box(Modifier.size(GaugeWidth, GaugeHeight)) {
        Spacer(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val stroke = GaugeStroke.toPx()
                    val radius = GaugeRadius.toPx()
                    val center = Offset(size.width / 2f, GaugeCenterY.toPx())
                    val arcTopLeft = Offset(center.x - radius, center.y - radius)
                    val arcSize = Size(radius * 2f, radius * 2f)
                    val ringWidth = 3.dp.toPx()
                    val ringRadius = radius - stroke / 2f - 13.dp.toPx()
                    val ringTopLeft = Offset(center.x - ringRadius, center.y - ringRadius)
                    val ringSize = Size(ringRadius * 2f, ringRadius * 2f)
                    val tickOuter = radius - stroke / 2f - 3.dp.toPx()
                    val tickInner = tickOuter - 6.dp.toPx()
                    val labelRadius = radius + stroke / 2f + 9.dp.toPx()
                    val arcStroke = Stroke(width = stroke, cap = StrokeCap.Round)
                    val ringStroke = Stroke(width = ringWidth, cap = StrokeCap.Round)
                    // Drawn rotated by ARC_START: the gradient then starts where the arc starts. The last
                    // stop goes back to the first colour so the round cap before the start is not cyan.
                    val valueBrush = Brush.sweepGradient(
                        0f to blue,
                        0.3f to azure,
                        0.5f to cyan,
                        0.667f to aqua,
                        0.9f to aqua,
                        1f to blue,
                        center = center,
                    )
                    val labelStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    val texts = labels.map { measurer.measure(it, labelStyle) }
                    val marks = SCALE_KBPS.size - 1
                    fun pointAt(fraction: Float, r: Float): Offset {
                        val radians = (ARC_START + ARC_SWEEP * fraction) * PI.toFloat() / 180f
                        return Offset(center.x + r * cos(radians), center.y + r * sin(radians))
                    }
                    onDrawBehind {
                        val value = position
                        drawArc(
                            color = Color.White.copy(alpha = 0.08f),
                            startAngle = ARC_START,
                            sweepAngle = ARC_SWEEP,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = arcStroke,
                        )
                        // Marks; the quality labels (SD, HD, FHD, 4K) light up once reached.
                        for (i in 1 until marks) {
                            val fraction = i.toFloat() / marks
                            val reached = value >= fraction - 0.001f
                            drawLine(
                                color = if (reached) cyan else dim,
                                start = pointAt(fraction, tickInner),
                                end = pointAt(fraction, tickOuter),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            val text = texts.getOrNull(i - 1) ?: continue
                            val at = pointAt(fraction, labelRadius)
                            drawText(
                                textLayoutResult = text,
                                color = if (reached) cyan else dim,
                                topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f),
                            )
                        }
                        val sweep = ARC_SWEEP * value
                        if (sweep > 0.5f) {
                            rotate(degrees = ARC_START, pivot = center) {
                                drawArc(
                                    brush = valueBrush,
                                    startAngle = 0f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = arcTopLeft,
                                    size = arcSize,
                                    style = arcStroke,
                                )
                            }
                            val end = pointAt(value, radius)
                            drawCircle(color = aqua.copy(alpha = 0.35f), radius = stroke * 0.95f, center = end)
                            drawCircle(color = knob, radius = stroke * 0.36f, center = end)
                        }
                        val alpha = ringAlpha
                        if (alpha > 0.01f) {
                            drawArc(
                                color = Color.White.copy(alpha = 0.06f * alpha),
                                startAngle = ARC_START,
                                sweepAngle = ARC_SWEEP,
                                useCenter = false,
                                topLeft = ringTopLeft,
                                size = ringSize,
                                style = ringStroke,
                            )
                            val done = progress
                            if (done > 0.003f) {
                                drawArc(
                                    color = violet.copy(alpha = alpha),
                                    startAngle = ARC_START,
                                    sweepAngle = ARC_SWEEP * done,
                                    useCenter = false,
                                    topLeft = ringTopLeft,
                                    size = ringSize,
                                    style = ringStroke,
                                )
                            }
                        }
                    }
                },
        )
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = GaugeCenterY - 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (kbps != null) formatMbps(kbps) else stringResource(R.string.account_unknown),
                color = FluideColors.Text,
                fontSize = 46.sp,
                lineHeight = 52.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
            Text(
                stringResource(R.string.account_speed_unit),
                color = FluideColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, note: String?, noteColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Color.White.copy(alpha = 0.04f), MetricShape)
            .border(1.dp, FluideColors.GlassBorder, MetricShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, color = FluideColors.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = FluideColors.Text, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (note != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    note,
                    color = noteColor,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** What is going on, or what the result means. Fixed minimum height: the panel does not jump between states. */
@Composable
private fun Message(state: SpeedTestState) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val kind = state.errorKind
        val verdict = state.verdict
        when {
            state.running -> Text(
                text = if (state.phase == SpeedTestPhase.LATENCY) {
                    stringResource(R.string.account_speed_phase_latency)
                } else {
                    stringResource(R.string.account_speed_phase_download, (state.progress * 100).roundToInt().coerceIn(0, 100))
                },
                color = FluideColors.Soft,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            kind != null -> {
                Text(
                    stringResource(R.string.account_speed_error_title),
                    color = FluideColors.Warm,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(stringResource(errorText(kind)), color = FluideColors.Soft, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
                state.error?.let {
                    Text(it, color = FluideColors.Dim, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            verdict != null -> {
                Text(
                    stringResource(verdictText(verdict)),
                    color = verdictColor(verdict),
                    fontSize = 19.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                )
                Text(stringResource(verdictHint(verdict)), color = FluideColors.Muted, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
            }
            else -> Text(
                stringResource(R.string.account_speed_intro),
                color = FluideColors.Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun verdictText(verdict: SpeedVerdict): Int = when (verdict) {
    SpeedVerdict.POOR -> R.string.account_speed_verdict_poor
    SpeedVerdict.SD -> R.string.account_speed_verdict_sd
    SpeedVerdict.HD -> R.string.account_speed_verdict_hd
    SpeedVerdict.FULL_HD -> R.string.account_speed_verdict_full_hd
    SpeedVerdict.UHD -> R.string.account_speed_verdict_uhd
}

private fun verdictHint(verdict: SpeedVerdict): Int = when (verdict) {
    SpeedVerdict.POOR -> R.string.account_speed_hint_poor
    SpeedVerdict.SD -> R.string.account_speed_hint_sd
    SpeedVerdict.HD -> R.string.account_speed_hint_hd
    SpeedVerdict.FULL_HD -> R.string.account_speed_hint_full_hd
    SpeedVerdict.UHD -> R.string.account_speed_hint_uhd
}

private fun verdictColor(verdict: SpeedVerdict): Color = when (verdict) {
    SpeedVerdict.POOR -> FluideColors.Warm
    SpeedVerdict.SD -> blend(FluideColors.Warm, FluideColors.Soft, 0.55f)
    SpeedVerdict.HD -> FluideColors.Azure
    SpeedVerdict.FULL_HD -> FluideColors.Cyan
    SpeedVerdict.UHD -> FluideColors.Aqua
}

private fun errorText(kind: SpeedTestError): Int = when (kind) {
    SpeedTestError.NO_SOURCE -> R.string.account_speed_error_no_source
    SpeedTestError.NO_STREAM -> R.string.account_speed_error_no_stream
    SpeedTestError.REFUSED -> R.string.account_speed_error_refused
    SpeedTestError.HTTP -> R.string.account_speed_error_http
    SpeedTestError.TIMEOUT -> R.string.account_speed_error_timeout
    SpeedTestError.NETWORK -> R.string.account_speed_error_network
    SpeedTestError.NO_DATA -> R.string.account_speed_error_no_data
}

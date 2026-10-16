package app.fluide.player.ui.components

import android.text.format.DateFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import app.fluide.player.R
import app.fluide.player.ui.PinRequest
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Dark navy glass for the panels drawn over the video. */
val PanelColor = Color(0xE80A1028)

/** Background gradient of the full screens (home, movies, series...): navy to very dark violet, diagonal. */
val ScreenBrush = Brush.linearGradient(listOf(FluideColors.Navy, FluideColors.NavyLight, FluideColors.Night))

private val ButtonShape = RoundedCornerShape(24.dp)
private val GlassFill = SolidColor(FluideColors.Glass)
private val DisabledFill = SolidColor(Color(0x0AFFFFFF))
private val PrimaryFill = Brush.linearGradient(listOf(FluideColors.Blue.copy(alpha = 0.55f), FluideColors.Cyan.copy(alpha = 0.32f)))
private val DialogShape = RoundedCornerShape(24.dp)
private val DialogBrush = Brush.verticalGradient(listOf(Color(0xFF161D40), Color(0xFF0D1230)))

/**
 * A button for the remote (clear focus highlight, OK and long OK) and for touch: a glass pill,
 * filled with the blue to cyan gradient and slightly enlarged while focused.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val active = focused && enabled
    val scale by animateFloatAsState(if (active) 1.06f else 1f, label = "tvButton")
    val fill = when {
        !enabled -> DisabledFill
        active -> FluideGradient
        primary -> PrimaryFill
        else -> GlassFill
    }
    val borderColor = when {
        active -> Color.White
        primary && enabled -> FluideColors.Cyan.copy(alpha = 0.45f)
        else -> FluideColors.GlassBorder
    }
    val content = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else Color.White
    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // Soft blue / cyan glow under the focused button (colored shadows from Android 9).
                shadowElevation = if (active) 12.dp.toPx() else 0f
                ambientShadowColor = FluideColors.Cyan
                spotShadowColor = FluideColors.Blue
                shape = ButtonShape
                clip = true
            }
            .background(fill, ButtonShape)
            .border(if (active) 2.dp else 1.dp, borderColor, ButtonShape)
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            AppIcon(glyph, content, size = 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text,
            color = content,
            // Same weight focused or not: the button keeps its width when the focus moves.
            fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Current time, refreshed every 15 s. */
@Composable
fun Clock(clock24h: Boolean, modifier: Modifier = Modifier, showDate: Boolean = true) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    val locale = Locale.getDefault()
    val time = remember(now, clock24h) { SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", locale).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(now))
            .replaceFirstChar { it.titlecase(locale) }
    }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(time, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = 0.5.sp)
        if (showDate) Text(date, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Yes / no question (exit the app...). */
@Composable
fun ConfirmDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val confirmFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(DialogBrush, DialogShape)
                .border(1.dp, FluideColors.GlassBorder, DialogShape)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AccentBar()
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvButton(stringResource(R.string.yes), onConfirm, Modifier.width(120.dp), primary = true, focusRequester = confirmFocus)
                TvButton(stringResource(R.string.no), onDismiss, Modifier.width(120.dp))
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
}

/**
 * Parental PIN: four digits typed with the number keys of the remote or with the on-screen keypad
 * (D-pad + OK, or touch).
 */
@Composable
fun PinDialog(request: PinRequest, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var digits by remember(request.id) { mutableStateOf("") }
    val firstKey = remember { FocusRequester() }
    fun add(d: Char) {
        if (digits.length >= 4) return
        digits += d
        if (digits.length == 4) onSubmit(digits)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .background(DialogBrush, DialogShape)
                .border(1.dp, FluideColors.GlassBorder, DialogShape)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = digitOf(event.key)
                    when {
                        digit != null -> {
                            add(digit)
                            true
                        }
                        event.key == Key.Backspace || event.key == Key.Delete -> {
                            digits = digits.dropLast(1)
                            true
                        }
                        else -> false
                    }
                }
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(Glyph.LOCK, FluideColors.Cyan, size = 22.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (request.changing) R.string.pin_new_title else R.string.pin_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    val filled = i < digits.length
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (filled) FluideGradient else SolidColor(FluideColors.Glass), CircleShape)
                            .border(1.5.dp, if (filled) FluideColors.Cyan else Color.White.copy(alpha = 0.25f), CircleShape),
                    )
                }
            }
            when {
                request.error && request.changing -> Text(stringResource(R.string.pin_invalid), color = MaterialTheme.colorScheme.error)
                request.error -> Text(stringResource(R.string.pin_wrong), color = MaterialTheme.colorScheme.error)
                !request.changing -> Text(
                    stringResource(R.string.pin_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val rows = listOf("123", "456", "789")
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (c in row) {
                        TvButton(
                            c.toString(),
                            onClick = { add(c) },
                            modifier = Modifier.width(72.dp),
                            focusRequester = if (c == '1') firstKey else null,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton("⌫", onClick = { digits = digits.dropLast(1) }, modifier = Modifier.width(72.dp))
                TvButton("0", onClick = { add('0') }, modifier = Modifier.width(72.dp))
                TvButton("✕", onClick = onDismiss, modifier = Modifier.width(72.dp))
            }
            Spacer(Modifier.height(2.dp))
        }
    }
    LaunchedEffect(request.id) {
        digits = ""
        runCatching { firstKey.requestFocus() }
    }
}

/** Short blue to cyan bar on top of the dialogs. */
@Composable
private fun AccentBar() {
    Box(
        Modifier
            .width(44.dp)
            .height(4.dp)
            .background(FluideGradient, CircleShape),
    )
}

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/** "1 h 42 min", "45 min". */
fun formatDuration(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "$h h ${m.toString().padStart(2, '0')}" else "$m min"
}

/** "1:42:10" / "42:10". */
fun formatClock(ms: Long): String {
    val total = (ms / 1_000).coerceAtLeast(0)
    val h = total / 3_600
    val m = total % 3_600 / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}" else "$m:${s.toString().padStart(2, '0')}"
}

/**
 * Full-screen background: the [ScreenBrush] gradient with two soft glows (blue at the top left,
 * violet at the bottom right).
 */
@Composable
fun ScreenSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val radius = size.maxDimension.coerceAtLeast(1f)
                val blueGlow = Brush.radialGradient(
                    listOf(FluideColors.Blue.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.1f, 0f),
                    radius = radius * 0.55f,
                )
                val violetGlow = Brush.radialGradient(
                    listOf(FluideColors.Violet.copy(alpha = 0.12f), Color.Transparent),
                    center = Offset(size.width, size.height),
                    radius = radius * 0.5f,
                )
                onDrawBehind {
                    drawRect(ScreenBrush)
                    drawRect(blueGlow)
                    drawRect(violetGlow)
                }
            },
    ) { content() }
}

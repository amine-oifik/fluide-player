package app.fluide.player.ui.components

import android.text.format.DateFormat
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.fluide.player.R
import app.fluide.player.ui.PinRequest
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val PanelColor = Color(0xE60B1020)

/** Dark blue gradient of the full screens (home, movies, series...). */
val ScreenBrush = Brush.linearGradient(listOf(Color(0xFF0A1330), Color(0xFF070B1C), Color(0xFF140A2A)))

/**
 * A button for the remote (clear focus highlight, OK and long OK) and for touch.
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
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val background = when {
        !enabled -> colors.surfaceVariant.copy(alpha = 0.4f)
        focused -> colors.primary
        primary -> colors.primary.copy(alpha = 0.35f)
        else -> colors.surfaceVariant
    }
    val content = if (!enabled) colors.onSurfaceVariant.copy(alpha = 0.5f) else Color.White
    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(background, shape)
            .border(2.dp, if (focused) Color.White.copy(alpha = 0.8f) else Color.Transparent, shape)
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (glyph != null) {
            AppIcon(glyph, content, size = 20.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = content, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Text(time, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
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
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
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
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(if (request.changing) R.string.pin_new_title else R.string.pin_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (i < digits.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** Full-screen gradient background with the usual padding. */
@Composable
fun ScreenSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(ScreenBrush)) { content() }
}

package app.fluide.player.ui.reminders

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.components.AppIcon
import app.fluide.player.ui.components.FluideColors
import app.fluide.player.ui.components.FluideGradient
import app.fluide.player.ui.components.Glyph
import app.fluide.player.ui.components.TvButton
import app.fluide.player.ui.components.fluideGlass
import app.fluide.player.ui.components.formatDuration
import app.fluide.player.ui.theme.blend
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(20.dp)

/** The pop-up closes by itself after this delay. */
private const val AUTO_DISMISS_MS = 90_000L

/**
 * "Ça commence": a programme the user asked to be reminded of is starting. A glass card in the bottom
 * corner with Regarder (its channel full screen) and Ignorer; the focus goes to Regarder, Back ignores it,
 * and it closes by itself after 90 s. Nothing is composed while no reminder is due, so it takes neither
 * the focus nor the keys of the screen.
 *
 * The card is a small dialog window placed in the corner (no dim, touches outside go to the screen):
 * while it is shown its keys reach it directly, even over the full-screen player whose keys the view
 * model reads before Compose.
 */
@Composable
fun ReminderPopup(vm: MainViewModel) {
    val due by vm.reminders.due.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val reminder = due ?: return
    // The parental PIN or the exit question is answered first; the reminder waits.
    if (ui.pin != null || ui.exitDialog || ui.pip) return
    val settings by vm.settings.collectAsStateWithLifecycle()
    LaunchedEffect(reminder.id) {
        delay(AUTO_DISMISS_MS)
        if (vm.reminders.due.value?.id == reminder.id) vm.reminders.dismissDue()
    }
    Dialog(
        onDismissRequest = { vm.reminders.dismissDue() },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        CornerWindow()
        ReminderCard(
            reminder = reminder,
            clock24h = settings.clock24h,
            onWatch = { vm.watchReminder(reminder) },
            onIgnore = { vm.reminders.dismissDue() },
        )
    }
}

/** Moves the dialog window to the bottom corner (end side), without dimming nor blocking the screen. */
@Composable
private fun CornerWindow() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.setGravity(Gravity.BOTTOM or Gravity.END)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        // Same immersive mode as the activity: the system bars stay hidden while the card is shown.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun ReminderCard(reminder: Reminder, clock24h: Boolean, onWatch: () -> Unit, onIgnore: () -> Unit) {
    val watchFocus = remember { FocusRequester() }
    val timeFormat = remember(clock24h) { SimpleDateFormat(if (clock24h) "HH:mm" else "h:mm a", Locale.getDefault()) }
    // Time left before the card closes by itself, drawn as a thin bar (only redrawn, never recomposed).
    var left by remember(reminder.id) { mutableFloatStateOf(1f) }
    LaunchedEffect(reminder.id) {
        runCatching { watchFocus.requestFocus() }
        val start = System.nanoTime()
        while (left > 0f) {
            delay(500)
            left = (1f - (System.nanoTime() - start) / 1_000_000f / AUTO_DISMISS_MS).coerceAtLeast(0f)
        }
    }
    val warmInk = blend(FluideColors.Warm, Color.White, 0.35f)
    Box(Modifier.padding(start = 16.dp, top = 16.dp, end = 28.dp, bottom = 28.dp)) {
        Column(
            Modifier
                .widthIn(min = 280.dp, max = 400.dp)
                .clip(CardShape)
                .background(FluideColors.Panel, CardShape)
                .fluideGlass(CardShape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .background(FluideColors.Warm.copy(alpha = 0.18f), CircleShape)
                        .border(1.dp, FluideColors.Warm.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(Glyph.ALARM, warmInk, size = 22.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.tvguide_reminder_starting),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = warmInk,
                        letterSpacing = 0.4.sp,
                        maxLines = 1,
                    )
                    Text(
                        reminder.channelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = FluideColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                reminder.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${timeFormat.format(Date(reminder.startSec * 1_000))} – ${timeFormat.format(Date(reminder.stopSec * 1_000))}" +
                    "  ·  " + formatDuration(reminder.stopSec - reminder.startSec),
                style = MaterialTheme.typography.bodyMedium,
                color = FluideColors.Soft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvButton(
                    stringResource(R.string.tvguide_reminder_watch),
                    onClick = onWatch,
                    glyph = Glyph.PLAY,
                    primary = true,
                    focusRequester = watchFocus,
                )
                TvButton(stringResource(R.string.tvguide_reminder_ignore), onClick = onIgnore)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(color = Color.White.copy(alpha = 0.1f), cornerRadius = radius)
                        val width = size.width * left
                        if (width > 0f) {
                            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                            drawRoundRect(brush = FluideGradient, topLeft = Offset(x, 0f), size = Size(width, size.height), cornerRadius = radius)
                        }
                    },
            )
        }
    }
}

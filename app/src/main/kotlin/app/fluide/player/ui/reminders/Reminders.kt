package app.fluide.player.ui.reminders

import android.content.Context
import app.fluide.player.data.Programme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Reminder(
    val id: Long,
    val sourceId: Long,
    val channelName: String,
    val title: String,
    val startSec: Long,
    val stopSec: Long,
)

/**
 * Programme reminders: the app tells the user when a programme they marked starts.
 * (Contract: implemented by the reminders work.)
 */
class Reminders(private val context: Context, private val scope: CoroutineScope) {
    private val list = MutableStateFlow<List<Reminder>>(emptyList())
    val all: StateFlow<List<Reminder>> = list.asStateFlow()

    private val dueState = MutableStateFlow<Reminder?>(null)

    /** The reminder whose programme is starting, shown as a pop-up until handled. */
    val due: StateFlow<Reminder?> = dueState.asStateFlow()

    /** False when this programme already has a reminder or has already started. */
    fun add(sourceId: Long, channelName: String, programme: Programme): Boolean = false

    fun remove(id: Long) = Unit

    fun isSet(sourceId: Long, channelName: String, startSec: Long): Boolean = false

    fun dismissDue() {
        dueState.value = null
    }
}

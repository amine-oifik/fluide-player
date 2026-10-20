package app.fluide.player.ui.reminders

import android.content.Context
import android.content.SharedPreferences
import app.fluide.player.data.Programme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
 *
 * Reminders are kept in the "reminders" preferences (a small JSON array) so they survive restarts.
 * Channels are identified by name, like favorites, so a reminder survives a playlist refresh. A ticker
 * checks every 15 s: the first programme starting within a minute becomes [due] (one at a time) and leaves
 * the list; programmes over, or started long ago while the app was closed, are dropped.
 */
class Reminders(private val context: Context, private val scope: CoroutineScope) {
    private val list = MutableStateFlow<List<Reminder>>(emptyList())
    val all: StateFlow<List<Reminder>> = list.asStateFlow()

    private val dueState = MutableStateFlow<Reminder?>(null)

    /** The reminder whose programme is starting, shown as a pop-up until handled. */
    val due: StateFlow<Reminder?> = dueState.asStateFlow()

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** False until the stored reminders are read: saving earlier would overwrite them. */
    private var loaded = false
    private var lastId = 0L

    init {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { runCatching { read() }.getOrDefault(emptyList()) }
            val nowSec = nowSec()
            // Reminders added while the file was being read are kept too.
            list.update { added -> (stored + added).distinctBy { it.id }.filter { alive(it, nowSec) }.sortedBy { it.startSec } }
            lastId = maxOf(lastId, list.value.maxOfOrNull { it.id } ?: 0L)
            loaded = true
            save()
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
    }

    /** False when this programme already has a reminder or has already started. */
    fun add(sourceId: Long, channelName: String, programme: Programme): Boolean {
        if (programme.startSec <= nowSec()) return false
        if (isSet(sourceId, channelName, programme.startSec)) return false
        if (list.value.size >= MAX_REMINDERS) return false
        val reminder = Reminder(
            id = nextId(),
            sourceId = sourceId,
            channelName = channelName,
            title = programme.title,
            startSec = programme.startSec,
            stopSec = maxOf(programme.stopSec, programme.startSec + 60),
        )
        list.update { current -> (current + reminder).sortedBy { it.startSec } }
        save()
        // A programme starting within the minute: told at once rather than at the next tick.
        if (reminder.startSec - nowSec() <= LEAD_SEC) tick()
        return true
    }

    fun remove(id: Long) {
        list.update { current -> current.filterNot { it.id == id } }
        if (dueState.value?.id == id) dueState.value = null
        save()
    }

    fun isSet(sourceId: Long, channelName: String, startSec: Long): Boolean =
        list.value.any { it.sourceId == sourceId && it.startSec == startSec && it.channelName == channelName }

    fun dismissDue() {
        dueState.value = null
    }

    /** Hands out the next programme starting (if no pop-up is shown) and drops the programmes missed. */
    private fun tick() {
        val nowSec = nowSec()
        // Nothing left to watch: the pop-up of a programme already over closes by itself.
        if ((dueState.value?.stopSec ?: Long.MAX_VALUE) <= nowSec) dueState.value = null
        val showing = dueState.value != null
        var starting: Reminder? = null
        val before = list.value
        list.update { current ->
            val kept = current.filter { alive(it, nowSec) }
            starting = if (showing) null else kept.filter { it.startSec - nowSec <= LEAD_SEC }.minByOrNull { it.startSec }
            val next = starting
            if (next == null) kept else kept.filterNot { it.id == next.id }
        }
        starting?.let { dueState.value = it }
        if (list.value != before) save()
    }

    /** Still worth telling: not over, and not started long ago (the app was closed at that time). */
    private fun alive(reminder: Reminder, nowSec: Long): Boolean =
        reminder.stopSec > nowSec && reminder.startSec + LATE_SEC > nowSec

    private fun nextId(): Long {
        val id = maxOf(System.currentTimeMillis(), lastId + 1)
        lastId = id
        return id
    }

    private fun save() {
        if (!loaded) return
        val array = JSONArray()
        for (r in list.value) {
            array.put(
                JSONObject()
                    .put(KEY_ID, r.id)
                    .put(KEY_SOURCE, r.sourceId)
                    .put(KEY_CHANNEL, r.channelName)
                    .put(KEY_TITLE, r.title)
                    .put(KEY_START, r.startSec)
                    .put(KEY_STOP, r.stopSec),
            )
        }
        val json = array.toString()
        // apply(): written in the background, the in-memory copy is updated at once.
        runCatching { prefs.edit().putString(KEY_LIST, json).apply() }
    }

    private fun read(): List<Reminder> {
        val json = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val array = JSONArray(json)
        val out = ArrayList<Reminder>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            // A damaged entry is skipped, the others are kept.
            runCatching {
                out += Reminder(
                    id = o.getLong(KEY_ID),
                    sourceId = o.getLong(KEY_SOURCE),
                    channelName = o.getString(KEY_CHANNEL),
                    title = o.optString(KEY_TITLE, ""),
                    startSec = o.getLong(KEY_START),
                    stopSec = o.getLong(KEY_STOP),
                )
            }
        }
        return out
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1_000

    private companion object {
        const val PREFS = "reminders"
        const val KEY_LIST = "list"
        const val KEY_ID = "id"
        const val KEY_SOURCE = "source"
        const val KEY_CHANNEL = "channel"
        const val KEY_TITLE = "title"
        const val KEY_START = "start"
        const val KEY_STOP = "stop"

        const val TICK_MS = 15_000L

        /** A programme starting within this delay is announced. */
        const val LEAD_SEC = 60L

        /** Started more than this ago (the app was closed): too late to announce it. */
        const val LATE_SEC = 10 * 60L

        const val MAX_REMINDERS = 500
    }
}

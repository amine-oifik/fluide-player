package app.fluide.player.ui.tvguide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.Programme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil

/**
 * One channel of the grid with its programmes overlapping [fromSec, toSec) (epoch s), the time range that
 * was loaded: outside it the grid shows "loading" cells instead of "no information".
 */
data class GuideRow(
    val channel: ChannelRow,
    val programmes: List<Programme>,
    val fromSec: Long = Long.MIN_VALUE,
    val toSec: Long = Long.MAX_VALUE,
)

data class TvGuideState(
    /** Start of the visible time window (epoch s). */
    val windowStartSec: Long = 0,
    val windowHours: Int = 3,
    /** Not filled: rows are served page by page by [TvGuideController.rowAt] (any list size stays fast). */
    val rows: List<GuideRow> = emptyList(),
    val totalChannels: Int = 0,
    val loading: Boolean = false,
    /** Changes whenever the channel list is reloaded, so the screen can scroll to the focused row again. */
    val version: Int = 0,
)

/**
 * One cell of a row of the grid, [startSec, endSec) being its part inside the window: a programme, a gap
 * of the guide ([programme] null) or a part not loaded yet ([loading]).
 */
data class GuideCell(val startSec: Long, val endSec: Long, val programme: Programme?, val loading: Boolean = false)

/**
 * Full TV guide: channels down, time across.
 *
 * The channel list is only a LongArray of ids; rows (channel + programmes of the window, with a margin
 * of [MARGIN_SEC] on each side) are loaded by pages of [PAGE] channels when the grid asks for them, like
 * ui/RowPager.kt, so 100 000 channels stay fast. Pages are Compose state: a row appears as soon as its page
 * arrives. When the window moves, the rows already loaded stay shown until their new page arrives, and the
 * focus is a (row, time) pair rather than a composable: it never jumps while pages load.
 */
class TvGuideController(private val channels: ChannelRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(TvGuideState())
    val state: StateFlow<TvGuideState> = mutable.asStateFlow()

    private class Page(val fromSec: Long, val toSec: Long, val rows: Map<Long, GuideRow>)

    private var ids = LongArray(0)
    private var sourceId = 0L
    private var generation = 0
    private val pages = mutableStateMapOf<Int, Page>()
    private val loading = HashSet<Int>()
    private var loadJob: Job? = null

    /** Index (in the channel list) of the row holding the focus. */
    var focusedRow by mutableIntStateOf(0)
        private set

    /**
     * Time of the focus: the cell of [focusedRow] containing it is focused. Moving up or down keeps it, so
     * the focus stays at the same time of the day from one channel to the next.
     */
    var anchorSec by mutableLongStateOf(0L)
        private set

    /** Channel focused, to find it again when the list is reloaded. */
    private var focusedChannelId = 0L

    val windowStartSec: Long get() = mutable.value.windowStartSec
    val windowEndSec: Long get() = mutable.value.windowStartSec + mutable.value.windowHours * HOUR

    /** Number of channels of the grid. */
    val size: Int get() = ids.size

    /** Loads the guide of [sourceId] around now; [excluded] categories are left out. */
    fun load(sourceId: Long, excluded: Set<String>) {
        if (sourceId != this.sourceId) focusedChannelId = 0L
        this.sourceId = sourceId
        loadJob?.cancel()
        generation++
        ids = LongArray(0)
        pages.clear()
        loading.clear()
        val nowSec = nowSec()
        anchorSec = nowSec
        mutable.update {
            it.copy(windowStartSec = halfHourFloor(nowSec), totalChannels = 0, loading = true, version = it.version + 1)
        }
        val requested = generation
        loadJob = scope.launch {
            val newIds = withContext(Dispatchers.IO) {
                runCatching { channels.channelIds(sourceId, ChannelFilter.All, excluded) }.getOrDefault(LongArray(0))
            }
            if (requested != generation) return@launch
            ids = newIds
            val index = if (focusedChannelId != 0L) newIds.indexOf(focusedChannelId) else -1
            focusedRow = if (index >= 0) index else 0
            focusedChannelId = newIds.getOrNull(focusedRow) ?: 0L
            mutable.update { it.copy(totalChannels = newIds.size, loading = false, version = it.version + 1) }
        }
    }

    /** Moves the time window by [hours] (negative: earlier); the focus moves with it. */
    fun shift(hours: Int) {
        val before = windowStartSec
        if (!moveWindow(hours)) return
        anchorSec = (anchorSec + (windowStartSec - before)).coerceIn(windowStartSec, windowEndSec - 1)
    }

    /** Back to the current time: the window starts at the last half hour, the focus on the programme on air. */
    fun showNow() {
        val nowSec = nowSec()
        mutable.update { it.copy(windowStartSec = halfHourFloor(nowSec)) }
        anchorSec = nowSec
    }

    /** Reloads the visible rows (guide imported again, EPG offset changed). */
    fun invalidate() {
        generation++
        pages.clear()
        loading.clear()
    }

    fun idAt(index: Int): Long? = ids.getOrNull(index)

    /**
     * Row at [index], or null while its page is loading (the load is requested). A row loaded for an
     * earlier window is returned while the new one loads.
     */
    fun rowAt(index: Int): GuideRow? {
        val id = ids.getOrNull(index) ?: return null
        val page = index / PAGE
        val data = pages[page]
        if (data == null || data.fromSec > windowStartSec || data.toSec < windowEndSec) request(page)
        return data?.rows?.get(id)
    }

    /** Cells of the focused row and the index of the focused one (-1 when the list is empty). */
    fun focusedCells(): Pair<List<GuideCell>, Int> {
        if (ids.isEmpty()) return emptyList<GuideCell>() to -1
        val cells = guideCells(rowAt(focusedRow), windowStartSec, windowEndSec)
        return cells to cells.indexAtTime(anchorSec)
    }

    /** Touch: focuses the cell of [row] starting at [timeSec]. */
    fun select(row: Int, timeSec: Long) {
        if (ids.isEmpty()) return
        focusedRow = row.coerceIn(0, ids.size - 1)
        focusedChannelId = ids[focusedRow]
        anchorSec = timeSec.coerceIn(windowStartSec, windowEndSec - 1)
    }

    /** Up / down by [delta] channels. False when the focus cannot move (top or bottom of the list). */
    fun moveRow(delta: Int): Boolean {
        if (ids.isEmpty()) return false
        val target = (focusedRow + delta).coerceIn(0, ids.size - 1)
        if (target == focusedRow) return false
        focusedRow = target
        focusedChannelId = ids[target]
        return true
    }

    /** Next programme of the focused row; past the end of the window, the window moves forward. */
    fun moveLater() {
        val (cells, index) = focusedCells()
        if (index < 0) return
        if (index < cells.lastIndex) {
            anchorSec = cells[index + 1].startSec
            return
        }
        // Last cell: show what comes next, with at least half an hour of it.
        val cell = cells[index]
        val target = maxOf(cell.programme?.stopSec ?: cell.endSec, cell.endSec)
        val hours = maxOf(1, ceil((target + HALF_HOUR - windowEndSec) / HOUR.toDouble()).toInt())
        if (moveWindow(hours)) focusTime(target)
    }

    /** Previous programme of the focused row; before the start of the window, the window moves back. */
    fun moveEarlier() {
        val (cells, index) = focusedCells()
        if (index < 0) return
        if (index > 0) {
            anchorSec = cells[index - 1].startSec
            return
        }
        // First cell: show what came before it.
        val cell = cells[index]
        val target = minOf(cell.programme?.startSec ?: cell.startSec, cell.startSec) - 1
        val hours = maxOf(1, ceil((windowStartSec - target) / HOUR.toDouble()).toInt())
        if (moveWindow(-hours)) focusTime(target)
    }

    /** Focuses the cell of the focused row containing [timeSec] (from its start, for the moves up / down). */
    private fun focusTime(timeSec: Long) {
        val time = timeSec.coerceIn(windowStartSec, windowEndSec - 1)
        val cells = guideCells(rowAt(focusedRow), windowStartSec, windowEndSec)
        anchorSec = cells.getOrNull(cells.indexAtTime(time))?.startSec ?: time
    }

    /** False when the window is already at its limit (7 days before or after now). */
    private fun moveWindow(hours: Int): Boolean {
        val base = halfHourFloor(nowSec())
        val length = mutable.value.windowHours * HOUR
        val current = windowStartSec
        val next = (current + hours * HOUR).coerceIn(base - LIMIT_SEC, base + LIMIT_SEC - length)
        if (next == current) return false
        mutable.update { it.copy(windowStartSec = next) }
        return true
    }

    private fun request(page: Int) {
        if (!loading.add(page)) return
        val requested = generation
        val first = page * PAGE
        if (first >= ids.size) {
            loading.remove(page)
            return
        }
        val slice = ids.copyOfRange(first, minOf(ids.size, first + PAGE))
        val fromSec = windowStartSec - MARGIN_SEC
        val toSec = windowEndSec + MARGIN_SEC
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching {
                    channels.rows(slice, nowSec()).map { row ->
                        GuideRow(row, channels.programmes(row.epgKey, fromSec, toSec), fromSec, toSec)
                    }
                }.getOrDefault(emptyList())
            }
            if (requested != generation) return@launch
            loading.remove(page)
            pages[page] = Page(fromSec, toSec, rows.associateBy { it.channel.id })
            if (pages.size > MAX_PAGES) {
                pages.keys.filter { abs(it - page) > MAX_PAGES / 2 }.forEach { pages.remove(it) }
            }
        }
    }

    private fun nowSec(): Long = System.currentTimeMillis() / 1_000

    companion object {
        private const val PAGE = 24
        private const val MAX_PAGES = 24
        const val HOUR = 3_600L
        const val HALF_HOUR = 1_800L

        /** Programmes loaded on each side of the window, so that moving it a little needs no new load. */
        private const val MARGIN_SEC = 3 * HOUR

        /** The window stays within a week before and after now. */
        private const val LIMIT_SEC = 7 * 24 * HOUR

        /** Last half hour (local time) at or before [sec]. */
        fun halfHourFloor(sec: Long): Long {
            val offset = TimeZone.getDefault().getOffset(sec * 1_000) / 1_000L
            return sec - Math.floorMod(sec + offset, HALF_HOUR)
        }
    }
}

/** Holes of the guide shorter than this (rounded programme times) get no cell of their own. */
private const val SMALL_GAP_SEC = 5 * 60L

/**
 * Cells of [row] covering [startSec, endSec) without holes: its programmes (cut to the window), gaps "no
 * information", and "loading" parts where the row was not loaded yet (the whole window when [row] is null).
 */
fun guideCells(row: GuideRow?, startSec: Long, endSec: Long): List<GuideCell> {
    if (endSec <= startSec) return emptyList()
    if (row == null) return listOf(GuideCell(startSec, endSec, null, loading = true))
    val loadedFrom = row.fromSec.coerceIn(startSec, endSec)
    val loadedTo = row.toSec.coerceIn(loadedFrom, endSec)
    val out = ArrayList<GuideCell>()
    var cursor = startSec
    for (p in row.programmes) {
        if (p.stopSec <= p.startSec || p.stopSec <= cursor || p.startSec >= endSec) continue
        var start = maxOf(p.startSec, cursor)
        if (start > cursor && start - cursor < SMALL_GAP_SEC && cursor >= loadedFrom && start <= loadedTo) {
            val last = out.lastOrNull()
            if (last?.programme != null && last.endSec == cursor) out[out.lastIndex] = last.copy(endSec = start) else start = cursor
            cursor = start
        }
        out.addGap(cursor, start, loadedFrom, loadedTo)
        val end = minOf(p.stopSec, endSec)
        out += GuideCell(start, end, p)
        cursor = end
        if (cursor >= endSec) break
    }
    out.addGap(cursor, endSec, loadedFrom, loadedTo)
    return out
}

private fun MutableList<GuideCell>.addGap(from: Long, to: Long, loadedFrom: Long, loadedTo: Long) {
    var cursor = from
    if (cursor < to && cursor < loadedFrom) {
        val end = minOf(to, loadedFrom)
        add(GuideCell(cursor, end, null, loading = true))
        cursor = end
    }
    if (cursor < to && cursor < loadedTo) {
        val end = minOf(to, loadedTo)
        val last = lastOrNull()
        if (end - cursor < SMALL_GAP_SEC && last?.programme != null && last.endSec == cursor) {
            this[lastIndex] = last.copy(endSec = end)
        } else {
            add(GuideCell(cursor, end, null))
        }
        cursor = end
    }
    if (cursor < to) add(GuideCell(cursor, to, null, loading = true))
}

/** Index of the cell containing [timeSec] (the first or the last one when it is outside). */
fun List<GuideCell>.indexAtTime(timeSec: Long): Int {
    if (isEmpty()) return -1
    if (timeSec < first().startSec) return 0
    val index = indexOfFirst { timeSec >= it.startSec && timeSec < it.endSec }
    return if (index >= 0) index else lastIndex
}

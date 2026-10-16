package app.fluide.player.ui

import androidx.compose.runtime.mutableStateMapOf
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.ChannelRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Virtualised access to a channel list of any size: the list itself is only a LongArray of ids
 * (800 KB for 100 000 channels), rows are loaded by pages of [PAGE] when the LazyColumn asks for them,
 * and at most [MAX_PAGES] pages stay in memory. Pages are Compose state: a row appears as soon as its
 * page arrives.
 */
class RowPager(private val repository: ChannelRepository, private val scope: CoroutineScope) {

    private var ids = LongArray(0)
    private var generation = 0
    private val pages = mutableStateMapOf<Int, Map<Long, ChannelRow>>()
    private val loading = HashSet<Int>()

    val size: Int get() = ids.size

    fun setIds(newIds: LongArray) {
        ids = newIds
        invalidate()
    }

    /** Reloads visible rows (favorite toggled, guide imported). */
    fun invalidate() {
        generation++
        pages.clear()
        loading.clear()
    }

    fun idAt(index: Int): Long? = ids.getOrNull(index)

    /** Row at [index], or null while its page is loading (the load is requested). */
    fun rowAt(index: Int): ChannelRow? {
        val id = ids.getOrNull(index) ?: return null
        val page = index / PAGE
        val rows = pages[page]
        if (rows == null) {
            request(page)
            return null
        }
        return rows[id]
    }

    private fun request(page: Int) {
        if (!loading.add(page)) return
        val requestGeneration = generation
        val from = page * PAGE
        val slice = ids.copyOfRange(from, minOf(ids.size, from + PAGE))
        scope.launch {
            val rows = withContext(Dispatchers.IO) {
                runCatching { repository.rows(slice, System.currentTimeMillis() / 1_000) }.getOrDefault(emptyList())
            }
            if (requestGeneration != generation) return@launch
            pages[page] = rows.associateBy { it.id }
            loading.remove(page)
            if (pages.size > MAX_PAGES) {
                pages.keys.filter { abs(it - page) > MAX_PAGES / 2 }.forEach { pages.remove(it) }
            }
        }
    }

    private companion object {
        const val PAGE = 40
        const val MAX_PAGES = 30
    }
}

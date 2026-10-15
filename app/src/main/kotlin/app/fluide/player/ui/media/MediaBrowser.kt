package app.fluide.player.ui.media

import androidx.compose.runtime.mutableStateMapOf
import app.fluide.player.data.GroupInfo
import app.fluide.player.data.MediaFilter
import app.fluide.player.data.MediaItem
import app.fluide.player.data.MediaRepository
import app.fluide.player.data.MediaSort
import app.fluide.player.data.Section
import app.fluide.player.ui.BrowserState
import app.fluide.player.ui.MediaGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Movies or series screen: categories, filter, sort and search over the stored catalogue. The grid
 * only holds ids; tiles are loaded by pages of [MediaPager.PAGE] as the grid scrolls.
 */
class MediaBrowser(
    private val section: Section,
    private val repository: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow(BrowserState(section))
    val browser: StateFlow<BrowserState> = state.asStateFlow()
    val pager = MediaPager(section, repository, scope)

    private var sourceId = 0L
    private var excluded: Set<String> = emptySet()
    private var searchJob: Job? = null
    private var loadJob: Job? = null

    /** (Re)loads the categories of [source]. [hidden] are not listed, [locked] show a lock and stay out of the other lists. */
    fun load(source: Long, hidden: Set<String>, locked: Set<String>, keepSelection: Boolean = false) {
        sourceId = source
        excluded = hidden + locked
        val previous = state.value.selected
        loadJob?.cancel()
        loadJob = scope.launch {
            val groups = withContext(Dispatchers.IO) { repository.groups(source, section) }
            val total = groups.filter { it.name == null || it.name !in excluded }.sumOf { it.count }
            state.update { it.copy(groups = mediaGroups(groups, total, hidden, locked)) }
            val selection = if (keepSelection && previous !is MediaFilter.Search) previous else MediaFilter.All
            showList(selection)
        }
    }

    fun select(filter: MediaFilter) {
        searchJob?.cancel()
        scope.launch { showList(filter) }
    }

    fun setSort(sort: MediaSort) {
        state.update { it.copy(sort = sort) }
        val selected = state.value.selected
        scope.launch { showList(selected) }
    }

    fun search(query: String) {
        state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            scope.launch { showList(MediaFilter.All) }
            return
        }
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            showList(MediaFilter.Search(query))
        }
    }

    /** Favorites or progress changed: reload the visible tiles (and the list when it depends on them). */
    fun invalidate() {
        val selected = state.value.selected
        if (selected == MediaFilter.Favorites || selected == MediaFilter.ContinueWatching) {
            scope.launch { showList(selected) }
        } else {
            pager.invalidate()
        }
    }

    private suspend fun showList(filter: MediaFilter) {
        val source = sourceId
        val sort = state.value.sort
        val ids = withContext(Dispatchers.IO) { repository.ids(source, section, filter, sort, excluded) }
        pager.setIds(ids)
        state.update {
            it.copy(
                selected = filter,
                query = if (filter is MediaFilter.Search) it.query else "",
                size = ids.size,
                version = it.version + 1,
            )
        }
    }

    private fun mediaGroups(groups: List<GroupInfo>, total: Int, hidden: Set<String>, locked: Set<String>): List<MediaGroup> = buildList {
        add(MediaGroup(MediaFilter.All, null, total))
        add(MediaGroup(MediaFilter.ContinueWatching, null, -1))
        add(MediaGroup(MediaFilter.Favorites, null, -1))
        add(MediaGroup(MediaFilter.RecentlyAdded, null, -1))
        for (group in groups) {
            val name = group.name
            if (name != null && name in hidden) continue
            add(MediaGroup(MediaFilter.Group(name), name, group.count, locked = name != null && name in locked))
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/** Virtualised access to a movies or series list (see RowPager): ids in memory, tiles by pages. */
class MediaPager(private val section: Section, private val repository: MediaRepository, private val scope: CoroutineScope) {

    private var ids = LongArray(0)
    private var generation = 0
    private val pages = mutableStateMapOf<Int, Map<Long, MediaItem>>()
    private val loading = HashSet<Int>()

    val size: Int get() = ids.size

    fun setIds(newIds: LongArray) {
        ids = newIds
        invalidate()
    }

    fun invalidate() {
        generation++
        pages.clear()
        loading.clear()
    }

    fun idAt(index: Int): Long? = ids.getOrNull(index)

    fun itemAt(index: Int): MediaItem? {
        val id = ids.getOrNull(index) ?: return null
        val page = index / PAGE
        val items = pages[page]
        if (items == null) {
            request(page)
            return null
        }
        return items[id]
    }

    private fun request(page: Int) {
        if (!loading.add(page)) return
        val requestGeneration = generation
        val from = page * PAGE
        val slice = ids.copyOfRange(from, minOf(ids.size, from + PAGE))
        scope.launch {
            val items = withContext(Dispatchers.IO) {
                runCatching { repository.rows(section, slice) }.getOrDefault(emptyList())
            }
            if (requestGeneration != generation) return@launch
            pages[page] = items.associateBy { it.id }
            loading.remove(page)
            if (pages.size > MAX_PAGES) {
                pages.keys.filter { abs(it - page) > MAX_PAGES / 2 }.forEach { pages.remove(it) }
            }
        }
    }

    companion object {
        const val PAGE = 36
        private const val MAX_PAGES = 20
    }
}

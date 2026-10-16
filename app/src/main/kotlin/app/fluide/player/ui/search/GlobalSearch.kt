package app.fluide.player.ui.search

import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.MediaFilter
import app.fluide.player.data.MediaItem
import app.fluide.player.data.MediaRepository
import app.fluide.player.data.MediaSort
import app.fluide.player.data.Section
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

data class SearchState(
    val query: String = "",
    val searching: Boolean = false,
    val channels: List<ChannelRow> = emptyList(),
    val movies: List<MediaItem> = emptyList(),
    val series: List<MediaItem> = emptyList(),
) {
    val empty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/**
 * One search field for the whole catalogue: channels (full-text index), movies and series (name
 * words), each limited to [LIMIT] results. Typing is debounced; hidden and locked categories are left out.
 */
class GlobalSearch(
    private val channels: ChannelRepository,
    private val media: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutable.asStateFlow()
    private var job: Job? = null

    fun search(query: String, sourceId: Long, excludedLive: Set<String>, excludedMovies: Set<String>, excludedSeries: Set<String>) {
        job?.cancel()
        mutable.update { it.copy(query = query) }
        if (query.isBlank() || sourceId == 0L) {
            mutable.value = SearchState(query = query)
            return
        }
        mutable.update { it.copy(searching = true) }
        job = scope.launch {
            delay(DEBOUNCE_MS)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val nowSec = System.currentTimeMillis() / 1_000
                    val channelIds = channels.channelIds(sourceId, ChannelFilter.Search(query), excludedLive)
                    val movieIds = media.ids(sourceId, Section.MOVIES, MediaFilter.Search(query), MediaSort.DEFAULT, excludedMovies)
                    val seriesIds = media.ids(sourceId, Section.SERIES, MediaFilter.Search(query), MediaSort.DEFAULT, excludedSeries)
                    SearchState(
                        query = query,
                        channels = channels.rows(channelIds.copyOf(minOf(channelIds.size, LIMIT)), nowSec),
                        movies = media.rows(Section.MOVIES, movieIds.copyOf(minOf(movieIds.size, LIMIT))),
                        series = media.rows(Section.SERIES, seriesIds.copyOf(minOf(seriesIds.size, LIMIT))),
                    )
                }.getOrDefault(SearchState(query = query))
            }
            mutable.value = result
        }
    }

    fun clear() {
        job?.cancel()
        mutable.value = SearchState()
    }

    private companion object {
        const val LIMIT = 40
        const val DEBOUNCE_MS = 300L
    }
}

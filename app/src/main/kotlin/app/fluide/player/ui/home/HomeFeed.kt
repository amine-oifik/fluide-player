package app.fluide.player.ui.home

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Rows of the home screen, each at most [HomeFeed.ROW_SIZE] items. Empty rows are not shown. */
data class HomeFeedState(
    val continueMovies: List<MediaItem> = emptyList(),
    val continueSeries: List<MediaItem> = emptyList(),
    val recentMovies: List<MediaItem> = emptyList(),
    val recentSeries: List<MediaItem> = emptyList(),
    val favoriteChannels: List<ChannelRow> = emptyList(),
    val recentChannels: List<ChannelRow> = emptyList(),
    /** Featured item of the hero banner: the movie being watched, else the latest movie added. */
    val hero: MediaItem? = null,
    val loaded: Boolean = false,
)

/**
 * Personalised rows of the home screen ("continue watching", "recently added", favorite and recent
 * channels), read from the catalogue of the active playlist. Hidden and locked categories are left out.
 */
class HomeFeed(
    private val channels: ChannelRepository,
    private val media: MediaRepository,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(HomeFeedState())
    val state: StateFlow<HomeFeedState> = mutable.asStateFlow()
    private var job: Job? = null

    fun load(sourceId: Long, excludedLive: Set<String>, excludedMovies: Set<String>, excludedSeries: Set<String>) {
        job?.cancel()
        if (sourceId == 0L) {
            mutable.value = HomeFeedState(loaded = true)
            return
        }
        job = scope.launch {
            mutable.value = withContext(Dispatchers.IO) {
                runCatching { read(sourceId, excludedLive, excludedMovies, excludedSeries) }.getOrDefault(HomeFeedState(loaded = true))
            }
        }
    }

    private fun read(sourceId: Long, excludedLive: Set<String>, excludedMovies: Set<String>, excludedSeries: Set<String>): HomeFeedState {
        fun mediaRow(section: Section, filter: MediaFilter, excluded: Set<String>): List<MediaItem> {
            val ids = media.ids(sourceId, section, filter, MediaSort.DEFAULT, excluded)
            return media.rows(section, ids.copyOf(minOf(ids.size, ROW_SIZE)))
        }
        fun channelRow(filter: ChannelFilter): List<ChannelRow> {
            val ids = channels.channelIds(sourceId, filter, excludedLive)
            return channels.rows(ids.copyOf(minOf(ids.size, ROW_SIZE)), System.currentTimeMillis() / 1_000)
        }
        val continueMovies = mediaRow(Section.MOVIES, MediaFilter.ContinueWatching, excludedMovies)
        val recentMovies = mediaRow(Section.MOVIES, MediaFilter.RecentlyAdded, excludedMovies)
        return HomeFeedState(
            continueMovies = continueMovies,
            continueSeries = mediaRow(Section.SERIES, MediaFilter.ContinueWatching, excludedSeries),
            recentMovies = recentMovies,
            recentSeries = mediaRow(Section.SERIES, MediaFilter.RecentlyAdded, excludedSeries),
            favoriteChannels = channelRow(ChannelFilter.Favorites),
            recentChannels = channelRow(ChannelFilter.Recent),
            hero = continueMovies.firstOrNull { it.poster != null } ?: recentMovies.firstOrNull { it.poster != null },
            loaded = true,
        )
    }

    companion object {
        const val ROW_SIZE = 20
    }
}

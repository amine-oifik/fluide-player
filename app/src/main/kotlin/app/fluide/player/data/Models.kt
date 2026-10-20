package app.fluide.player.data

import app.fluide.core.m3u.StreamKind

enum class SourceType { URL, FILE, XTREAM }

data class SourceInfo(
    val id: Long,
    val name: String,
    val type: SourceType,
    val channelCount: Int,
    val programmeCount: Int,
    val updatedAt: Long,
    val epgUpdatedAt: Long,
    val maxConnections: Int,
    val userAgent: String?,
    val lastError: String?,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    /** Xtream accounts: expiry (epoch seconds), null when unlimited or unknown. */
    val expiresAtSec: Long? = null,
    val accountStatus: String? = null,
    val activeConnections: Int? = null,
    /** Xtream live container: "ts" or "m3u8". */
    val streamFormat: String = "ts",
    val serverTimezone: String? = null,
    /** Shift applied to the guide times of this playlist, in minutes (-720..720; 0 = none). */
    val epgOffsetMinutes: Int = 0,
)

/** One line of the channel list. Built page by page, never for a whole list. */
data class ChannelRow(
    val id: Long,
    val number: Int?,
    val name: String,
    val group: String?,
    val logo: String?,
    val epgKey: String?,
    val nowTitle: String?,
    val nowStartSec: Long,
    val nowStopSec: Long,
    val favorite: Boolean,
    /** Days of recordings available (catch-up), 0 without catch-up. */
    val catchupDays: Int = 0,
)

/** Everything the player needs for one channel or video, URLs decrypted. Kept in memory only while playing. */
data class PlayableChannel(
    val id: Long,
    val sourceId: Long,
    val number: Int?,
    val name: String,
    val nameNorm: String,
    val group: String?,
    val logo: String?,
    val epgKey: String?,
    val url: String,
    val kind: StreamKind,
    val userAgent: String?,
    val referrer: String?,
    val drmScheme: String?,
    val drmLicenseUrl: String?,
    /** How many simultaneous connections the provider allows (preloading needs more than one). */
    val maxConnections: Int,
    /** Movie, episode or recording: seekable, no live-only recovery (backup channels, live edge). */
    val vod: Boolean = false,
    val catchupMode: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
)

data class Programme(
    val startSec: Long,
    val stopSec: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
    val category: String?,
)

/** `name` null: channels without group-title. */
data class GroupInfo(val name: String?, val count: Int)

sealed interface ChannelFilter {
    data object All : ChannelFilter
    data object Favorites : ChannelFilter
    data object Recent : ChannelFilter
    /** Channels with recordings (catch-up). */
    data object Catchup : ChannelFilter
    data class Group(val name: String?) : ChannelFilter
    data class Search(val query: String) : ChannelFilter
}

/** Sections of the catalogue, as stored in `category_rule.section`. */
enum class Section(val code: Int) { LIVE(0), MOVIES(1), SERIES(2) }

/** Per-category setting: hidden from the lists, or locked behind the parental PIN. */
enum class CategoryMode(val code: Int) { HIDDEN(1), LOCKED(2) }

/** Movies and series lists. */
sealed interface MediaFilter {
    data object All : MediaFilter
    data object Favorites : MediaFilter
    /** Movies or series started and not finished, most recent first. */
    data object ContinueWatching : MediaFilter
    data object RecentlyAdded : MediaFilter
    data class Group(val name: String?) : MediaFilter
    data class Search(val query: String) : MediaFilter
}

enum class MediaSort { DEFAULT, ADDED, NAME_ASC, NAME_DESC, RATING, YEAR }

/** One tile of the movies or series grid. */
data class MediaItem(
    val id: Long,
    val key: String,
    val name: String,
    val poster: String?,
    val rating: Double?,
    val year: Int?,
    val group: String?,
    val favorite: Boolean,
    /** 0..1 watched fraction (movies), -1 when never started. */
    val progress: Float,
)

data class Episode(
    /** Xtream episode id, or the row id for M3U playlists. */
    val key: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String?,
    val durationSec: Long?,
    val image: String?,
    val extension: String?,
    /** M3U playlists: decrypted URL. Xtream: built from the account when played. */
    val url: String?,
    val progress: Float,
    val positionMs: Long,
)

data class Season(val number: Int, val name: String?, val episodes: List<Episode>)

/** Details page of a movie or a series. Every field but [name] may be missing. */
data class MediaDetailsUi(
    val sourceId: Long,
    val section: Section,
    val id: Long,
    val key: String,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val year: Int?,
    val durationSec: Long?,
    val rating: Double?,
    val trailer: String?,
    val country: String?,
    val group: String?,
    val favorite: Boolean,
    /** Movies: resume point, 0 when never started. */
    val resumeMs: Long,
    val durationMs: Long,
    val seasons: List<Season>,
    /** Series: the episode to continue with (last watched, or the next one when it was finished). */
    val continueEpisode: Episode?,
    val extension: String?,
    val loading: Boolean,
)

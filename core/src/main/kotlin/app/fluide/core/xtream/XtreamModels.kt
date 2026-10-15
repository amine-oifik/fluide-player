package app.fluide.core.xtream

/** `player_api.php` without action: the account and the server. */
data class XtreamAccount(
    val authenticated: Boolean,
    val status: String?,
    val message: String?,
    /** Epoch seconds, null for an account without expiry. */
    val expiresAtSec: Long?,
    val isTrial: Boolean,
    val activeConnections: Int?,
    val maxConnections: Int?,
    val allowedFormats: List<String>,
    val serverTimezone: String?,
    val serverTimeSec: Long?,
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamLiveStream(
    val num: Int?,
    val name: String,
    val streamId: Long,
    val icon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val hasArchive: Boolean,
    val archiveDays: Int,
)

data class XtreamVodStream(
    val num: Int?,
    val name: String,
    val streamId: Long,
    val icon: String?,
    val rating: Double?,
    val addedSec: Long?,
    val categoryId: String?,
    val extension: String?,
    val year: Int?,
)

data class XtreamSeries(
    val num: Int?,
    val name: String,
    val seriesId: Long,
    val cover: String?,
    val plot: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Double?,
    val lastModifiedSec: Long?,
    val categoryId: String?,
    val year: Int?,
)

/** Details shown on a movie or series page. Every field may be missing on real panels. */
data class MediaDetails(
    val title: String?,
    val originalTitle: String?,
    val plot: String?,
    val genre: String?,
    val director: String?,
    val cast: String?,
    val releaseDate: String?,
    val durationSec: Long?,
    val rating: Double?,
    val poster: String?,
    val backdrop: String?,
    val trailer: String?,
    val country: String?,
)

data class XtreamVodInfo(val details: MediaDetails, val extension: String?)

data class XtreamEpisode(
    val id: Long,
    val season: Int,
    val episode: Int,
    val title: String?,
    val extension: String?,
    val plot: String?,
    val durationSec: Long?,
    val image: String?,
    val rating: Double?,
)

data class XtreamSeason(val number: Int, val name: String?, val cover: String?, val episodeCount: Int?)

data class XtreamSeriesInfo(
    val details: MediaDetails,
    val seasons: List<XtreamSeason>,
    /** Sorted by season then episode. */
    val episodes: List<XtreamEpisode>,
)

data class XtreamProgramme(val startSec: Long, val stopSec: Long, val title: String, val description: String?)

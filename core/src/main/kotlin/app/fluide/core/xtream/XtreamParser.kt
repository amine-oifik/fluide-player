package app.fluide.core.xtream

import app.fluide.core.json.JsonReader
import app.fluide.core.json.JsonToken
import app.fluide.core.json.forEachElement
import app.fluide.core.json.forEachMember

/**
 * Readers for the answers of the Xtream Codes `player_api.php` interface (the de-facto standard of
 * IPTV panels). Lists are streamed item by item ([onItem]); only small answers are returned whole.
 * Field types vary between panels (numbers as strings, empty arrays for missing objects): every value
 * goes through the lenient [JsonReader] accessors.
 */
object XtreamParser {

    fun account(reader: JsonReader): XtreamAccount {
        var auth = false
        var status: String? = null
        var message: String? = null
        var exp: Long? = null
        var trial = false
        var active: Int? = null
        var max: Int? = null
        val formats = ArrayList<String>(3)
        var timezone: String? = null
        var serverTime: Long? = null
        reader.forEachMember { section ->
            when (section) {
                "user_info" -> reader.forEachMember { name ->
                    when (name) {
                        "auth" -> auth = truthy(reader.nextString())
                        "status" -> status = reader.nextString()
                        "message" -> message = reader.nextString()?.takeIf { it.isNotBlank() }
                        "exp_date" -> exp = reader.nextLongOrNull()?.takeIf { it > 0 }
                        "is_trial" -> trial = truthy(reader.nextString())
                        "active_cons" -> active = reader.nextIntOrNull()
                        "max_connections" -> max = reader.nextIntOrNull()
                        "allowed_output_formats" -> reader.forEachElement {
                            reader.nextString()?.let { formats += it.lowercase() }
                        }
                        else -> reader.skipValue()
                    }
                }
                "server_info" -> reader.forEachMember { name ->
                    when (name) {
                        "timezone" -> timezone = reader.nextString()?.takeIf { it.isNotBlank() }
                        "timestamp_now" -> serverTime = reader.nextLongOrNull()
                        else -> reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        return XtreamAccount(auth, status, message, exp, trial, active, max, formats, timezone, serverTime)
    }

    fun categories(reader: JsonReader): List<XtreamCategory> {
        val out = ArrayList<XtreamCategory>()
        reader.forEachElement {
            var id: String? = null
            var name: String? = null
            reader.forEachMember { field ->
                when (field) {
                    "category_id" -> id = reader.nextString()
                    "category_name" -> name = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            val categoryId = id
            if (categoryId != null) out += XtreamCategory(categoryId, name?.trim()?.ifEmpty { null } ?: categoryId)
        }
        return out
    }

    fun liveStreams(reader: JsonReader, onItem: (XtreamLiveStream) -> Unit) {
        reader.forEachElement {
            var num: Int? = null
            var name: String? = null
            var id: Long? = null
            var icon: String? = null
            var epg: String? = null
            var category: String? = null
            var archive = false
            var archiveDays = 0
            reader.forEachMember { field ->
                when (field) {
                    "num" -> num = reader.nextIntOrNull()
                    "name" -> name = reader.nextString()
                    "stream_id" -> id = reader.nextLongOrNull()
                    "stream_icon" -> icon = cleanUrl(reader.nextString())
                    "epg_channel_id" -> epg = reader.nextString()?.trim()?.ifEmpty { null }
                    "category_id" -> category = reader.nextString()
                    "tv_archive" -> archive = truthy(reader.nextString())
                    "tv_archive_duration" -> archiveDays = reader.nextIntOrNull() ?: 0
                    else -> reader.skipValue()
                }
            }
            val streamId = id
            val title = name?.trim()
            if (streamId != null && !title.isNullOrEmpty()) {
                onItem(XtreamLiveStream(num, title, streamId, icon, epg, category, archive && archiveDays > 0, archiveDays))
            }
        }
    }

    fun vodStreams(reader: JsonReader, onItem: (XtreamVodStream) -> Unit) {
        reader.forEachElement {
            var num: Int? = null
            var name: String? = null
            var id: Long? = null
            var icon: String? = null
            var rating: Double? = null
            var added: Long? = null
            var category: String? = null
            var ext: String? = null
            var year: Int? = null
            reader.forEachMember { field ->
                when (field) {
                    "num" -> num = reader.nextIntOrNull()
                    "name" -> name = reader.nextString()
                    "stream_id" -> id = reader.nextLongOrNull()
                    "stream_icon" -> icon = cleanUrl(reader.nextString())
                    "rating" -> rating = reader.nextDoubleOrNull()?.takeIf { it > 0 }
                    "added" -> added = reader.nextLongOrNull()
                    "category_id" -> category = reader.nextString()
                    "container_extension" -> ext = reader.nextString()?.trim()?.ifEmpty { null }
                    "year" -> year = reader.nextString()?.let(::yearOf)
                    else -> reader.skipValue()
                }
            }
            val streamId = id
            val title = name?.trim()
            if (streamId != null && !title.isNullOrEmpty()) {
                onItem(XtreamVodStream(num, title, streamId, icon, rating, added, category, ext, year ?: yearOf(title)))
            }
        }
    }

    fun series(reader: JsonReader, onItem: (XtreamSeries) -> Unit) {
        reader.forEachElement {
            var num: Int? = null
            var name: String? = null
            var id: Long? = null
            var cover: String? = null
            var plot: String? = null
            var genre: String? = null
            var release: String? = null
            var rating: Double? = null
            var modified: Long? = null
            var category: String? = null
            reader.forEachMember { field ->
                when (field) {
                    "num" -> num = reader.nextIntOrNull()
                    "name" -> name = reader.nextString()
                    "series_id" -> id = reader.nextLongOrNull()
                    "cover" -> cover = cleanUrl(reader.nextString())
                    "plot" -> plot = reader.nextString()?.trim()?.ifEmpty { null }
                    "genre" -> genre = reader.nextString()?.trim()?.ifEmpty { null }
                    "releaseDate", "release_date" -> release = reader.nextString()?.trim()?.ifEmpty { null } ?: release
                    "rating" -> rating = reader.nextDoubleOrNull()?.takeIf { it > 0 }
                    "last_modified" -> modified = reader.nextLongOrNull()
                    "category_id" -> category = reader.nextString()
                    else -> reader.skipValue()
                }
            }
            val seriesId = id
            val title = name?.trim()
            if (seriesId != null && !title.isNullOrEmpty()) {
                val year = release?.let(::yearOf) ?: yearOf(title)
                onItem(XtreamSeries(num, title, seriesId, cover, plot, genre, release, rating, modified, category, year))
            }
        }
    }

    fun vodInfo(reader: JsonReader): XtreamVodInfo {
        var details = EMPTY_DETAILS
        var ext: String? = null
        reader.forEachMember { section ->
            when (section) {
                "info" -> details = details(reader)
                "movie_data" -> reader.forEachMember { field ->
                    when (field) {
                        "container_extension" -> ext = reader.nextString()?.trim()?.ifEmpty { null }
                        "name" -> {
                            val name = reader.nextString()
                            if (details.title == null) details = details.copy(title = name)
                        }
                        else -> reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        return XtreamVodInfo(details, ext)
    }

    fun seriesInfo(reader: JsonReader): XtreamSeriesInfo {
        var details = EMPTY_DETAILS
        val seasons = ArrayList<XtreamSeason>()
        val episodes = ArrayList<XtreamEpisode>()
        reader.forEachMember { section ->
            when (section) {
                "info" -> details = details(reader)
                "seasons" -> reader.forEachElement {
                    var number: Int? = null
                    var name: String? = null
                    var cover: String? = null
                    var count: Int? = null
                    reader.forEachMember { field ->
                        when (field) {
                            "season_number" -> number = reader.nextIntOrNull()
                            "name" -> name = reader.nextString()
                            "cover", "cover_big" -> cover = cleanUrl(reader.nextString()) ?: cover
                            "episode_count" -> count = reader.nextIntOrNull()
                            else -> reader.skipValue()
                        }
                    }
                    number?.let { seasons += XtreamSeason(it, name, cover, count) }
                }
                // Either {"1": [...], "2": [...]} or [[...], [...]]: forEachElement reads both.
                "episodes" -> reader.forEachElement {
                    reader.forEachElement { episode(reader)?.let(episodes::add) }
                }
                else -> reader.skipValue()
            }
        }
        episodes.sortWith(compareBy({ it.season }, { it.episode }))
        // Panels often omit "seasons": derive them from the episodes.
        val known = seasons.mapTo(HashSet()) { it.number }
        for (season in episodes.map { it.season }.distinct()) {
            if (season !in known) seasons += XtreamSeason(season, null, null, null)
        }
        val withEpisodes = episodes.mapTo(HashSet()) { it.season }
        val shown = seasons.filter { it.number in withEpisodes }.sortedBy { it.number }
        return XtreamSeriesInfo(details, shown, episodes)
    }

    /**
     * `get_short_epg` / `get_simple_data_table`: `{"epg_listings": [...]}` with base64 titles and
     * descriptions, decoded by [decodeBase64] (platform decoder).
     */
    fun shortEpg(reader: JsonReader, decodeBase64: (String) -> String?): List<XtreamProgramme> {
        val out = ArrayList<XtreamProgramme>()
        reader.forEachMember { section ->
            if (section != "epg_listings") {
                reader.skipValue()
                return@forEachMember
            }
            reader.forEachElement {
                var start: Long? = null
                var stop: Long? = null
                var title: String? = null
                var description: String? = null
                reader.forEachMember { field ->
                    when (field) {
                        "start_timestamp" -> start = reader.nextLongOrNull()
                        "stop_timestamp", "end_timestamp" -> stop = reader.nextLongOrNull() ?: stop
                        "title" -> title = reader.nextString()?.let(decodeBase64)?.trim()?.ifEmpty { null }
                        "description" -> description = reader.nextString()?.let(decodeBase64)?.trim()?.ifEmpty { null }
                        else -> reader.skipValue()
                    }
                }
                val from = start
                val to = stop
                val name = title
                if (from != null && to != null && to > from && name != null) out += XtreamProgramme(from, to, name, description)
            }
        }
        return out.sortedBy { it.startSec }
    }

    private fun episode(reader: JsonReader): XtreamEpisode? {
        var id: Long? = null
        var season: Int? = null
        var number: Int? = null
        var title: String? = null
        var ext: String? = null
        var plot: String? = null
        var duration: Long? = null
        var image: String? = null
        var rating: Double? = null
        reader.forEachMember { field ->
            when (field) {
                "id" -> id = reader.nextLongOrNull()
                "season" -> season = reader.nextIntOrNull()
                "episode_num" -> number = reader.nextIntOrNull()
                "title" -> title = reader.nextString()?.trim()?.ifEmpty { null }
                "container_extension" -> ext = reader.nextString()?.trim()?.ifEmpty { null }
                "info" -> reader.forEachMember { info ->
                    when (info) {
                        "plot" -> plot = reader.nextString()?.trim()?.ifEmpty { null }
                        "duration_secs" -> duration = reader.nextLongOrNull()?.takeIf { it > 0 }
                        "duration" -> {
                            val parsed = reader.nextString()?.let(::parseDuration)
                            if (duration == null) duration = parsed
                        }
                        "movie_image" -> image = cleanUrl(reader.nextString())
                        "rating" -> rating = reader.nextDoubleOrNull()?.takeIf { it > 0 }
                        else -> reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        val episodeId = id ?: return null
        return XtreamEpisode(episodeId, season ?: 1, number ?: 0, title, ext, plot, duration, image, rating)
    }

    private fun details(reader: JsonReader): MediaDetails {
        var title: String? = null
        var original: String? = null
        var plot: String? = null
        var genre: String? = null
        var director: String? = null
        var cast: String? = null
        var release: String? = null
        var duration: Long? = null
        var rating: Double? = null
        var poster: String? = null
        var backdrop: String? = null
        var trailer: String? = null
        var country: String? = null
        reader.forEachMember { field ->
            when (field) {
                "name", "title" -> title = text(reader) ?: title
                "o_name", "original_name" -> original = text(reader)
                "plot", "description" -> plot = text(reader) ?: plot
                "genre" -> genre = text(reader)
                "director" -> director = text(reader)
                "cast", "actors" -> cast = text(reader) ?: cast
                "releasedate", "releaseDate", "release_date" -> release = text(reader) ?: release
                "duration_secs" -> duration = reader.nextLongOrNull()?.takeIf { it > 0 } ?: duration
                "duration" -> {
                    val parsed = reader.nextString()?.let(::parseDuration)
                    if (duration == null) duration = parsed
                }
                "episode_run_time" -> {
                    val minutes = reader.nextLongOrNull()
                    if (duration == null && minutes != null && minutes > 0) duration = minutes * 60
                }
                "rating" -> rating = reader.nextDoubleOrNull()?.takeIf { it > 0 } ?: rating
                "movie_image", "cover_big", "cover" -> poster = cleanUrl(reader.nextString()) ?: poster
                "backdrop_path" -> {
                    // A string or an array of strings depending on the panel.
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.forEachElement {
                            val url = cleanUrl(reader.nextString())
                            if (backdrop == null) backdrop = url
                        }
                    } else {
                        backdrop = cleanUrl(reader.nextString()) ?: backdrop
                    }
                }
                "youtube_trailer" -> trailer = text(reader)
                "country" -> country = text(reader)
                else -> reader.skipValue()
            }
        }
        return MediaDetails(title, original, plot, genre, director, cast, release, duration, rating, poster, backdrop, trailer, country)
    }

    private fun text(reader: JsonReader): String? = reader.nextString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private val EMPTY_DETAILS = MediaDetails(null, null, null, null, null, null, null, null, null, null, null, null, null)

    private fun truthy(value: String?): Boolean = value != null && (value == "1" || value.equals("true", ignoreCase = true))

    /** Panels send "", "null" or relative junk for missing images: keep only absolute http(s) URLs. */
    fun cleanUrl(value: String?): String? {
        val url = value?.trim() ?: return null
        return url.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    /** "01:42:10" or "102 min" or "6130" -> seconds. */
    fun parseDuration(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        if (':' in text) {
            var total = 0L
            for (part in text.split(':')) total = total * 60 + (part.trim().toLongOrNull() ?: return null)
            return total.takeIf { it > 0 }
        }
        val digits = text.takeWhile { it.isDigit() }.toLongOrNull() ?: return null
        return if (text.contains("min", ignoreCase = true)) digits * 60 else digits.takeIf { it > 0 }
    }

    /** First plausible year (1900-2099) in a date or a title such as "Dune (2021)". */
    fun yearOf(text: String): Int? {
        var i = 0
        while (i + 4 <= text.length) {
            val window = text.substring(i, i + 4)
            val before = if (i > 0) text[i - 1] else ' '
            val after = if (i + 4 < text.length) text[i + 4] else ' '
            if (!before.isDigit() && !after.isDigit() && window.all { it.isDigit() }) {
                val year = window.toInt()
                if (year in 1900..2099) return year
            }
            i++
        }
        return null
    }
}

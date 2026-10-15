package app.fluide.player.data

import app.fluide.core.text.SearchNormalizer
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.secure.UrlCipher

/** A stored movie or series, URL decrypted (movies). */
data class MediaRecord(
    val id: Long,
    val sourceId: Long,
    val key: String,
    val name: String,
    val group: String?,
    val poster: String?,
    val rating: Double?,
    val year: Int?,
    val plot: String?,
    val genre: String?,
    val url: String?,
    val extension: String?,
)

data class WatchState(val positionMs: Long, val durationMs: Long) {
    /** Watched to the end (credits included): restart instead of resuming. */
    val finished: Boolean get() = durationMs > 0 && positionMs >= durationMs * FINISHED_FRACTION

    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        const val FINISHED_FRACTION = 0.93
    }
}

/**
 * Movies and series of the catalogue, favorites, resume points and category rules (hidden / locked).
 * Every function blocks: call them from Dispatchers.IO.
 */
class MediaRepository(private val database: FluideDatabase, private val cipher: UrlCipher) {

    private val db get() = database.readableDatabase

    private fun table(section: Section) = if (section == Section.SERIES) "series" else "vod"

    private fun favoriteKind(section: Section) = if (section == Section.SERIES) KIND_SERIES else KIND_MOVIE

    fun groups(sourceId: Long, section: Section): List<GroupInfo> = db.rawQuery(
        "SELECT grp, COUNT(*) FROM ${table(section)} WHERE source_id = $sourceId GROUP BY grp ORDER BY MIN(position)",
        null,
    ).use { c ->
        val out = ArrayList<GroupInfo>(c.count)
        while (c.moveToNext()) out += GroupInfo(c.stringOrNull(0), c.getInt(1))
        out
    }

    fun ids(sourceId: Long, section: Section, filter: MediaFilter, sort: MediaSort, excluded: Set<String>): LongArray {
        val t = table(section)
        val (skip, skipArgs) = ChannelRepository.exclusion("m.grp", excluded)
        val order = orderBy(sort)
        return when (filter) {
            MediaFilter.All -> longs("SELECT m.id FROM $t m WHERE m.source_id = $sourceId$skip ORDER BY $order", *skipArgs)
            MediaFilter.Favorites -> longs(
                "SELECT m.id FROM $t m JOIN media_favorite f ON f.source_id = m.source_id AND f.item_key = m.item_key " +
                    "AND f.kind = ${favoriteKind(section)} WHERE m.source_id = $sourceId$skip ORDER BY f.added_at DESC",
                *skipArgs,
            )
            MediaFilter.ContinueWatching -> if (section == Section.MOVIES) {
                longs(
                    "SELECT m.id FROM vod m JOIN watch w ON w.source_id = m.source_id AND w.kind = $KIND_MOVIE " +
                        "AND w.item_key = m.item_key WHERE m.source_id = $sourceId$skip " +
                        "AND (w.duration_ms = 0 OR w.position_ms < w.duration_ms * ${WatchState.FINISHED_FRACTION}) " +
                        "ORDER BY w.updated_at DESC LIMIT $LIST_LIMIT",
                    *skipArgs,
                )
            } else {
                longs(
                    "SELECT m.id FROM series m JOIN (SELECT series_key, MAX(updated_at) AS at FROM watch " +
                        "WHERE source_id = $sourceId AND kind = $KIND_EPISODE GROUP BY series_key) w ON w.series_key = m.item_key " +
                        "WHERE m.source_id = $sourceId$skip ORDER BY w.at DESC LIMIT $LIST_LIMIT",
                    *skipArgs,
                )
            }
            MediaFilter.RecentlyAdded -> longs(
                "SELECT m.id FROM $t m WHERE m.source_id = $sourceId$skip " +
                    "ORDER BY (m.added IS NULL), m.added DESC, m.position DESC LIMIT $LIST_LIMIT",
                *skipArgs,
            )
            is MediaFilter.Group -> if (filter.name == null) {
                longs("SELECT m.id FROM $t m WHERE m.source_id = $sourceId AND m.grp IS NULL ORDER BY $order")
            } else {
                longs("SELECT m.id FROM $t m WHERE m.source_id = $sourceId AND m.grp = ? ORDER BY $order", filter.name)
            }
            is MediaFilter.Search -> {
                val words = SearchNormalizer.normalize(filter.query).split(' ').filter { it.isNotEmpty() }.take(6)
                if (words.isEmpty()) {
                    LongArray(0)
                } else {
                    val like = words.joinToString("") { " AND m.name_norm LIKE ? ESCAPE '\\'" }
                    val args = words.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
                    longs(
                        "SELECT m.id FROM $t m WHERE m.source_id = $sourceId$skip$like ORDER BY m.position LIMIT $SEARCH_LIMIT",
                        *skipArgs,
                        *args.toTypedArray(),
                    )
                }
            }
        }
    }

    private fun orderBy(sort: MediaSort): String = when (sort) {
        MediaSort.DEFAULT -> "m.position"
        MediaSort.ADDED -> "(m.added IS NULL), m.added DESC, m.position DESC"
        MediaSort.NAME_ASC -> "m.name_norm ASC"
        MediaSort.NAME_DESC -> "m.name_norm DESC"
        MediaSort.RATING -> "(m.rating IS NULL), m.rating DESC, m.position"
        MediaSort.YEAR -> "(m.year IS NULL), m.year DESC, m.position"
    }

    /** Tiles for [ids], in the same order. */
    fun rows(section: Section, ids: LongArray): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val t = table(section)
        val progress = if (section == Section.MOVIES) {
            "(SELECT CASE WHEN w.duration_ms > 0 THEN w.position_ms * 1.0 / w.duration_ms ELSE -1 END FROM watch w " +
                "WHERE w.source_id = m.source_id AND w.kind = $KIND_MOVIE AND w.item_key = m.item_key)"
        } else {
            "(SELECT 0.0 FROM watch w WHERE w.source_id = m.source_id AND w.kind = $KIND_EPISODE AND w.series_key = m.item_key LIMIT 1)"
        }
        val sql = "SELECT m.id, m.item_key, m.name, m.poster, m.rating, m.year, m.grp, " +
            "EXISTS(SELECT 1 FROM media_favorite f WHERE f.source_id = m.source_id AND f.kind = ${favoriteKind(section)} " +
            "AND f.item_key = m.item_key), $progress FROM $t m WHERE m.id IN (${ids.joinToString(",")})"
        val byId = HashMap<Long, MediaItem>(ids.size * 2)
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                byId[c.getLong(0)] = MediaItem(
                    id = c.getLong(0),
                    key = c.getString(1),
                    name = c.getString(2),
                    poster = c.stringOrNull(3),
                    rating = if (c.isNull(4)) null else c.getDouble(4),
                    year = c.intOrNull(5),
                    group = c.stringOrNull(6),
                    favorite = c.getInt(7) != 0,
                    progress = if (c.isNull(8)) -1f else c.getFloat(8),
                )
            }
        }
        val ordered = ArrayList<MediaItem>(byId.size)
        for (id in ids) byId[id]?.let(ordered::add)
        return ordered
    }

    fun record(section: Section, id: Long): MediaRecord? {
        val sql = if (section == Section.SERIES) {
            "SELECT id, source_id, item_key, name, grp, poster, rating, year, plot, genre, NULL, NULL FROM series WHERE id = $id"
        } else {
            "SELECT id, source_id, item_key, name, grp, poster, rating, year, NULL, NULL, url, ext FROM vod WHERE id = $id"
        }
        return db.rawQuery(sql, null).use { c ->
            if (!c.moveToFirst()) return@use null
            MediaRecord(
                id = c.getLong(0),
                sourceId = c.getLong(1),
                key = c.getString(2),
                name = c.getString(3),
                group = c.stringOrNull(4),
                poster = c.stringOrNull(5),
                rating = if (c.isNull(6)) null else c.getDouble(6),
                year = c.intOrNull(7),
                plot = c.stringOrNull(8),
                genre = c.stringOrNull(9),
                url = if (c.isNull(10)) null else cipher.decryptOrNull(c.getBlob(10)),
                extension = c.stringOrNull(11),
            )
        }
    }

    fun idByKey(sourceId: Long, section: Section, key: String): Long? = db.rawQuery(
        "SELECT id FROM ${table(section)} WHERE source_id = $sourceId AND item_key = ? LIMIT 1",
        arrayOf(key),
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    /** Episodes of an M3U series (Xtream episodes come from the server). */
    fun storedEpisodes(sourceId: Long, seriesKey: String): List<Episode> = db.rawQuery(
        "SELECT id, season, episode, title, url, poster FROM episode WHERE source_id = $sourceId AND series_key = ? " +
            "ORDER BY season, episode, position",
        arrayOf(seriesKey),
    ).use { c ->
        val out = ArrayList<Episode>(c.count)
        while (c.moveToNext()) {
            val url = cipher.decryptOrNull(c.getBlob(4))
            val number = c.getInt(2)
            if (url != null) {
                // Keyed by season and number, not row id: resume points survive playlist refreshes.
                out += Episode(
                    key = "s${c.getInt(1)}e$number",
                    season = c.getInt(1),
                    number = number,
                    title = c.stringOrNull(3) ?: "Épisode $number",
                    plot = null,
                    durationSec = null,
                    image = c.stringOrNull(5),
                    extension = null,
                    url = url,
                    progress = -1f,
                    positionMs = 0,
                )
            }
        }
        out
    }

    fun toggleFavorite(sourceId: Long, section: Section, key: String): Boolean {
        val w = database.writableDatabase
        val kind = favoriteKind(section)
        val removed = w.delete(
            "media_favorite",
            "source_id = ? AND kind = ? AND item_key = ?",
            arrayOf(sourceId.toString(), kind.toString(), key),
        )
        if (removed > 0) return false
        w.execSQL(
            "INSERT OR REPLACE INTO media_favorite(source_id, kind, item_key, added_at) VALUES (?, ?, ?, ?)",
            arrayOf<Any>(sourceId, kind, key, System.currentTimeMillis()),
        )
        return true
    }

    fun isFavorite(sourceId: Long, section: Section, key: String): Boolean = db.rawQuery(
        "SELECT 1 FROM media_favorite WHERE source_id = $sourceId AND kind = ${favoriteKind(section)} AND item_key = ?",
        arrayOf(key),
    ).use { it.moveToFirst() }

    // ------------------------------------------------------------------ resume points

    fun saveProgress(
        sourceId: Long,
        kind: Int,
        key: String,
        seriesKey: String?,
        name: String,
        poster: String?,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        durationMs: Long,
    ) {
        database.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO watch(source_id, kind, item_key, series_key, name, poster, season, episode, " +
                "position_ms, duration_ms, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(sourceId, kind, key, seriesKey, name, poster, season, episode, positionMs, durationMs, System.currentTimeMillis()),
        )
    }

    fun progress(sourceId: Long, kind: Int, key: String): WatchState? = db.rawQuery(
        "SELECT position_ms, duration_ms FROM watch WHERE source_id = $sourceId AND kind = $kind AND item_key = ?",
        arrayOf(key),
    ).use { c -> if (c.moveToFirst()) WatchState(c.getLong(0), c.getLong(1)) else null }

    /** Resume points of every watched episode of a series, by episode key. */
    fun episodeProgress(sourceId: Long, seriesKey: String): Map<String, WatchState> = db.rawQuery(
        "SELECT item_key, position_ms, duration_ms FROM watch WHERE source_id = $sourceId AND kind = $KIND_EPISODE AND series_key = ?",
        arrayOf(seriesKey),
    ).use { c ->
        val out = HashMap<String, WatchState>(c.count * 2)
        while (c.moveToNext()) out[c.getString(0)] = WatchState(c.getLong(1), c.getLong(2))
        out
    }

    /** Key of the episode watched last in a series. */
    fun lastEpisodeKey(sourceId: Long, seriesKey: String): String? = db.rawQuery(
        "SELECT item_key FROM watch WHERE source_id = $sourceId AND kind = $KIND_EPISODE AND series_key = ? " +
            "ORDER BY updated_at DESC LIMIT 1",
        arrayOf(seriesKey),
    ).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun clearProgress(sourceId: Long) {
        database.writableDatabase.delete("watch", "source_id = ?", arrayOf(sourceId.toString()))
    }

    // ------------------------------------------------------------------ category rules

    fun rules(sourceId: Long, section: Section): Map<String, CategoryMode> = db.rawQuery(
        "SELECT name, mode FROM category_rule WHERE source_id = $sourceId AND section = ${section.code}",
        null,
    ).use { c ->
        val out = HashMap<String, CategoryMode>()
        while (c.moveToNext()) {
            val mode = CategoryMode.entries.firstOrNull { it.code == c.getInt(1) } ?: continue
            out[c.getString(0)] = mode
        }
        out
    }

    /** [mode] null: default (visible, unlocked unless the parental filter recognises it as adult). */
    fun setRule(sourceId: Long, section: Section, name: String, mode: CategoryMode?) {
        val w = database.writableDatabase
        if (mode == null) {
            w.delete(
                "category_rule",
                "source_id = ? AND section = ? AND name = ?",
                arrayOf(sourceId.toString(), section.code.toString(), name),
            )
        } else {
            w.execSQL(
                "INSERT OR REPLACE INTO category_rule(source_id, section, name, mode) VALUES (?, ?, ?, ?)",
                arrayOf<Any>(sourceId, section.code, name, mode.code),
            )
        }
    }

    private fun longs(sql: String, vararg args: String): LongArray =
        db.rawQuery(sql, if (args.isEmpty()) null else arrayOf(*args)).use { c ->
            val out = LongArray(c.count)
            var i = 0
            while (c.moveToNext()) out[i++] = c.getLong(0)
            if (i == out.size) out else out.copyOf(i)
        }

    companion object {
        const val KIND_MOVIE = 1
        const val KIND_SERIES = 2
        const val KIND_EPISODE = 3
        private const val LIST_LIMIT = 200
        private const val SEARCH_LIMIT = 500
    }
}

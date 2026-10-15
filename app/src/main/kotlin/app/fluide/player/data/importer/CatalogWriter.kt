package app.fluide.player.data.importer

import android.database.sqlite.SQLiteStatement
import app.fluide.core.m3u.M3uEntry
import app.fluide.core.text.SearchNormalizer
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.db.inTransaction
import app.fluide.player.data.secure.UrlCipher
import app.fluide.player.playback.PlaybackPressure

/** A movie to store: [key] identifies it across refreshes (Xtream stream id, or the name for M3U). */
internal class VodRow(
    val key: String,
    val name: String,
    val group: String?,
    val poster: String?,
    val rating: Double?,
    val year: Int?,
    val addedSec: Long?,
    val url: String,
    val extension: String?,
)

internal class SeriesRow(
    val key: String,
    val name: String,
    val group: String?,
    val poster: String?,
    val rating: Double?,
    val year: Int?,
    val addedSec: Long?,
    val plot: String?,
    val genre: String?,
)

/** M3U playlists only: Xtream episodes are fetched with the series details. */
internal class EpisodeRow(
    val seriesKey: String,
    val season: Int,
    val episode: Int,
    val title: String?,
    val url: String,
    val poster: String?,
)

/**
 * Writes one source's catalogue (channels, movies, series, episodes) into the staging tables, in
 * short batched transactions (readers are never blocked, the import pauses while the player's buffer
 * is low), then [commit] swaps everything in one transaction. [abort] leaves the visible catalogue as
 * it was.
 */
internal class CatalogWriter(
    private val database: FluideDatabase,
    private val cipher: UrlCipher,
    private val pressure: PlaybackPressure,
    private val sourceId: Long,
) {
    private val db = database.writableDatabase
    private val sourceArg = arrayOf(sourceId.toString())
    private var inBatch = 0
    private var open = false

    var channels = 0
        private set
    var movies = 0
        private set
    var series = 0
        private set
    var episodes = 0
        private set
    val total: Int get() = channels + movies + series + episodes

    private val channelInsert: SQLiteStatement
    private val vodInsert: SQLiteStatement
    private val seriesInsert: SQLiteStatement
    private val episodeInsert: SQLiteStatement

    init {
        clearStaging() // leftovers of an interrupted import
        channelInsert = db.compileStatement(
            "INSERT INTO channel_staging(${FluideDatabase.CHANNEL_COLUMNS}) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        vodInsert = db.compileStatement("INSERT INTO vod_staging(${FluideDatabase.VOD_COLUMNS}) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
        seriesInsert = db.compileStatement("INSERT INTO series_staging(${FluideDatabase.SERIES_COLUMNS}) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")
        episodeInsert = db.compileStatement("INSERT INTO episode_staging(${FluideDatabase.EPISODE_COLUMNS}) VALUES (?,?,?,?,?,?,?,?)")
    }

    fun channel(e: M3uEntry) {
        begin()
        val s = channelInsert
        s.clearBindings()
        s.bindLong(1, sourceId)
        s.bindLong(2, channels.toLong())
        s.bindLong(3, (e.channelNumber ?: (channels + 1)).toLong())
        s.bindString(4, e.name)
        s.bindString(5, SearchNormalizer.normalize(e.name))
        s.bindNullable(6, e.group)
        s.bindNullable(7, e.logoUrl)
        s.bindString(8, epgKey(e))
        s.bindBlob(9, cipher.encrypt(e.url))
        s.bindLong(10, e.kind.ordinal.toLong())
        s.bindNullable(11, e.userAgent)
        s.bindNullable(12, e.referrer)
        s.bindNullable(13, e.drm?.scheme)
        val license = e.drm?.licenseUrl
        if (license == null) s.bindNull(14) else s.bindBlob(14, cipher.encrypt(license))
        s.bindNullable(15, e.catchup?.mode)
        val days = e.catchup?.days
        if (days == null) s.bindNull(16) else s.bindLong(16, days.toLong())
        s.bindNullable(17, e.catchup?.source)
        s.executeInsert()
        channels++
        step()
    }

    fun movie(row: VodRow) {
        begin()
        val s = vodInsert
        s.clearBindings()
        s.bindLong(1, sourceId)
        s.bindLong(2, movies.toLong())
        s.bindString(3, row.key)
        s.bindString(4, row.name)
        s.bindString(5, SearchNormalizer.normalize(row.name))
        s.bindNullable(6, row.group)
        s.bindNullable(7, row.poster)
        if (row.rating == null) s.bindNull(8) else s.bindDouble(8, row.rating)
        if (row.year == null) s.bindNull(9) else s.bindLong(9, row.year.toLong())
        if (row.addedSec == null) s.bindNull(10) else s.bindLong(10, row.addedSec)
        s.bindBlob(11, cipher.encrypt(row.url))
        s.bindNullable(12, row.extension)
        s.executeInsert()
        movies++
        step()
    }

    fun series(row: SeriesRow) {
        begin()
        val s = seriesInsert
        s.clearBindings()
        s.bindLong(1, sourceId)
        s.bindLong(2, series.toLong())
        s.bindString(3, row.key)
        s.bindString(4, row.name)
        s.bindString(5, SearchNormalizer.normalize(row.name))
        s.bindNullable(6, row.group)
        s.bindNullable(7, row.poster)
        if (row.rating == null) s.bindNull(8) else s.bindDouble(8, row.rating)
        if (row.year == null) s.bindNull(9) else s.bindLong(9, row.year.toLong())
        if (row.addedSec == null) s.bindNull(10) else s.bindLong(10, row.addedSec)
        s.bindNullable(11, row.plot)
        s.bindNullable(12, row.genre)
        s.executeInsert()
        series++
        step()
    }

    fun episode(row: EpisodeRow) {
        begin()
        val s = episodeInsert
        s.clearBindings()
        s.bindLong(1, sourceId)
        s.bindString(2, row.seriesKey)
        s.bindLong(3, episodes.toLong())
        s.bindLong(4, row.season.toLong())
        s.bindLong(5, row.episode.toLong())
        s.bindNullable(6, row.title)
        s.bindBlob(7, cipher.encrypt(row.url))
        s.bindNullable(8, row.poster)
        s.executeInsert()
        episodes++
        step()
    }

    /** Ends the current batch so that progress can be published. */
    fun flush() {
        if (!open) return
        db.setTransactionSuccessful()
        db.endTransaction()
        open = false
        inBatch = 0
    }

    /**
     * Replaces the source's catalogue with the staged one. [epgUrl] (encrypted) is the guide advertised
     * by the playlist header, if any.
     */
    fun commit(epgUrl: String?) {
        flush()
        closeStatements()
        val id = sourceId
        db.inTransaction {
            for ((table, columns) in TABLES) {
                delete(table, "source_id = ?", sourceArg)
                execSQL("INSERT INTO $table($columns) SELECT $columns FROM ${table}_staging WHERE source_id = $id ORDER BY position")
                delete("${table}_staging", "source_id = ?", sourceArg)
            }
            execSQL(
                "UPDATE source SET channel_count = ?, movie_count = ?, series_count = ?, header_epg_url = ?, last_error = NULL WHERE id = ?",
                arrayOf<Any?>(channels, movies, series, epgUrl?.let(cipher::encrypt), id),
            )
        }
        // External-content FTS index: rebuilt outside the swap so the swap itself stays short.
        db.execSQL("INSERT INTO channel_fts(channel_fts) VALUES('rebuild')")
    }

    fun abort() {
        if (open) {
            db.endTransaction() // not successful: the batch is rolled back
            open = false
        }
        closeStatements()
        runCatching { clearStaging() }
    }

    private fun begin() {
        if (open) return
        db.beginTransactionNonExclusive()
        open = true
    }

    private fun step() {
        if (++inBatch < BATCH) return
        flush()
        pressure.throttle()
    }

    private fun clearStaging() {
        for ((table, _) in TABLES) db.delete("${table}_staging", "source_id = ?", sourceArg)
    }

    private fun closeStatements() {
        for (statement in listOf(channelInsert, vodInsert, seriesInsert, episodeInsert)) runCatching { statement.close() }
    }

    private fun SQLiteStatement.bindNullable(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    companion object {
        private const val BATCH = 2_000

        private val TABLES = listOf(
            "channel" to FluideDatabase.CHANNEL_COLUMNS,
            "vod" to FluideDatabase.VOD_COLUMNS,
            "series" to FluideDatabase.SERIES_COLUMNS,
            "episode" to FluideDatabase.EPISODE_COLUMNS,
        )

        /** tvg-id when present (case-insensitive, like most EPG tools), else the normalised name. */
        fun epgKey(e: M3uEntry): String {
            val id = e.tvgId?.trim()?.lowercase()
            if (!id.isNullOrEmpty()) return id
            return ChannelRepository.NAME_KEY_PREFIX + SearchNormalizer.normalize(e.tvgName ?: e.name)
        }
    }
}

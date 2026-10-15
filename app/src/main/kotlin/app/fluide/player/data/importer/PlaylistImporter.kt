package app.fluide.player.data.importer

import android.database.sqlite.SQLiteStatement
import app.fluide.core.m3u.M3uEntry
import app.fluide.core.m3u.M3uHeader
import app.fluide.core.m3u.M3uParser
import app.fluide.core.text.SearchNormalizer
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.db.inTransaction
import app.fluide.player.data.secure.UrlCipher
import app.fluide.player.playback.PlaybackPressure
import kotlinx.coroutines.Job
import java.io.IOException
import java.io.Reader

class ImportException(message: String) : IOException(message)

/**
 * Streams an M3U playlist into SQLite: parse -> encrypt URL -> insert, entry by entry, so memory stays
 * flat whatever the playlist size. Entries go to `channel_staging` in batches of [BATCH] (short write
 * transactions, readers never blocked), then one swap transaction replaces the source's channels.
 */
internal class PlaylistImporter(
    private val database: FluideDatabase,
    private val cipher: UrlCipher,
    private val pressure: PlaybackPressure,
) {

    class Result(val channels: Int, val epgUrls: List<String>, val reachedLimit: Boolean)

    fun import(sourceId: Long, reader: Reader, job: Job, onProgress: (Int) -> Unit): Result {
        val db = database.writableDatabase
        val sourceArg = arrayOf(sourceId.toString())
        db.delete("channel_staging", "source_id = ?", sourceArg) // leftovers of an interrupted import
        val insert = db.compileStatement(
            "INSERT INTO channel_staging(${FluideDatabase.CHANNEL_COLUMNS}) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        )
        var header: M3uHeader? = null
        var count = 0
        val parsed = try {
            db.beginTransactionNonExclusive()
            try {
                val result = M3uParser().parse(CancellableCharReader(reader, job), onHeader = { header = it }) { entry ->
                    bind(insert, sourceId, entry)
                    insert.executeInsert()
                    count++
                    if (count % BATCH == 0) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        onProgress(count)
                        pressure.throttle()
                        db.beginTransactionNonExclusive()
                    }
                }
                db.setTransactionSuccessful()
                result
            } finally {
                db.endTransaction()
            }
        } catch (e: Throwable) {
            db.delete("channel_staging", "source_id = ?", sourceArg)
            throw e
        } finally {
            insert.close()
        }
        if (count == 0) {
            db.delete("channel_staging", "source_id = ?", sourceArg)
            throw ImportException("La playlist ne contient aucune chaîne")
        }

        val epgUrls = header?.epgUrls.orEmpty()
        db.inTransaction {
            delete("channel", "source_id = ?", sourceArg)
            execSQL(
                "INSERT INTO channel(${FluideDatabase.CHANNEL_COLUMNS}) " +
                    "SELECT ${FluideDatabase.CHANNEL_COLUMNS} FROM channel_staging WHERE source_id = $sourceId ORDER BY position",
            )
            delete("channel_staging", "source_id = ?", sourceArg)
            execSQL(
                "UPDATE source SET channel_count = ?, header_epg_url = ?, last_error = NULL WHERE id = ?",
                arrayOf<Any?>(count, epgUrls.firstOrNull()?.let(cipher::encrypt), sourceId),
            )
        }
        // External-content FTS index: rebuilt outside the swap so the swap itself stays short.
        db.execSQL("INSERT INTO channel_fts(channel_fts) VALUES('rebuild')")
        onProgress(count)
        return Result(count, epgUrls, parsed.reachedEntryLimit)
    }

    private fun bind(statement: SQLiteStatement, sourceId: Long, e: M3uEntry) {
        statement.clearBindings()
        statement.bindLong(1, sourceId)
        statement.bindLong(2, e.index.toLong())
        statement.bindLong(3, (e.channelNumber ?: (e.index + 1)).toLong())
        statement.bindString(4, e.name)
        statement.bindString(5, SearchNormalizer.normalize(e.name))
        statement.bindNullable(6, e.group)
        statement.bindNullable(7, e.logoUrl)
        statement.bindString(8, epgKey(e))
        statement.bindBlob(9, cipher.encrypt(e.url))
        statement.bindLong(10, e.kind.ordinal.toLong())
        statement.bindNullable(11, e.userAgent)
        statement.bindNullable(12, e.referrer)
        statement.bindNullable(13, e.drm?.scheme)
        val license = e.drm?.licenseUrl
        if (license == null) statement.bindNull(14) else statement.bindBlob(14, cipher.encrypt(license))
        statement.bindNullable(15, e.catchup?.mode)
        val days = e.catchup?.days
        if (days == null) statement.bindNull(16) else statement.bindLong(16, days.toLong())
        statement.bindNullable(17, e.catchup?.source)
    }

    private fun SQLiteStatement.bindNullable(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    companion object {
        private const val BATCH = 2_000

        /** tvg-id when present (case-insensitive, like most EPG tools), else the normalised name. */
        fun epgKey(e: M3uEntry): String {
            val id = e.tvgId?.trim()?.lowercase()
            if (!id.isNullOrEmpty()) return id
            return ChannelRepository.NAME_KEY_PREFIX + SearchNormalizer.normalize(e.tvgName ?: e.name)
        }
    }
}

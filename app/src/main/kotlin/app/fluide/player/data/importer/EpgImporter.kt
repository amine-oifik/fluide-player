package app.fluide.player.data.importer

import app.fluide.core.text.SearchNormalizer
import app.fluide.core.xmltv.XmltvParser
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.db.inTransaction
import app.fluide.player.playback.PlaybackPressure
import kotlinx.coroutines.Job
import java.io.Reader
import java.util.Locale

/**
 * Streams an XMLTV guide into the `programme` table, keeping only what the playlist can show:
 * programmes of unknown channels or outside the time window are skipped before their text is read.
 *
 * Channel matching, in order: tvg-id = XMLTV channel id (case-insensitive), then the normalised
 * display-name for playlist entries without tvg-id ("n:" keys).
 *
 * The playlist's guide shift (`source.epg_offset_min`, for providers whose XMLTV times are off) is applied
 * here, to the times stored: the screens read programme times as they are.
 */
internal class EpgImporter(
    private val database: FluideDatabase,
    private val pressure: PlaybackPressure,
) {

    class Result(val programmes: Int, val matchedChannels: Int)

    /**
     * Imports [reader] for [sourceId]. [offsetMinutes] shifts every programme (start and stop) by that many
     * minutes; the time window is checked on the times of the file.
     */
    fun import(
        sourceId: Long,
        reader: Reader,
        job: Job,
        pastDays: Int,
        futureDays: Int,
        offsetMinutes: Int = 0,
        onProgress: (Int) -> Unit,
    ): Result {
        val db = database.writableDatabase
        val shiftSec = offsetMinutes.toLong() * 60
        val wanted = HashSet<String>()
        db.rawQuery("SELECT DISTINCT epg_key FROM channel WHERE source_id = $sourceId AND epg_key IS NOT NULL", null)
            .use { c -> while (c.moveToNext()) wanted += c.getString(0) }
        if (wanted.isEmpty()) return Result(0, 0)

        val nowSec = System.currentTimeMillis() / 1000
        val windowStart = nowSec - pastDays * DAY
        val windowEnd = nowSec + futureDays * DAY

        // XMLTV channel id -> epg_key of the playlist (filled while <channel> elements stream by).
        val mapping = HashMap<String, String>()
        // Cache of the decision per programme channel id: one lowercase() per channel, not per programme.
        val decisions = HashMap<String, String?>()
        fun keyFor(xmltvId: String): String? = mapping[xmltvId] ?: decisions.getOrPut(xmltvId) {
            xmltvId.lowercase().takeIf { it in wanted }
        }

        val parser = XmltvParser(
            acceptChannel = { true },
            acceptProgrammeChannel = { keyFor(it) != null },
            windowStartEpochSec = windowStart,
            windowEndEpochSec = windowEnd,
            preferredLanguage = Locale.getDefault().language,
        )
        val insert = db.compileStatement(
            "INSERT OR REPLACE INTO programme(epg_key, start, stop, title, subtitle, description, category) " +
                "VALUES (?,?,?,?,?,?,?)",
        )
        val cleared = HashSet<String>()
        var count = 0
        try {
            db.beginTransactionNonExclusive()
            try {
                parser.parse(
                    CancellableCharReader(reader, job),
                    onChannel = { channel ->
                        val byId = channel.id.lowercase()
                        val key = when {
                            byId in wanted -> byId
                            channel.displayName != null ->
                                (ChannelRepository.NAME_KEY_PREFIX + SearchNormalizer.normalize(channel.displayName!!))
                                    .takeIf { it in wanted }
                            else -> null
                        }
                        if (key != null) {
                            mapping[channel.id] = key
                            decisions.remove(channel.id)
                        }
                    },
                ) { programme ->
                    val key = keyFor(programme.channelId) ?: return@parse
                    // A fresh guide replaces the old schedule of that channel (times may have moved).
                    if (cleared.add(key)) {
                        db.delete("programme", "epg_key = ? AND start >= ?", arrayOf(key, windowStart.toString()))
                    }
                    insert.clearBindings()
                    insert.bindString(1, key)
                    insert.bindLong(2, programme.startEpochSec + shiftSec)
                    insert.bindLong(3, programme.stopEpochSec + shiftSec)
                    insert.bindString(4, programme.title)
                    programme.subTitle?.let { insert.bindString(5, it) } ?: insert.bindNull(5)
                    programme.description?.let { insert.bindString(6, it) } ?: insert.bindNull(6)
                    programme.category?.let { insert.bindString(7, it) } ?: insert.bindNull(7)
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
            } finally {
                db.endTransaction()
            }
        } finally {
            insert.close()
        }
        db.delete("programme", "stop < ?", arrayOf(windowStart.toString()))
        onProgress(count)
        return Result(count, cleared.size)
    }

    /**
     * Moves the stored programmes of the channels of [sourceId] by [deltaSec] (the guide shift of the playlist
     * changed): the change shows at once, without downloading the guide again. Programmes are shared by guide
     * key, so a channel with the same key in another playlist moves too (as a re-import would replace it).
     */
    fun shiftStored(sourceId: Long, deltaSec: Long) {
        if (deltaSec == 0L) return
        val keys = "epg_key IN (SELECT epg_key FROM channel WHERE source_id = ? AND epg_key IS NOT NULL)"
        database.writableDatabase.inTransaction {
            // (epg_key, start) is the primary key and SQLite checks it row by row: the rows are first parked far
            // in the future (no moved row can land on one not moved yet), then brought back shifted.
            execSQL(
                "UPDATE programme SET start = start + ?, stop = stop + ? WHERE $keys",
                arrayOf<Any>(PARK_SEC + deltaSec, deltaSec, sourceId),
            )
            execSQL(
                "UPDATE programme SET start = start - ? WHERE $keys AND start >= ?",
                arrayOf<Any>(PARK_SEC, sourceId, PARK_SEC / 2),
            )
        }
    }

    private companion object {
        const val DAY = 86_400L

        /** About 35 000 years in seconds: far beyond any real programme time. */
        const val PARK_SEC = 1_099_511_627_776L
        const val BATCH = 5_000
    }
}

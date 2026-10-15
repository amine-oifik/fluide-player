package app.fluide.player.data.importer

import app.fluide.core.text.SearchNormalizer
import app.fluide.core.xmltv.XmltvParser
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.db.FluideDatabase
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
 */
internal class EpgImporter(
    private val database: FluideDatabase,
    private val pressure: PlaybackPressure,
) {

    class Result(val programmes: Int, val matchedChannels: Int)

    fun import(sourceId: Long, reader: Reader, job: Job, pastDays: Int, futureDays: Int, onProgress: (Int) -> Unit): Result {
        val db = database.writableDatabase
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
                    insert.bindLong(2, programme.startEpochSec)
                    insert.bindLong(3, programme.stopEpochSec)
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

    private companion object {
        const val DAY = 86_400L
        const val BATCH = 5_000
    }
}

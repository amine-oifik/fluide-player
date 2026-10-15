package app.fluide.player.data

import android.database.Cursor
import app.fluide.core.m3u.StreamKind
import app.fluide.core.text.SearchNormalizer
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.db.inTransaction
import app.fluide.player.data.secure.UrlCipher

/**
 * Read side of the channel database. Every function blocks: call them from Dispatchers.IO.
 * Queries are shaped for their index (see FluideDatabase) and return small results.
 */
class ChannelRepository(private val database: FluideDatabase, private val cipher: UrlCipher) {

    private val db get() = database.readableDatabase

    /**
     * Ids of the channels of [filter], in playlist order. Channels of [excluded] categories (hidden, or
     * locked by the parental control) are left out of every list but their own category.
     */
    fun channelIds(sourceId: Long, filter: ChannelFilter, excluded: Set<String> = emptySet()): LongArray {
        val (skip, skipArgs) = exclusion("c.grp", excluded)
        return when (filter) {
            ChannelFilter.All ->
                longs("SELECT c.id FROM channel c WHERE c.source_id = $sourceId$skip ORDER BY c.position", *skipArgs)
            ChannelFilter.Favorites -> longs(
                "SELECT c.id FROM channel c JOIN favorite f ON f.source_id = c.source_id AND f.name = c.name " +
                    "WHERE c.source_id = $sourceId$skip ORDER BY c.position",
                *skipArgs,
            )
            ChannelFilter.Recent -> longs(
                "SELECT c.id FROM channel_history h JOIN channel c ON c.source_id = h.source_id AND c.name = h.name " +
                    "WHERE h.source_id = $sourceId$skip GROUP BY h.name ORDER BY MAX(h.watched_at) DESC LIMIT $RECENT_LIMIT",
                *skipArgs,
            )
            ChannelFilter.Catchup -> longs(
                "SELECT c.id FROM channel c WHERE c.source_id = $sourceId$skip AND c.catchup_days > 0 ORDER BY c.position",
                *skipArgs,
            )
            is ChannelFilter.Group -> if (filter.name == null) {
                longs("SELECT id FROM channel WHERE source_id = $sourceId AND grp IS NULL ORDER BY position")
            } else {
                longs("SELECT id FROM channel WHERE source_id = $sourceId AND grp = ? ORDER BY position", filter.name)
            }
            is ChannelFilter.Search -> {
                val match = ftsQuery(filter.query)
                if (match == null) {
                    LongArray(0)
                } else {
                    longs(
                        "SELECT c.id FROM channel c WHERE c.source_id = $sourceId$skip AND c.id IN " +
                            "(SELECT docid FROM channel_fts WHERE channel_fts MATCH ?) ORDER BY c.position LIMIT 500",
                        *skipArgs,
                        match,
                    )
                }
            }
        }
    }

    fun catchupCount(sourceId: Long): Int = db.rawQuery(
        "SELECT COUNT(*) FROM channel WHERE source_id = $sourceId AND catchup_days > 0",
        null,
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Remembers a channel for the "Récents" list (keyed by name: survives playlist refreshes). */
    fun recordHistory(sourceId: Long, name: String) {
        val w = database.writableDatabase
        w.execSQL(
            "INSERT OR REPLACE INTO channel_history(source_id, name, watched_at) VALUES (?, ?, ?)",
            arrayOf<Any>(sourceId, name, System.currentTimeMillis()),
        )
        w.execSQL(
            "DELETE FROM channel_history WHERE source_id = ? AND name NOT IN " +
                "(SELECT name FROM channel_history WHERE source_id = ? ORDER BY watched_at DESC LIMIT $RECENT_LIMIT)",
            arrayOf<Any>(sourceId, sourceId),
        )
    }

    fun clearHistory(sourceId: Long) {
        database.writableDatabase.delete("channel_history", "source_id = ?", arrayOf(sourceId.toString()))
    }

    /** Programmes of [epgKey] overlapping [fromSec, toSec), in order (EPG listing and catch-up). */
    fun programmes(epgKey: String?, fromSec: Long, toSec: Long): List<Programme> {
        if (epgKey == null) return emptyList()
        return db.rawQuery(
            "SELECT start, stop, title, subtitle, description, category FROM programme " +
                "WHERE epg_key = ? AND start < $toSec AND stop > $fromSec ORDER BY start LIMIT 500",
            arrayOf(epgKey),
        ).use { c ->
            val out = ArrayList<Programme>(c.count)
            while (c.moveToNext()) {
                out += Programme(c.getLong(0), c.getLong(1), c.getString(2), c.stringOrNull(3), c.stringOrNull(4), c.stringOrNull(5))
            }
            out
        }
    }

    /** Stores programmes fetched on demand (Xtream short EPG) under the channel's guide key. */
    fun storeProgrammes(epgKey: String, programmes: List<Programme>) {
        if (programmes.isEmpty()) return
        val w = database.writableDatabase
        w.inTransaction {
            for (p in programmes) {
                execSQL(
                    "INSERT OR REPLACE INTO programme(epg_key, start, stop, title, subtitle, description, category) VALUES (?,?,?,?,?,?,?)",
                    arrayOf<Any?>(epgKey, p.startSec, p.stopSec, p.title, p.subtitle, p.description, p.category),
                )
            }
        }
    }

    fun groups(sourceId: Long): List<GroupInfo> = db.rawQuery(
        "SELECT grp, COUNT(*) FROM channel WHERE source_id = $sourceId GROUP BY grp ORDER BY MIN(position)",
        null,
    ).use { c ->
        val out = ArrayList<GroupInfo>(c.count)
        while (c.moveToNext()) out += GroupInfo(c.stringOrNull(0), c.getInt(1))
        out
    }

    /**
     * Rows for [ids] in the same order, with the programme on air at [nowSec]. Pages are small (ids are
     * inlined, they are numbers we produced ourselves), so this is a handful of index seeks.
     */
    fun rows(ids: LongArray, nowSec: Long): List<ChannelRow> {
        if (ids.isEmpty()) return emptyList()
        val sql = """
            SELECT c.id, c.number, c.name, c.grp, c.logo, c.epg_key, p.title, p.start, p.stop,
                   EXISTS(SELECT 1 FROM favorite f WHERE f.source_id = c.source_id AND f.name = c.name),
                   IFNULL(c.catchup_days, 0)
            FROM channel c
            LEFT JOIN programme p ON p.epg_key = c.epg_key AND p.start =
                (SELECT MAX(start) FROM programme WHERE epg_key = c.epg_key AND start <= $nowSec)
            WHERE c.id IN (${ids.joinToString(",")})
        """
        val byId = HashMap<Long, ChannelRow>(ids.size * 2)
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val stop = if (c.isNull(8)) 0L else c.getLong(8)
                val onAir = !c.isNull(6) && stop > nowSec
                byId[c.getLong(0)] = ChannelRow(
                    id = c.getLong(0),
                    number = c.intOrNull(1),
                    name = c.getString(2),
                    group = c.stringOrNull(3),
                    logo = c.stringOrNull(4),
                    epgKey = c.stringOrNull(5),
                    nowTitle = if (onAir) c.getString(6) else null,
                    nowStartSec = if (onAir) c.getLong(7) else 0,
                    nowStopSec = if (onAir) stop else 0,
                    favorite = c.getInt(9) != 0,
                    catchupDays = c.getInt(10),
                )
            }
        }
        val ordered = ArrayList<ChannelRow>(byId.size)
        for (id in ids) byId[id]?.let(ordered::add)
        return ordered
    }

    fun row(id: Long, nowSec: Long): ChannelRow? = rows(longArrayOf(id), nowSec).firstOrNull()

    /** Null when the channel no longer exists or its URL cannot be decrypted (Keystore reset). */
    fun playable(id: Long): PlayableChannel? = db.rawQuery(
        "SELECT $PLAYABLE_COLUMNS FROM channel c JOIN source s ON s.id = c.source_id WHERE c.id = $id",
        null,
    ).use { c -> if (c.moveToFirst()) readPlayable(c) else null }

    /**
     * Same channel elsewhere in the playlist ("TF1 HD", "TF1 FHD", "TF1 backup"...): same tvg-id, or same
     * name once quality tags are removed. Used by the recovery ladder to fail over without the user noticing.
     */
    fun alternates(channel: PlayableChannel, limit: Int = 4): List<PlayableChannel> {
        val base = baseName(channel.nameNorm)
        if (base.isEmpty()) return emptyList()
        val tvgId = channel.epgKey?.takeUnless { it.startsWith(NAME_KEY_PREFIX) }
        val pattern = base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<PlayableChannel>()
        db.rawQuery(
            "SELECT $PLAYABLE_COLUMNS FROM channel c JOIN source s ON s.id = c.source_id " +
                "WHERE c.source_id = ${channel.sourceId} AND c.id != ${channel.id} " +
                "AND (c.epg_key = ? OR c.name_norm LIKE ? ESCAPE '\\') ORDER BY c.position LIMIT 50",
            arrayOf(tvgId ?: "", pattern), // epg_key is never empty: "" matches nothing
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                val candidate = readPlayable(c) ?: continue
                if (candidate.url == channel.url) continue
                val sameId = tvgId != null && candidate.epgKey == tvgId
                if (sameId || baseName(candidate.nameNorm) == base) out += candidate
            }
        }
        return out
    }

    fun idByNumber(sourceId: Long, number: Int): Long? = longOrNull(
        "SELECT id FROM channel WHERE source_id = $sourceId AND number = $number ORDER BY position LIMIT 1",
    )

    fun idByName(sourceId: Long, name: String): Long? = longOrNull(
        "SELECT id FROM channel WHERE source_id = $sourceId AND name = ? ORDER BY position LIMIT 1",
        name,
    )

    /**
     * Every proper prefix of every channel number ("1", "10" for 104). Lets number entry commit as soon as
     * no longer number is possible. At most ~11 000 short strings whatever the list size.
     */
    fun numberPrefixes(sourceId: Long): Set<String> {
        val prefixes = HashSet<String>()
        db.rawQuery("SELECT DISTINCT number FROM channel WHERE source_id = $sourceId AND number IS NOT NULL", null)
            .use { c ->
                while (c.moveToNext()) {
                    val digits = c.getLong(0).toString()
                    for (end in 1 until digits.length) prefixes += digits.substring(0, end)
                }
            }
        return prefixes
    }

    /** The programme on air at [nowSec] (if any) followed by the next one. */
    fun nowNext(epgKey: String?, nowSec: Long): List<Programme> {
        if (epgKey == null) return emptyList()
        val out = ArrayList<Programme>(2)
        db.rawQuery(
            "SELECT start, stop, title, subtitle, description, category FROM programme " +
                "WHERE epg_key = ? AND stop > $nowSec AND start >= " +
                "(SELECT IFNULL(MAX(start), 0) FROM programme WHERE epg_key = ? AND start <= $nowSec) " +
                "ORDER BY start LIMIT 2",
            arrayOf(epgKey, epgKey),
        ).use { c ->
            while (c.moveToNext()) {
                out += Programme(
                    startSec = c.getLong(0),
                    stopSec = c.getLong(1),
                    title = c.getString(2),
                    subtitle = c.stringOrNull(3),
                    description = c.stringOrNull(4),
                    category = c.stringOrNull(5),
                )
            }
        }
        return out
    }

    /** Returns the new state. Favorites are keyed by name so they survive playlist refreshes. */
    fun toggleFavorite(sourceId: Long, name: String): Boolean {
        val w = database.writableDatabase
        val removed = w.delete("favorite", "source_id = ? AND name = ?", arrayOf(sourceId.toString(), name))
        if (removed > 0) return false
        w.execSQL("INSERT OR IGNORE INTO favorite(source_id, name) VALUES (?, ?)", arrayOf<Any>(sourceId, name))
        return true
    }

    private fun readPlayable(c: Cursor): PlayableChannel? {
        val url = cipher.decryptOrNull(c.getBlob(7)) ?: return null
        return PlayableChannel(
            id = c.getLong(0),
            sourceId = c.getLong(1),
            number = c.intOrNull(2),
            name = c.getString(3),
            nameNorm = c.getString(4),
            group = c.stringOrNull(5),
            logo = c.stringOrNull(6),
            epgKey = c.stringOrNull(8),
            url = url,
            kind = StreamKind.entries.getOrElse(c.getInt(9)) { StreamKind.UNKNOWN },
            userAgent = c.stringOrNull(10) ?: c.stringOrNull(14),
            referrer = c.stringOrNull(11),
            drmScheme = c.stringOrNull(12),
            drmLicenseUrl = if (c.isNull(13)) null else cipher.decryptOrNull(c.getBlob(13)),
            maxConnections = c.getInt(15),
            catchupMode = c.stringOrNull(16),
            catchupDays = c.intOrNull(17) ?: 0,
            catchupSource = c.stringOrNull(18),
        )
    }

    private fun longs(sql: String, vararg args: String): LongArray =
        db.rawQuery(sql, if (args.isEmpty()) null else arrayOf(*args)).use { c ->
            val out = LongArray(c.count)
            var i = 0
            while (c.moveToNext()) out[i++] = c.getLong(0)
            if (i == out.size) out else out.copyOf(i)
        }

    private fun longOrNull(sql: String, vararg args: String): Long? =
        db.rawQuery(sql, if (args.isEmpty()) null else arrayOf(*args)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    companion object {
        private const val RECENT_LIMIT = 50

        /** SQL condition excluding [groups] (and its arguments); empty when there is nothing to exclude. */
        fun exclusion(column: String, groups: Set<String>): Pair<String, Array<String>> {
            if (groups.isEmpty()) return "" to emptyArray()
            val marks = groups.joinToString(",") { "?" }
            return " AND ($column IS NULL OR $column NOT IN ($marks))" to groups.toTypedArray()
        }

        /** epg_key of channels without tvg-id: "n:" + normalised name. */
        const val NAME_KEY_PREFIX = "n:"

        private const val PLAYABLE_COLUMNS =
            "c.id, c.source_id, c.number, c.name, c.name_norm, c.grp, c.logo, c.url, c.epg_key, c.kind, " +
                "c.user_agent, c.referrer, c.drm_scheme, c.drm_license, s.user_agent, s.max_connections, " +
                "c.catchup_mode, c.catchup_days, c.catchup_source"

        private val QUALITY_TAGS = setOf(
            "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "hevc", "h265", "h264", "avc", "1080p", "1080i",
            "720p", "2160p", "576p", "50fps", "60fps", "25fps", "backup", "bk", "raw", "low", "high", "multi",
        )

        /** "fr tf1 fhd backup" -> "fr tf1". */
        fun baseName(normalized: String): String =
            normalized.split(' ').filter { it.isNotEmpty() && it !in QUALITY_TAGS }.joinToString(" ")

        /** "tele mat" -> "tele* mat*" (each word is a prefix, all words required). Null when nothing to search. */
        fun ftsQuery(text: String): String? {
            val words = SearchNormalizer.normalize(text).split(' ').filter { it.isNotEmpty() }.take(6)
            return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
        }
    }
}

internal fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
internal fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

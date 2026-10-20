package app.fluide.player.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite storage, written for lists of 100 000+ channels and movies on 1 GB boxes:
 *
 * - WAL: the screens keep reading while an import writes.
 * - Imports write into `*_staging` tables, then swap in one short transaction: the visible lists are
 *   never half-imported, and a failed import leaves the previous lists untouched.
 * - The UI only ever loads ids (a LongArray) and small pages of rows; nothing loads a whole list.
 * - FTS4 (available on every Android version; FTS5 is not) for instant prefix search of channels,
 *   over a normalised name (no accents, no punctuation) computed in Kotlin.
 * - Programmes keyed by (epg_key, start) WITHOUT ROWID: "what is on now" is one index seek.
 * - URLs and credentials are stored encrypted (see UrlCipher).
 *
 * Version 2 adds Xtream Codes accounts, movies, series and episodes, watch progress (resume and
 * "continue watching"), favorites of movies and series, channel history and per-category settings
 * (hidden or locked by the parental control).
 *
 * Version 3 adds a per-playlist shift of the guide (`source.epg_offset_min`, minutes), for providers whose
 * XMLTV times are off by a few hours.
 */
class FluideDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (statement in SCHEMA_V1) db.execSQL(statement)
        for (statement in UPGRADE_V2) db.execSQL(statement)
        for (statement in UPGRADE_V3) db.execSQL(statement)
    }

    /** Applies the missing steps in order (a version 1 database goes through 2, then 3). */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) for (statement in UPGRADE_V2) db.execSQL(statement)
        if (oldVersion < 3) for (statement in UPGRADE_V3) db.execSQL(statement)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (table in ALL_TABLES) db.execSQL("DROP TABLE IF EXISTS $table")
        onCreate(db)
    }

    companion object {
        private const val NAME = "fluide.db"
        private const val VERSION = 3

        /** Column list shared by `channel` and `channel_staging` (same order). */
        const val CHANNEL_COLUMNS =
            "source_id, position, number, name, name_norm, grp, logo, epg_key, url, kind, " +
                "user_agent, referrer, drm_scheme, drm_license, catchup_mode, catchup_days, catchup_source"

        const val VOD_COLUMNS = "source_id, position, item_key, name, name_norm, grp, poster, rating, year, added, url, ext"

        const val SERIES_COLUMNS = "source_id, position, item_key, name, name_norm, grp, poster, rating, year, added, plot, genre"

        const val EPISODE_COLUMNS = "source_id, series_key, position, season, episode, title, url, poster"

        private const val CHANNEL_TABLE_BODY = """(
            id INTEGER PRIMARY KEY,
            source_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            number INTEGER,
            name TEXT NOT NULL,
            name_norm TEXT NOT NULL,
            grp TEXT,
            logo TEXT,
            epg_key TEXT,
            url BLOB NOT NULL,
            kind INTEGER NOT NULL,
            user_agent TEXT,
            referrer TEXT,
            drm_scheme TEXT,
            drm_license BLOB,
            catchup_mode TEXT,
            catchup_days INTEGER,
            catchup_source TEXT
        )"""

        private const val VOD_TABLE_BODY = """(
            id INTEGER PRIMARY KEY,
            source_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            item_key TEXT NOT NULL,
            name TEXT NOT NULL,
            name_norm TEXT NOT NULL,
            grp TEXT,
            poster TEXT,
            rating REAL,
            year INTEGER,
            added INTEGER,
            url BLOB NOT NULL,
            ext TEXT
        )"""

        private const val SERIES_TABLE_BODY = """(
            id INTEGER PRIMARY KEY,
            source_id INTEGER NOT NULL,
            position INTEGER NOT NULL,
            item_key TEXT NOT NULL,
            name TEXT NOT NULL,
            name_norm TEXT NOT NULL,
            grp TEXT,
            poster TEXT,
            rating REAL,
            year INTEGER,
            added INTEGER,
            plot TEXT,
            genre TEXT
        )"""

        private const val EPISODE_TABLE_BODY = """(
            id INTEGER PRIMARY KEY,
            source_id INTEGER NOT NULL,
            series_key TEXT NOT NULL,
            position INTEGER NOT NULL,
            season INTEGER NOT NULL,
            episode INTEGER NOT NULL,
            title TEXT,
            url BLOB NOT NULL,
            poster TEXT
        )"""

        private val SCHEMA_V1 = listOf(
            """CREATE TABLE source(
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                location BLOB NOT NULL,
                epg_url BLOB,
                header_epg_url BLOB,
                user_agent TEXT,
                max_connections INTEGER NOT NULL DEFAULT 1,
                etag TEXT,
                last_modified TEXT,
                epg_etag TEXT,
                epg_last_modified TEXT,
                updated_at INTEGER NOT NULL DEFAULT 0,
                epg_updated_at INTEGER NOT NULL DEFAULT 0,
                channel_count INTEGER NOT NULL DEFAULT 0,
                programme_count INTEGER NOT NULL DEFAULT 0,
                last_error TEXT
            )""",
            "CREATE TABLE channel $CHANNEL_TABLE_BODY",
            "CREATE TABLE channel_staging $CHANNEL_TABLE_BODY",
            "CREATE INDEX channel_order ON channel(source_id, position)",
            "CREATE INDEX channel_group ON channel(source_id, grp, position)",
            "CREATE INDEX channel_number ON channel(source_id, number)",
            "CREATE INDEX channel_name ON channel(source_id, name)",
            "CREATE INDEX channel_epg ON channel(source_id, epg_key)",
            "CREATE INDEX channel_staging_source ON channel_staging(source_id)",
            // External-content FTS4: the text lives in `channel`, the index is rebuilt after each import.
            "CREATE VIRTUAL TABLE channel_fts USING fts4(content=\"channel\", name_norm, prefix=\"2,3\")",
            """CREATE TABLE programme(
                epg_key TEXT NOT NULL,
                start INTEGER NOT NULL,
                stop INTEGER NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT,
                description TEXT,
                category TEXT,
                PRIMARY KEY(epg_key, start)
            ) WITHOUT ROWID""",
            """CREATE TABLE favorite(
                source_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                PRIMARY KEY(source_id, name)
            ) WITHOUT ROWID""",
        )

        private val UPGRADE_V2 = listOf(
            // Xtream Codes accounts: credentials (encrypted), account state, catalogue sizes.
            "ALTER TABLE source ADD COLUMN username BLOB",
            "ALTER TABLE source ADD COLUMN password BLOB",
            "ALTER TABLE source ADD COLUMN expires_at INTEGER",
            "ALTER TABLE source ADD COLUMN account_status TEXT",
            "ALTER TABLE source ADD COLUMN active_connections INTEGER",
            "ALTER TABLE source ADD COLUMN server_tz TEXT",
            "ALTER TABLE source ADD COLUMN stream_format TEXT",
            "ALTER TABLE source ADD COLUMN movie_count INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE source ADD COLUMN series_count INTEGER NOT NULL DEFAULT 0",
            "CREATE TABLE vod $VOD_TABLE_BODY",
            "CREATE TABLE vod_staging $VOD_TABLE_BODY",
            "CREATE INDEX vod_order ON vod(source_id, position)",
            "CREATE INDEX vod_group ON vod(source_id, grp, position)",
            "CREATE INDEX vod_key ON vod(source_id, item_key)",
            "CREATE INDEX vod_staging_source ON vod_staging(source_id)",
            "CREATE TABLE series $SERIES_TABLE_BODY",
            "CREATE TABLE series_staging $SERIES_TABLE_BODY",
            "CREATE INDEX series_order ON series(source_id, position)",
            "CREATE INDEX series_group ON series(source_id, grp, position)",
            "CREATE INDEX series_key ON series(source_id, item_key)",
            "CREATE INDEX series_staging_source ON series_staging(source_id)",
            "CREATE TABLE episode $EPISODE_TABLE_BODY",
            "CREATE TABLE episode_staging $EPISODE_TABLE_BODY",
            "CREATE INDEX episode_series ON episode(source_id, series_key, season, episode)",
            "CREATE INDEX episode_staging_source ON episode_staging(source_id)",
            // Resume points; kind 1 = movie, 3 = episode (series_key set).
            """CREATE TABLE watch(
                source_id INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                item_key TEXT NOT NULL,
                series_key TEXT,
                name TEXT,
                poster TEXT,
                season INTEGER,
                episode INTEGER,
                position_ms INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(source_id, kind, item_key)
            ) WITHOUT ROWID""",
            "CREATE INDEX watch_recent ON watch(source_id, kind, updated_at)",
            "CREATE INDEX watch_series ON watch(source_id, series_key, updated_at)",
            // kind 1 = movie, 2 = series.
            """CREATE TABLE media_favorite(
                source_id INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                item_key TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                PRIMARY KEY(source_id, kind, item_key)
            ) WITHOUT ROWID""",
            """CREATE TABLE channel_history(
                source_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                watched_at INTEGER NOT NULL,
                PRIMARY KEY(source_id, name)
            ) WITHOUT ROWID""",
            // section 0 = live, 1 = movies, 2 = series; mode 1 = hidden, 2 = locked (parental PIN).
            """CREATE TABLE category_rule(
                source_id INTEGER NOT NULL,
                section INTEGER NOT NULL,
                name TEXT NOT NULL,
                mode INTEGER NOT NULL,
                PRIMARY KEY(source_id, section, name)
            ) WITHOUT ROWID""",
        )

        private val UPGRADE_V3 = listOf(
            // Shift of the guide in minutes (-720..720), applied to programme times when the guide is imported.
            "ALTER TABLE source ADD COLUMN epg_offset_min INTEGER NOT NULL DEFAULT 0",
        )

        private val ALL_TABLES = listOf(
            "source", "channel", "channel_staging", "channel_fts", "programme", "favorite", "vod", "vod_staging",
            "series", "series_staging", "episode", "episode_staging", "watch", "media_favorite", "channel_history",
            "category_rule",
        )
    }
}

/** Runs [block] in a transaction that does not block readers (WAL). */
inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransactionNonExclusive()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

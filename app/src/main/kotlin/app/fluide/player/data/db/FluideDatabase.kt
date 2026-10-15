package app.fluide.player.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite storage, written for lists of 100 000+ channels on 1 GB boxes:
 *
 * - WAL: the guide keeps reading while an import writes.
 * - Imports write into `channel_staging`, then swap in one short transaction: the visible list is never
 *   half-imported, and a failed import leaves the previous list untouched.
 * - The UI only ever loads ids (a LongArray) and small pages of rows; nothing loads a whole list.
 * - FTS4 (available on every Android version; FTS5 is not) for instant prefix search, over a
 *   normalised name (no accents, no punctuation) computed in Kotlin.
 * - Programmes keyed by (epg_key, start) WITHOUT ROWID: "what is on now" is one index seek.
 * - URLs are stored encrypted (see UrlCipher).
 */
class FluideDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        for (statement in SCHEMA) db.execSQL(statement)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema. Future versions migrate here instead of dropping data.
        for (table in listOf("source", "channel", "channel_staging", "channel_fts", "programme", "favorite")) {
            db.execSQL("DROP TABLE IF EXISTS $table")
        }
        onCreate(db)
    }

    companion object {
        private const val NAME = "fluide.db"
        private const val VERSION = 1

        /** Column list shared by `channel` and `channel_staging` (same order). */
        const val CHANNEL_COLUMNS =
            "source_id, position, number, name, name_norm, grp, logo, epg_key, url, kind, " +
                "user_agent, referrer, drm_scheme, drm_license, catchup_mode, catchup_days, catchup_source"

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

        private val SCHEMA = listOf(
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

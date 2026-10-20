package app.fluide.player.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.fluide.player.AspectMode
import app.fluide.player.R
import app.fluide.player.Settings
import app.fluide.player.SettingsValues
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.db.inTransaction
import app.fluide.player.data.secure.UrlCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Backup and restore of playlists, favorites, category rules and settings in one JSON file the user keeps
 * (to move to another box, or to set the app up again after a reset).
 *
 * **Security warning: the backup file contains credentials in clear text.** Playlist URLs (which usually
 * carry the subscriber's username and password), Xtream Codes usernames and passwords and the parental PIN
 * are written decrypted, because another box cannot read data encrypted with this box's key. The file only
 * goes where the user chooses (Storage Access Framework); the app never sends it anywhere. The screen that
 * offers the export must tell the user to keep the file private. On restore, every secret is encrypted again
 * with [UrlCipher] before it is stored.
 *
 * Format (JSON object, see [FORMAT] and [VERSION]):
 * - `format`, `version`, `exportedAt` (epoch milliseconds);
 * - `settings`: every [SettingsValues] field except `activeSourceId` and `lastChannelName` (they name things
 *   of this box);
 * - `playlists`: name, type (`url` or `xtream`), location, EPG URL, user agent, max connections, Xtream
 *   username, password and stream format, guide shift in minutes;
 * - `channelFavorites`, `mediaFavorites` (kind 1 = movie, 2 = series), `categoryRules`: each entry names its
 *   playlist by its index in `playlists`.
 *
 * Playlists imported from a local file are not saved: their content URI and its read permission only exist
 * on this box. Watch progress and history are not saved either.
 */
class BackupManager(
    private val context: Context,
    private val database: FluideDatabase,
    private val cipher: UrlCipher,
    private val settings: Settings,
) {
    /** Error shown to the user as is (short French message from the resources). */
    private class BackupException(message: String) : Exception(message)

    /** Writes the backup to [uri] (replacing its content); the number of playlists saved. */
    suspend fun export(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        guard(R.string.data_backup_write_failed) {
            val root = buildBackup()
            val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw BackupException(context.getString(R.string.data_backup_write_failed))
            output.use { it.write(bytes) }
            root.getJSONArray(KEY_PLAYLISTS).length()
        }
    }

    /**
     * Restores [uri]: its playlists are added (existing playlists are never changed; a playlist with the same
     * name and location as an existing one is skipped), with their favorites and category rules, then the
     * settings are applied (the active playlist stays the current one). The number of playlists added.
     */
    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        guard(R.string.data_backup_read_failed) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw BackupException(context.getString(R.string.data_backup_read_failed))
            val text = input.use { readLimited(it) }
            val root = try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw BackupException(context.getString(R.string.data_backup_not_backup))
            }
            restoreBackup(root)
        }
    }

    /** The backup document of the current database and settings (blocking: call from Dispatchers.IO). */
    internal fun buildBackup(): JSONObject {
        val root = JSONObject()
        root.put(KEY_FORMAT, FORMAT)
        root.put(KEY_VERSION, VERSION)
        root.put(KEY_EXPORTED_AT, System.currentTimeMillis())
        root.put(KEY_SETTINGS, settingsToJson(settings.current))
        val db = database.readableDatabase
        val indexes = HashMap<Long, Int>()
        val playlists = JSONArray()
        db.rawQuery(
            "SELECT id, name, type, location, epg_url, user_agent, max_connections, username, password, " +
                "stream_format, epg_offset_min FROM source ORDER BY id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val type = c.getString(2)
                if (type != TYPE_URL && type != TYPE_XTREAM) continue
                // Not readable with this box's key (restored from another device): nothing to save.
                val location = cipher.decryptOrNull(c.getBlob(3)) ?: continue
                val playlist = JSONObject()
                playlist.put("name", c.getString(1))
                playlist.put("type", type)
                playlist.put("location", location)
                if (!c.isNull(4)) cipher.decryptOrNull(c.getBlob(4))?.let { playlist.put("epgUrl", it) }
                if (!c.isNull(5)) playlist.put("userAgent", c.getString(5))
                playlist.put("maxConnections", c.getInt(6))
                if (type == TYPE_XTREAM) {
                    val username = if (c.isNull(7)) null else cipher.decryptOrNull(c.getBlob(7))
                    val password = if (c.isNull(8)) null else cipher.decryptOrNull(c.getBlob(8))
                    if (username == null || password == null) continue
                    playlist.put("username", username)
                    playlist.put("password", password)
                    playlist.put("streamFormat", if (c.isNull(9)) "ts" else c.getString(9))
                }
                playlist.put("epgOffsetMinutes", c.getInt(10))
                indexes[c.getLong(0)] = playlists.length()
                playlists.put(playlist)
            }
        }
        root.put(KEY_PLAYLISTS, playlists)

        val channelFavorites = JSONArray()
        db.rawQuery("SELECT source_id, name FROM favorite ORDER BY source_id, name", null).use { c ->
            while (c.moveToNext()) {
                val index = indexes[c.getLong(0)] ?: continue
                channelFavorites.put(JSONObject().put("playlist", index).put("name", c.getString(1)))
            }
        }
        root.put(KEY_CHANNEL_FAVORITES, channelFavorites)

        val mediaFavorites = JSONArray()
        db.rawQuery("SELECT source_id, kind, item_key, added_at FROM media_favorite ORDER BY source_id, added_at", null).use { c ->
            while (c.moveToNext()) {
                val index = indexes[c.getLong(0)] ?: continue
                mediaFavorites.put(
                    JSONObject()
                        .put("playlist", index)
                        .put("kind", c.getInt(1))
                        .put("key", c.getString(2))
                        .put("addedAt", c.getLong(3)),
                )
            }
        }
        root.put(KEY_MEDIA_FAVORITES, mediaFavorites)

        val rules = JSONArray()
        db.rawQuery("SELECT source_id, section, name, mode FROM category_rule ORDER BY source_id, section, name", null).use { c ->
            while (c.moveToNext()) {
                val index = indexes[c.getLong(0)] ?: continue
                rules.put(
                    JSONObject()
                        .put("playlist", index)
                        .put("section", c.getInt(1))
                        .put("name", c.getString(2))
                        .put("mode", c.getInt(3)),
                )
            }
        }
        root.put(KEY_CATEGORY_RULES, rules)
        return root
    }

    /**
     * Checks and restores a backup document (blocking: call from Dispatchers.IO); the number of playlists
     * added. Throws [BackupException] with a message for the user when the document is not a usable backup.
     */
    internal fun restoreBackup(root: JSONObject): Int {
        if (root.optString(KEY_FORMAT) != FORMAT) throw BackupException(context.getString(R.string.data_backup_not_backup))
        val version = root.optInt(KEY_VERSION, 0)
        if (version < 1) throw BackupException(context.getString(R.string.data_backup_damaged))
        if (version > VERSION) throw BackupException(context.getString(R.string.data_backup_newer))
        val added = restoreCatalogue(root)
        root.optJSONObject(KEY_SETTINGS)?.let(::restoreSettings)
        return added
    }

    /** Inserts the playlists and their favorites and rules in one transaction; the number of playlists added. */
    private fun restoreCatalogue(root: JSONObject): Int {
        val playlists = root.optJSONArray(KEY_PLAYLISTS) ?: JSONArray()
        val db = database.writableDatabase
        // name + location of the playlists already there (and of the ones added, against duplicates in the file).
        val known = HashSet<Pair<String, String>>()
        db.rawQuery("SELECT name, location FROM source", null).use { c ->
            while (c.moveToNext()) {
                val location = cipher.decryptOrNull(c.getBlob(1)) ?: continue
                known += c.getString(0) to location
            }
        }
        // Index in the file -> id of the playlist added.
        val ids = HashMap<Int, Long>()
        db.inTransaction {
            for (index in 0 until playlists.length()) {
                val playlist = playlists.optJSONObject(index) ?: continue
                val values = playlistValues(playlist) ?: continue
                val identity = values.getAsString("name").orEmpty() to playlist.text("location").orEmpty()
                if (identity in known) continue
                ids[index] = insertOrThrow("source", null, values)
                known += identity
            }

            root.optJSONArray(KEY_CHANNEL_FAVORITES)?.forEachObject { favorite ->
                val id = ids[favorite.optInt("playlist", -1)] ?: return@forEachObject
                val name = favorite.text("name") ?: return@forEachObject
                insertIgnoring(
                    "favorite",
                    ContentValues().apply {
                        put("source_id", id)
                        put("name", name)
                    },
                )
            }
            root.optJSONArray(KEY_MEDIA_FAVORITES)?.forEachObject { favorite ->
                val id = ids[favorite.optInt("playlist", -1)] ?: return@forEachObject
                val kind = favorite.optInt("kind", 0).takeIf { it == KIND_MOVIE || it == KIND_SERIES } ?: return@forEachObject
                val key = favorite.text("key") ?: return@forEachObject
                insertIgnoring(
                    "media_favorite",
                    ContentValues().apply {
                        put("source_id", id)
                        put("kind", kind)
                        put("item_key", key)
                        put("added_at", favorite.optLong("addedAt", System.currentTimeMillis()))
                    },
                )
            }
            root.optJSONArray(KEY_CATEGORY_RULES)?.forEachObject { rule ->
                val id = ids[rule.optInt("playlist", -1)] ?: return@forEachObject
                val section = rule.optInt("section", -1).takeIf { it in 0..2 } ?: return@forEachObject
                val mode = rule.optInt("mode", 0).takeIf { it == 1 || it == 2 } ?: return@forEachObject
                val name = rule.text("name") ?: return@forEachObject
                insertIgnoring(
                    "category_rule",
                    ContentValues().apply {
                        put("source_id", id)
                        put("section", section)
                        put("name", name)
                        put("mode", mode)
                    },
                )
            }
        }
        return ids.size
    }

    /**
     * Row of a playlist of the file, every secret encrypted again; null when the entry is unusable (unknown
     * type, location that is not an http(s) URL, Xtream account without credentials). Never imported yet:
     * the counters and dates stay at 0, so the next start-up imports it.
     */
    private fun playlistValues(playlist: JSONObject): ContentValues? {
        val type = playlist.text("type")
        if (type != TYPE_URL && type != TYPE_XTREAM) return null
        val location = playlist.text("location")?.takeIf(::isWebUrl) ?: return null
        val name = playlist.text("name") ?: context.getString(R.string.source_default_name)
        return ContentValues().apply {
            put("name", name)
            put("type", type)
            put("location", cipher.encrypt(location))
            playlist.text("epgUrl")?.takeIf(::isWebUrl)?.let { put("epg_url", cipher.encrypt(it)) }
            playlist.text("userAgent")?.let { put("user_agent", it) }
            put("max_connections", playlist.optInt("maxConnections", 1).coerceIn(1, 8))
            if (type == TYPE_XTREAM) {
                val username = playlist.text("username") ?: return null
                val password = playlist.text("password") ?: return null
                put("username", cipher.encrypt(username))
                put("password", cipher.encrypt(password))
                put("stream_format", if (playlist.text("streamFormat") == "m3u8") "m3u8" else "ts")
            }
            put("epg_offset_min", playlist.optInt("epgOffsetMinutes", 0).coerceIn(-720, 720))
        }
    }

    private fun settingsToJson(values: SettingsValues): JSONObject = JSONObject()
        .put("resumeLastChannel", values.resumeLastChannel)
        .put("autoFrameRate", values.autoFrameRate)
        .put("tunneling", values.tunneling)
        .put("audioPassthrough", values.audioPassthrough)
        .put("showStats", values.showStats)
        .put("parentalEnabled", values.parentalEnabled)
        .put("parentalPin", values.parentalPin)
        .put("aspectMode", values.aspectMode.name)
        .put("autoNextEpisode", values.autoNextEpisode)
        .put("startOnBoot", values.startOnBoot)
        .put("clock24h", values.clock24h)
        .put("language", values.language)
        .put("theme", values.theme)
        .put("subtitleScale", values.subtitleScale.toDouble())

    /** Applies the saved settings over the current ones; invalid or missing values keep the current value. */
    private fun restoreSettings(json: JSONObject) {
        settings.update { current ->
            current.copy(
                resumeLastChannel = json.optBoolean("resumeLastChannel", current.resumeLastChannel),
                autoFrameRate = json.optBoolean("autoFrameRate", current.autoFrameRate),
                tunneling = json.optBoolean("tunneling", current.tunneling),
                audioPassthrough = json.optBoolean("audioPassthrough", current.audioPassthrough),
                showStats = json.optBoolean("showStats", current.showStats),
                parentalEnabled = json.optBoolean("parentalEnabled", current.parentalEnabled),
                parentalPin = json.text("parentalPin")?.takeIf { pin -> pin.length == 4 && pin.all { it in '0'..'9' } }
                    ?: current.parentalPin,
                aspectMode = AspectMode.entries.firstOrNull { it.name == json.text("aspectMode") } ?: current.aspectMode,
                autoNextEpisode = json.optBoolean("autoNextEpisode", current.autoNextEpisode),
                startOnBoot = json.optBoolean("startOnBoot", current.startOnBoot),
                clock24h = json.optBoolean("clock24h", current.clock24h),
                language = if (json.has("language")) {
                    json.optString("language").takeIf { it in SettingsValues.LANGUAGES } ?: current.language
                } else {
                    current.language
                },
                theme = json.text("theme")?.takeIf { it in SettingsValues.THEMES } ?: current.theme,
                subtitleScale = json.optDouble("subtitleScale", current.subtitleScale.toDouble())
                    .takeIf { !it.isNaN() }?.toFloat()?.coerceIn(0.75f, 2f) ?: current.subtitleScale,
            )
        }
    }

    /**
     * Runs [block], turning every failure into a short French message ([fallback] when nothing more precise
     * fits). Cancellation is not a failure: it goes through.
     */
    private inline fun <T> guard(fallback: Int, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: BackupException) {
        Result.failure(e)
    } catch (e: SecurityException) {
        Result.failure(BackupException(context.getString(R.string.data_backup_denied)))
    } catch (e: JSONException) {
        Result.failure(BackupException(context.getString(R.string.data_backup_damaged)))
    } catch (e: Exception) {
        Result.failure(BackupException(context.getString(fallback)))
    }

    /** Reads the whole file as UTF-8, refusing files far bigger than any backup (a wrong file chosen). */
    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) throw BackupException(context.getString(R.string.data_backup_too_large))
        }
        return String(out.toByteArray(), Charsets.UTF_8).removePrefix("﻿")
    }

    private fun SQLiteDatabase.insertIgnoring(table: String, values: ContentValues) {
        insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun isWebUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)

    /** A non-blank string value, trimmed; null when missing, null or blank (optString would give "null"). */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    private inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(action)
    }

    private companion object {
        const val FORMAT = "fluide-player-backup"

        /** Version of the file format; files of a newer version are refused. */
        const val VERSION = 1

        const val KEY_FORMAT = "format"
        const val KEY_VERSION = "version"
        const val KEY_EXPORTED_AT = "exportedAt"
        const val KEY_SETTINGS = "settings"
        const val KEY_PLAYLISTS = "playlists"
        const val KEY_CHANNEL_FAVORITES = "channelFavorites"
        const val KEY_MEDIA_FAVORITES = "mediaFavorites"
        const val KEY_CATEGORY_RULES = "categoryRules"

        /** `source.type` values (see SourceManager). */
        const val TYPE_URL = "url"
        const val TYPE_XTREAM = "xtream"

        /** `media_favorite.kind`. */
        const val KIND_MOVIE = 1
        const val KIND_SERIES = 2

        /** A backup of a few playlists weighs a few kilobytes; 8 MB leaves room for thousands of favorites. */
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}

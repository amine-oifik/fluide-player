package app.fluide.player.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Process
import app.fluide.core.net.CircuitBreaker
import app.fluide.core.xtream.XtreamAccount
import app.fluide.core.xtream.XtreamUrls
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.db.inTransaction
import app.fluide.player.data.importer.CatalogWriter
import app.fluide.player.data.importer.Downloader
import app.fluide.player.data.importer.EpgImporter
import app.fluide.player.data.importer.HttpStatusException
import app.fluide.player.data.importer.ImportException
import app.fluide.player.data.importer.PlaylistImporter
import app.fluide.player.data.importer.TextInput
import app.fluide.player.data.importer.Validators
import app.fluide.player.data.secure.UrlCipher
import app.fluide.player.data.xtream.XtreamClient
import app.fluide.player.data.xtream.XtreamLogin
import app.fluide.player.device.DeviceProfile
import app.fluide.player.net.HttpStack
import app.fluide.player.playback.PlaybackPressure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

enum class ImportPhase { PLAYLIST, EPG }

data class ImportProgress(
    val sourceId: Long,
    val phase: ImportPhase,
    val count: Int,
    val done: Boolean = false,
    val failed: Boolean = false,
    val notModified: Boolean = false,
    val message: String? = null,
    val movies: Int = 0,
    val series: Int = 0,
)

/** Result of adding an Xtream Codes account: null [error] on success. */
data class AddResult(val sourceId: Long?, val error: String?)

/**
 * Sources (M3U playlists and Xtream Codes accounts) and their imports. Imports run one at a time on a
 * background-priority thread, download and parse in a single streaming pass, and yield to the player
 * when its buffer is low.
 */
class SourceManager(
    private val app: Context,
    private val database: FluideDatabase,
    private val cipher: UrlCipher,
    private val http: HttpStack,
    private val device: DeviceProfile,
    private val pressure: PlaybackPressure,
) {
    private val importDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "fluide-import")
    }.asCoroutineDispatcher()

    private val scope = CoroutineScope(SupervisorJob() + importDispatcher)
    private val playlists = PlaylistImporter(database, cipher, pressure)
    private val guides = EpgImporter(database, pressure)
    private val downloader = Downloader(http.downloadClient)
    internal val xtream = XtreamClient(downloader) { http.defaultUserAgent }
    private val breaker = CircuitBreaker()
    private val jobs = HashMap<Long, Job>()

    private val sourcesState = MutableStateFlow<List<SourceInfo>>(emptyList())
    val sources: StateFlow<List<SourceInfo>> = sourcesState.asStateFlow()

    private val progressState = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = progressState.asStateFlow()

    private val changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Emits the id of a source whose catalogue was replaced (ids changed: reload lists). */
    val channelChanges: SharedFlow<Long> = changes.asSharedFlow()

    private val guideUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Emits the id of a source whose programme guide was updated (same channels, new titles). */
    val guideChanges: SharedFlow<Long> = guideUpdates.asSharedFlow()

    suspend fun reload() = withContext(Dispatchers.IO) { reloadNow() }

    /**
     * Adds an M3U URL. A provider link of the form `.../get.php?username=...&password=...` is added as an
     * Xtream Codes account when the server answers the API (movies and series then come with their
     * details), as a plain playlist otherwise.
     */
    suspend fun addUrl(name: String, url: String, epgUrl: String?, userAgent: String?, maxConnections: Int): Long {
        XtreamUrls.fromM3uLink(url)?.let { (server, user, pass) ->
            val result = addXtream(name, server, user, pass, "ts")
            if (result.sourceId != null) return result.sourceId
        }
        val id = withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("name", name)
                put("type", TYPE_URL)
                put("location", cipher.encrypt(url.trim()))
                epgUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { put("epg_url", cipher.encrypt(it)) }
                userAgent?.trim()?.takeIf { it.isNotEmpty() }?.let { put("user_agent", it) }
                put("max_connections", maxConnections.coerceIn(1, 8))
            }
            database.writableDatabase.insertOrThrow("source", null, values).also { reloadNow() }
        }
        refresh(id)
        return id
    }

    /** Checks the account with the server, then stores it (credentials encrypted) and imports it. */
    suspend fun addXtream(name: String, server: String, username: String, password: String, format: String): AddResult {
        val login = XtreamLogin(XtreamUrls.normalizeServer(server), username.trim(), password.trim())
        val account = withContext(Dispatchers.IO) {
            try {
                Result.success(xtream.account(login, coroutineContext.job))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val info = account.getOrElse { return AddResult(null, describe(it as? Exception ?: Exception(it))) }
        if (!info.authenticated) return AddResult(null, info.message ?: "identifiants refusés par le serveur")
        val id = withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("name", name.ifBlank { hostOf(login.server) })
                put("type", TYPE_XTREAM)
                put("location", cipher.encrypt(login.server))
                put("username", cipher.encrypt(login.username))
                put("password", cipher.encrypt(login.password))
                put("epg_url", cipher.encrypt(login.urls.xmltv()))
                put("stream_format", if (format == "m3u8") "m3u8" else "ts")
                putAccount(info)
            }
            database.writableDatabase.insertOrThrow("source", null, values).also { reloadNow() }
        }
        refresh(id)
        return AddResult(id, null)
    }

    suspend fun addFile(name: String, uri: Uri): Long {
        val id = withContext(Dispatchers.IO) {
            // Keeps read access across reboots so the file can be re-imported by "Actualiser".
            runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val values = ContentValues().apply {
                put("name", name)
                put("type", TYPE_FILE)
                put("location", cipher.encrypt(uri.toString()))
                put("max_connections", 1)
            }
            database.writableDatabase.insertOrThrow("source", null, values).also { reloadNow() }
        }
        refresh(id)
        return id
    }

    suspend fun rename(id: Long, name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        database.writableDatabase.execSQL("UPDATE source SET name = ? WHERE id = ?", arrayOf<Any>(name.trim(), id))
        reloadNow()
    }

    suspend fun setMaxConnections(id: Long, maxConnections: Int) = withContext(Dispatchers.IO) {
        database.writableDatabase.execSQL(
            "UPDATE source SET max_connections = ? WHERE id = ?",
            arrayOf<Any>(maxConnections.coerceIn(1, 8), id),
        )
        reloadNow()
    }

    /** Xtream live container: "ts" (MPEG-TS) or "m3u8" (HLS). Applies at the next refresh. */
    suspend fun setStreamFormat(id: Long, format: String) = withContext(Dispatchers.IO) {
        database.writableDatabase.execSQL(
            "UPDATE source SET stream_format = ?, updated_at = 0 WHERE id = ?",
            arrayOf<Any>(if (format == "m3u8") "m3u8" else "ts", id),
        )
        reloadNow()
    }

    suspend fun delete(id: Long) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val args = arrayOf(id.toString())
            db.inTransaction {
                for (table in SOURCE_TABLES) delete(table, "source_id = ?", args)
                delete("source", "id = ?", args)
            }
            db.execSQL("INSERT INTO channel_fts(channel_fts) VALUES('rebuild')")
            reloadNow()
        }
        changes.emit(id)
    }

    /** Re-downloads the catalogue and/or its guide. One import per source at a time. */
    fun refresh(id: Long, playlist: Boolean = true, epg: Boolean = true) {
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            jobs[id] = scope.launch { runImport(id, playlist, epg) }
        }
    }

    /** Automatic refresh at start-up: catalogue older than a day, guide older than 12 hours. */
    fun refreshIfStale(id: Long) {
        val source = sources.value.firstOrNull { it.id == id } ?: return
        if (!breaker.allow(id.toString(), System.currentTimeMillis())) return
        val now = System.currentTimeMillis()
        val playlist = source.type != SourceType.FILE && now - source.updatedAt > PLAYLIST_MAX_AGE_MS
        val epg = now - source.epgUpdatedAt > EPG_MAX_AGE_MS
        if (playlist || epg) refresh(id, playlist, epg)
    }

    /** Decrypted credentials of an Xtream source (blocking: call from Dispatchers.IO). */
    fun login(id: Long): XtreamLogin? {
        val row = readSource(id) ?: return null
        if (row.type != SourceType.XTREAM) return null
        val server = row.location ?: return null
        return XtreamLogin(server, row.username ?: return null, row.password ?: return null)
    }

    /** Refreshes the account information (expiry, connections) without re-importing. */
    suspend fun refreshAccount(id: Long) = withContext(Dispatchers.IO) {
        val login = login(id) ?: return@withContext
        val info = runCatching { xtream.account(login, coroutineContext.job) }.getOrNull() ?: return@withContext
        val values = ContentValues().apply { putAccount(info) }
        database.writableDatabase.update("source", values, "id = ?", arrayOf(id.toString()))
        reloadNow()
    }

    private fun ContentValues.putAccount(info: XtreamAccount) {
        if (info.expiresAtSec != null) put("expires_at", info.expiresAtSec) else putNull("expires_at")
        put("account_status", info.status ?: if (info.authenticated) "Active" else "Refused")
        info.activeConnections?.let { put("active_connections", it) }
        info.maxConnections?.takeIf { it > 0 }?.let { put("max_connections", it.coerceIn(1, 8)) }
        info.serverTimezone?.let { put("server_tz", it) }
    }

    private suspend fun runImport(id: Long, playlist: Boolean, epg: Boolean) {
        val job = coroutineContext.job
        val row = readSource(id) ?: return
        val location = row.location ?: run {
            fail(id, ImportPhase.PLAYLIST, ImportException("Source illisible sur cet appareil : ajoutez-la à nouveau"))
            return
        }
        var guideUrl = row.epgUrl ?: row.headerEpgUrl
        if (playlist) {
            progressState.value = ImportProgress(id, ImportPhase.PLAYLIST, 0)
            try {
                val onProgress = { n: Int -> progressState.value = ImportProgress(id, ImportPhase.PLAYLIST, n) }
                when (row.type) {
                    SourceType.XTREAM -> {
                        val login = XtreamLogin(location, row.username ?: "", row.password ?: "")
                        val account = xtream.account(login, job)
                        if (!account.authenticated) throw ImportException(account.message ?: "identifiants refusés par le serveur")
                        database.writableDatabase.update(
                            "source",
                            ContentValues().apply { putAccount(account) },
                            "id = ?",
                            arrayOf(id.toString()),
                        )
                        val writer = CatalogWriter(database, cipher, pressure, id)
                        try {
                            xtream.importCatalog(login, row.streamFormat ?: "ts", writer, job, onProgress)
                        } catch (e: Throwable) {
                            writer.abort()
                            throw e
                        }
                        if (writer.total == 0) {
                            writer.abort()
                            throw ImportException("Le compte ne contient aucune chaîne ni vidéo")
                        }
                        writer.commit(null)
                        touch(id, "updated_at")
                        reloadNow()
                        changes.emit(id)
                        progressState.value = ImportProgress(
                            id, ImportPhase.PLAYLIST, writer.channels, done = true, movies = writer.movies, series = writer.series,
                        )
                    }
                    SourceType.URL, SourceType.FILE -> {
                        val result = if (row.type == SourceType.URL) {
                            downloader.fetch(
                                url = location,
                                userAgent = row.userAgent ?: http.defaultUserAgent,
                                previous = Validators(row.etag, row.lastModified),
                                job = job,
                            ) { reader, validators ->
                                playlists.import(id, reader, job, onProgress).also { saveValidators(id, validators, epg = false) }
                            }
                        } else {
                            (app.contentResolver.openInputStream(Uri.parse(location))
                                ?: throw ImportException("Fichier introuvable")).use { input ->
                                TextInput.open(input).use { reader -> playlists.import(id, reader, job, onProgress) }
                            }
                        }
                        touch(id, "updated_at")
                        if (result == null) {
                            progressState.value =
                                ImportProgress(id, ImportPhase.PLAYLIST, row.channelCount, done = true, notModified = true)
                        } else {
                            if (row.epgUrl == null) guideUrl = result.epgUrls.firstOrNull() ?: guideUrl
                            reloadNow()
                            changes.emit(id)
                            progressState.value = ImportProgress(
                                id, ImportPhase.PLAYLIST, result.channels, done = true, movies = result.movies, series = result.series,
                            )
                        }
                    }
                }
                breaker.onSuccess(id.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(id, ImportPhase.PLAYLIST, e)
                return
            }
        }
        val epgSource = guideUrl
        if (epg && epgSource != null) {
            progressState.value = ImportProgress(id, ImportPhase.EPG, 0)
            try {
                val result = downloader.fetch(
                    url = epgSource,
                    userAgent = row.userAgent ?: http.defaultUserAgent,
                    previous = if (playlist && guideUrl != (row.epgUrl ?: row.headerEpgUrl)) Validators(null, null)
                    else Validators(row.epgEtag, row.epgLastModified),
                    job = job,
                ) { reader, validators ->
                    guides.import(id, reader, job, device.budget.epgPastDays.coerceAtLeast(EPG_MIN_PAST_DAYS), device.budget.epgFutureDays) { n ->
                        progressState.value = ImportProgress(id, ImportPhase.EPG, n)
                    }.also { saveValidators(id, validators, epg = true) }
                }
                touch(id, "epg_updated_at")
                if (result != null) {
                    database.writableDatabase.execSQL(
                        "UPDATE source SET programme_count = ? WHERE id = ?",
                        arrayOf<Any>(result.programmes, id),
                    )
                }
                progressState.value = ImportProgress(
                    id, ImportPhase.EPG, result?.programmes ?: row.programmeCount, done = true, notModified = result == null,
                )
                if (result != null) guideUpdates.emit(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(id, ImportPhase.EPG, e)
                return
            }
        }
        reloadNow()
    }

    private fun fail(id: Long, phase: ImportPhase, error: Exception) {
        val message = describe(error)
        breaker.onFailure(id.toString(), System.currentTimeMillis())
        runCatching {
            database.writableDatabase.execSQL("UPDATE source SET last_error = ? WHERE id = ?", arrayOf<Any>(message, id))
        }
        progressState.value = ImportProgress(id, phase, 0, done = true, failed = true, message = message)
        reloadNow()
    }

    private fun describe(e: Exception): String = when (e) {
        is HttpStatusException -> "le serveur a répondu HTTP ${e.code}"
        is UnknownHostException -> "serveur introuvable (DNS)"
        is SocketTimeoutException -> "le serveur ne répond pas"
        is ImportException -> e.message ?: "erreur"
        is IllegalArgumentException -> "URL invalide"
        is SecurityException -> "accès au fichier refusé"
        is app.fluide.core.json.JsonException -> "réponse du serveur illisible"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun touch(id: Long, column: String) {
        database.writableDatabase.execSQL(
            "UPDATE source SET $column = ?, last_error = NULL WHERE id = ?",
            arrayOf<Any>(System.currentTimeMillis(), id),
        )
    }

    private fun saveValidators(id: Long, validators: Validators, epg: Boolean) {
        val etag = if (epg) "epg_etag" else "etag"
        val modified = if (epg) "epg_last_modified" else "last_modified"
        database.writableDatabase.execSQL(
            "UPDATE source SET $etag = ?, $modified = ? WHERE id = ?",
            arrayOf<Any?>(validators.etag, validators.lastModified, id),
        )
    }

    private class SourceRow(
        val type: SourceType,
        val location: String?,
        val epgUrl: String?,
        val headerEpgUrl: String?,
        val userAgent: String?,
        val etag: String?,
        val lastModified: String?,
        val epgEtag: String?,
        val epgLastModified: String?,
        val channelCount: Int,
        val programmeCount: Int,
        val username: String?,
        val password: String?,
        val streamFormat: String?,
    )

    private fun readSource(id: Long): SourceRow? = database.readableDatabase.rawQuery(
        "SELECT type, location, epg_url, header_epg_url, user_agent, etag, last_modified, epg_etag, " +
            "epg_last_modified, channel_count, programme_count, username, password, stream_format FROM source WHERE id = $id",
        null,
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        SourceRow(
            type = typeOf(c.getString(0)),
            location = cipher.decryptOrNull(c.getBlob(1)),
            epgUrl = c.blobOrNull(2)?.let(cipher::decryptOrNull),
            headerEpgUrl = c.blobOrNull(3)?.let(cipher::decryptOrNull),
            userAgent = c.stringOrNull(4),
            etag = c.stringOrNull(5),
            lastModified = c.stringOrNull(6),
            epgEtag = c.stringOrNull(7),
            epgLastModified = c.stringOrNull(8),
            channelCount = c.getInt(9),
            programmeCount = c.getInt(10),
            username = c.blobOrNull(11)?.let(cipher::decryptOrNull),
            password = c.blobOrNull(12)?.let(cipher::decryptOrNull),
            streamFormat = c.stringOrNull(13),
        )
    }

    private fun reloadNow() {
        sourcesState.value = database.readableDatabase.rawQuery(
            "SELECT id, name, type, channel_count, programme_count, updated_at, epg_updated_at, max_connections, " +
                "user_agent, last_error, movie_count, series_count, expires_at, account_status, active_connections, " +
                "stream_format, server_tz FROM source ORDER BY id",
            null,
        ).use { c ->
            val out = ArrayList<SourceInfo>(c.count)
            while (c.moveToNext()) {
                out += SourceInfo(
                    id = c.getLong(0),
                    name = c.getString(1),
                    type = typeOf(c.getString(2)),
                    channelCount = c.getInt(3),
                    programmeCount = c.getInt(4),
                    updatedAt = c.getLong(5),
                    epgUpdatedAt = c.getLong(6),
                    maxConnections = c.getInt(7),
                    userAgent = c.stringOrNull(8),
                    lastError = c.stringOrNull(9),
                    movieCount = c.getInt(10),
                    seriesCount = c.getInt(11),
                    expiresAtSec = if (c.isNull(12)) null else c.getLong(12),
                    accountStatus = c.stringOrNull(13),
                    activeConnections = c.intOrNull(14),
                    streamFormat = c.stringOrNull(15) ?: "ts",
                    serverTimezone = c.stringOrNull(16),
                )
            }
            out
        }
    }

    private fun Cursor.blobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)

    private fun hostOf(server: String): String = runCatching { Uri.parse(server).host }.getOrNull() ?: "Xtream"

    private companion object {
        const val TYPE_URL = "url"
        const val TYPE_FILE = "file"
        const val TYPE_XTREAM = "xtream"
        const val PLAYLIST_MAX_AGE_MS = 24 * 60 * 60_000L
        const val EPG_MAX_AGE_MS = 12 * 60 * 60_000L

        /** Catch-up needs the past programmes: keep at least two days of them. */
        const val EPG_MIN_PAST_DAYS = 2

        val SOURCE_TABLES = listOf(
            "channel", "channel_staging", "favorite", "vod", "vod_staging", "series", "series_staging", "episode",
            "episode_staging", "watch", "media_favorite", "channel_history", "category_rule",
        )

        fun typeOf(value: String?): SourceType = when (value) {
            TYPE_FILE -> SourceType.FILE
            TYPE_XTREAM -> SourceType.XTREAM
            else -> SourceType.URL
        }
    }
}

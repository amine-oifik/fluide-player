package app.fluide.player.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Process
import app.fluide.core.net.CircuitBreaker
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.db.inTransaction
import app.fluide.player.data.importer.Downloader
import app.fluide.player.data.importer.EpgImporter
import app.fluide.player.data.importer.HttpStatusException
import app.fluide.player.data.importer.ImportException
import app.fluide.player.data.importer.PlaylistImporter
import app.fluide.player.data.importer.TextInput
import app.fluide.player.data.importer.Validators
import app.fluide.player.data.secure.UrlCipher
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
)

/**
 * Sources (playlists) and their imports. Imports run one at a time on a background-priority thread,
 * download and parse in a single streaming pass, and yield to the player when its buffer is low.
 */
class SourceManager(
    private val app: Context,
    private val database: FluideDatabase,
    private val cipher: UrlCipher,
    private val http: HttpStack,
    private val device: DeviceProfile,
    pressure: PlaybackPressure,
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
    private val breaker = CircuitBreaker()
    private val jobs = HashMap<Long, Job>()

    private val sourcesState = MutableStateFlow<List<SourceInfo>>(emptyList())
    val sources: StateFlow<List<SourceInfo>> = sourcesState.asStateFlow()

    private val progressState = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = progressState.asStateFlow()

    private val changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Emits the id of a source whose channel list was replaced (ids changed: reload lists). */
    val channelChanges: SharedFlow<Long> = changes.asSharedFlow()

    private val guideUpdates = MutableSharedFlow<Long>(extraBufferCapacity = 16)

    /** Emits the id of a source whose programme guide was updated (same channels, new titles). */
    val guideChanges: SharedFlow<Long> = guideUpdates.asSharedFlow()

    suspend fun reload() = withContext(Dispatchers.IO) { reloadNow() }

    suspend fun addUrl(name: String, url: String, epgUrl: String?, userAgent: String?, maxConnections: Int): Long {
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

    suspend fun setMaxConnections(id: Long, maxConnections: Int) = withContext(Dispatchers.IO) {
        database.writableDatabase.execSQL(
            "UPDATE source SET max_connections = ? WHERE id = ?",
            arrayOf<Any>(maxConnections.coerceIn(1, 8), id),
        )
        reloadNow()
    }

    suspend fun delete(id: Long) {
        synchronized(jobs) { jobs.remove(id)?.cancel() }
        withContext(Dispatchers.IO) {
            val db = database.writableDatabase
            val args = arrayOf(id.toString())
            db.inTransaction {
                delete("channel", "source_id = ?", args)
                delete("channel_staging", "source_id = ?", args)
                delete("favorite", "source_id = ?", args)
                delete("source", "id = ?", args)
            }
            db.execSQL("INSERT INTO channel_fts(channel_fts) VALUES('rebuild')")
            reloadNow()
        }
        changes.emit(id)
    }

    /** Re-downloads the playlist and/or its guide. One import per source at a time. */
    fun refresh(id: Long, playlist: Boolean = true, epg: Boolean = true) {
        synchronized(jobs) {
            if (jobs[id]?.isActive == true) return
            jobs[id] = scope.launch { runImport(id, playlist, epg) }
        }
    }

    /** Automatic refresh at start-up: playlist older than a day, guide older than 12 hours. */
    fun refreshIfStale(id: Long) {
        val source = sources.value.firstOrNull { it.id == id } ?: return
        if (!breaker.allow(id.toString(), System.currentTimeMillis())) return
        val now = System.currentTimeMillis()
        val playlist = source.type == SourceType.URL && now - source.updatedAt > PLAYLIST_MAX_AGE_MS
        val epg = now - source.epgUpdatedAt > EPG_MAX_AGE_MS
        if (playlist || epg) refresh(id, playlist, epg)
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
                val result = when (row.type) {
                    SourceType.URL -> downloader.fetch(
                        url = location,
                        userAgent = row.userAgent ?: http.defaultUserAgent,
                        previous = Validators(row.etag, row.lastModified),
                        job = job,
                    ) { reader, validators ->
                        playlists.import(id, reader, job, onProgress).also { saveValidators(id, validators, epg = false) }
                    }
                    SourceType.FILE -> (app.contentResolver.openInputStream(Uri.parse(location))
                        ?: throw ImportException("Fichier introuvable")).use { input ->
                        TextInput.open(input).use { reader -> playlists.import(id, reader, job, onProgress) }
                    }
                }
                touch(id, "updated_at")
                breaker.onSuccess(id.toString())
                if (result == null) {
                    progressState.value = ImportProgress(id, ImportPhase.PLAYLIST, row.channelCount, done = true, notModified = true)
                } else {
                    if (row.epgUrl == null) guideUrl = result.epgUrls.firstOrNull() ?: guideUrl
                    reloadNow()
                    changes.emit(id)
                    progressState.value = ImportProgress(id, ImportPhase.PLAYLIST, result.channels, done = true)
                }
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
                    guides.import(id, reader, job, device.budget.epgPastDays, device.budget.epgFutureDays) { n ->
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
    )

    private fun readSource(id: Long): SourceRow? = database.readableDatabase.rawQuery(
        "SELECT type, location, epg_url, header_epg_url, user_agent, etag, last_modified, epg_etag, " +
            "epg_last_modified, channel_count, programme_count FROM source WHERE id = $id",
        null,
    ).use { c ->
        if (!c.moveToFirst()) return@use null
        SourceRow(
            type = if (c.getString(0) == TYPE_FILE) SourceType.FILE else SourceType.URL,
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
        )
    }

    private fun reloadNow() {
        sourcesState.value = database.readableDatabase.rawQuery(
            "SELECT id, name, type, channel_count, programme_count, updated_at, epg_updated_at, max_connections, " +
                "user_agent, last_error FROM source ORDER BY id",
            null,
        ).use { c ->
            val out = ArrayList<SourceInfo>(c.count)
            while (c.moveToNext()) {
                out += SourceInfo(
                    id = c.getLong(0),
                    name = c.getString(1),
                    type = if (c.getString(2) == TYPE_FILE) SourceType.FILE else SourceType.URL,
                    channelCount = c.getInt(3),
                    programmeCount = c.getInt(4),
                    updatedAt = c.getLong(5),
                    epgUpdatedAt = c.getLong(6),
                    maxConnections = c.getInt(7),
                    userAgent = c.stringOrNull(8),
                    lastError = c.stringOrNull(9),
                )
            }
            out
        }
    }

    private fun Cursor.blobOrNull(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)

    private companion object {
        const val TYPE_URL = "url"
        const val TYPE_FILE = "file"
        const val PLAYLIST_MAX_AGE_MS = 24 * 60 * 60_000L
        const val EPG_MAX_AGE_MS = 12 * 60 * 60_000L
    }
}

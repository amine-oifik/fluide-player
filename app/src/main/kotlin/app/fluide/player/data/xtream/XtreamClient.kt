package app.fluide.player.data.xtream

import android.util.Base64
import app.fluide.core.json.JsonReader
import app.fluide.core.m3u.M3uEntry
import app.fluide.core.m3u.CatchupInfo
import app.fluide.core.m3u.StreamKind
import app.fluide.core.xtream.XtreamAccount
import app.fluide.core.xtream.XtreamCategory
import app.fluide.core.xtream.XtreamParser
import app.fluide.core.xtream.XtreamProgramme
import app.fluide.core.xtream.XtreamSeriesInfo
import app.fluide.core.xtream.XtreamUrls
import app.fluide.core.xtream.XtreamVodInfo
import app.fluide.player.data.importer.CancellableCharReader
import app.fluide.player.data.importer.CatalogWriter
import app.fluide.player.data.importer.Downloader
import app.fluide.player.data.importer.ImportException
import app.fluide.player.data.importer.SeriesRow
import app.fluide.player.data.importer.Validators
import app.fluide.player.data.importer.VodRow
import kotlinx.coroutines.Job

/** Credentials of one Xtream Codes account, decrypted (memory only). */
data class XtreamLogin(val server: String, val username: String, val password: String) {
    val urls: XtreamUrls get() = XtreamUrls(server, username, password)
}

/**
 * Talks to an Xtream Codes server (`player_api.php`). Big lists are streamed straight into the
 * catalogue writer; details (movie info, series episodes, short EPG) are fetched on demand.
 */
internal class XtreamClient(private val downloader: Downloader, private val userAgent: () -> String) {

    fun account(login: XtreamLogin, job: Job): XtreamAccount =
        get(login.urls.api(), job) { XtreamParser.account(it) }

    /**
     * Imports live channels, movies and series of the account. [format] is the live container ("ts" or
     * "m3u8"). Returns the number of items written.
     */
    fun importCatalog(login: XtreamLogin, format: String, writer: CatalogWriter, job: Job, onProgress: (Int) -> Unit) {
        val urls = login.urls

        val liveGroups = categories(urls.api("get_live_categories"), job)
        get(urls.api("get_live_streams"), job) { reader ->
            XtreamParser.liveStreams(reader) { s ->
                val url = urls.live(s.streamId, format)
                writer.channel(
                    M3uEntry(
                        index = writer.channels,
                        name = s.name,
                        url = url,
                        tvgId = s.epgChannelId,
                        tvgName = null,
                        logoUrl = s.icon,
                        group = s.categoryId?.let(liveGroups::get),
                        channelNumber = s.num,
                        durationSeconds = -1,
                        userAgent = null,
                        referrer = null,
                        catchup = if (s.hasArchive) CatchupInfo("xc", s.archiveDays, null) else null,
                        drm = null,
                        kind = if (format == "m3u8") StreamKind.HLS else StreamKind.MPEG_TS,
                    ),
                )
                progress(writer, onProgress)
            }
        }

        val movieGroups = categories(urls.api("get_vod_categories"), job)
        get(urls.api("get_vod_streams"), job) { reader ->
            XtreamParser.vodStreams(reader) { m ->
                writer.movie(
                    VodRow(
                        key = m.streamId.toString(),
                        name = m.name,
                        group = m.categoryId?.let(movieGroups::get),
                        poster = m.icon,
                        rating = m.rating,
                        year = m.year,
                        addedSec = m.addedSec,
                        url = urls.movie(m.streamId, m.extension),
                        extension = m.extension,
                    ),
                )
                progress(writer, onProgress)
            }
        }

        val seriesGroups = categories(urls.api("get_series_categories"), job)
        get(urls.api("get_series"), job) { reader ->
            XtreamParser.series(reader) { s ->
                writer.series(
                    SeriesRow(
                        key = s.seriesId.toString(),
                        name = s.name,
                        group = s.categoryId?.let(seriesGroups::get),
                        poster = s.cover,
                        rating = s.rating,
                        year = s.year,
                        addedSec = s.lastModifiedSec,
                        plot = s.plot,
                        genre = s.genre,
                    ),
                )
                progress(writer, onProgress)
            }
        }
    }

    fun vodInfo(login: XtreamLogin, streamId: String, job: Job): XtreamVodInfo =
        get(login.urls.api("get_vod_info", "vod_id" to streamId), job) { XtreamParser.vodInfo(it) }

    fun seriesInfo(login: XtreamLogin, seriesId: String, job: Job): XtreamSeriesInfo =
        get(login.urls.api("get_series_info", "series_id" to seriesId), job) { XtreamParser.seriesInfo(it) }

    fun shortEpg(login: XtreamLogin, streamId: Long, job: Job): List<XtreamProgramme> =
        get(login.urls.api("get_short_epg", "stream_id" to streamId.toString(), "limit" to "12"), job) { reader ->
            XtreamParser.shortEpg(reader, ::decodeBase64)
        }

    private fun categories(url: String, job: Job): Map<String, String> =
        runCatching { get(url, job) { reader -> XtreamParser.categories(reader) } }
            .getOrDefault(emptyList<XtreamCategory>())
            .associate { it.id to it.name }

    private fun progress(writer: CatalogWriter, onProgress: (Int) -> Unit) {
        if (writer.total % PROGRESS_EVERY == 0) {
            writer.flush()
            onProgress(writer.total)
        }
    }

    private fun <T> get(url: String, job: Job, parse: (JsonReader) -> T): T =
        downloader.fetch(url, userAgent(), Validators(null, null), job) { reader, _ ->
            parse(JsonReader(CancellableCharReader(reader, job)))
        } ?: throw ImportException("Réponse vide du serveur")

    private fun decodeBase64(value: String): String? =
        runCatching { String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()

    private companion object {
        const val PROGRESS_EVERY = 2_000
    }
}

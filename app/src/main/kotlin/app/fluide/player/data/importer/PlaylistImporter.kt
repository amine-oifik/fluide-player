package app.fluide.player.data.importer

import app.fluide.core.m3u.M3uEntry
import app.fluide.core.m3u.M3uHeader
import app.fluide.core.m3u.M3uParser
import app.fluide.core.text.SearchNormalizer
import app.fluide.core.vod.MediaClassifier
import app.fluide.core.vod.MediaKind
import app.fluide.core.xtream.XtreamParser
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.secure.UrlCipher
import app.fluide.player.playback.PlaybackPressure
import kotlinx.coroutines.Job
import java.io.IOException
import java.io.Reader

class ImportException(message: String) : IOException(message)

/**
 * Streams an M3U playlist into SQLite: parse -> classify -> encrypt URL -> insert, entry by entry, so
 * memory stays flat whatever the playlist size. Live channels, movies and series episodes go to their
 * own tables (like provider apps present them: Live TV, Movies, Series); series are grouped by the
 * name before their `S01E02` marker.
 */
internal class PlaylistImporter(
    private val database: FluideDatabase,
    private val cipher: UrlCipher,
    private val pressure: PlaybackPressure,
) {

    class Result(val channels: Int, val movies: Int, val series: Int, val epgUrls: List<String>, val reachedLimit: Boolean)

    private class SeriesInfo(val name: String, val group: String?, val poster: String?)

    fun import(sourceId: Long, reader: Reader, job: Job, onProgress: (Int) -> Unit): Result {
        val writer = CatalogWriter(database, cipher, pressure, sourceId)
        var header: M3uHeader? = null
        // Series met so far (key -> first entry's info); a few thousand at most.
        val seriesSeen = LinkedHashMap<String, SeriesInfo>()
        val parsed = try {
            M3uParser().parse(CancellableCharReader(reader, job), onHeader = { header = it }) { entry ->
                when (MediaClassifier.classify(entry)) {
                    MediaKind.LIVE -> writer.channel(entry)
                    MediaKind.MOVIE -> writer.movie(
                        VodRow(
                            key = entry.name,
                            name = entry.name,
                            group = entry.group,
                            poster = entry.logoUrl,
                            rating = null,
                            year = XtreamParser.yearOf(entry.name),
                            addedSec = null,
                            url = entry.url,
                            extension = extensionOf(entry.url),
                        ),
                    )
                    MediaKind.EPISODE -> {
                        val parsedName = MediaClassifier.parseEpisode(entry.name)
                        if (parsedName == null) {
                            // "/series/" URL without episode numbering: keep it playable as a movie.
                            writer.movie(
                                VodRow(entry.name, entry.name, entry.group, entry.logoUrl, null, null, null, entry.url, extensionOf(entry.url)),
                            )
                        } else {
                            val key = SearchNormalizer.normalize(parsedName.series)
                            if (key !in seriesSeen) seriesSeen[key] = SeriesInfo(parsedName.series, entry.group, entry.logoUrl)
                            writer.episode(
                                EpisodeRow(key, parsedName.season, parsedName.episode, parsedName.title, entry.url, entry.logoUrl),
                            )
                        }
                    }
                }
                if (writer.total % PROGRESS_EVERY == 0) {
                    writer.flush()
                    onProgress(writer.total)
                }
            }
        } catch (e: Throwable) {
            writer.abort()
            throw e
        }
        for ((key, info) in seriesSeen) {
            writer.series(SeriesRow(key, info.name, info.group, info.poster, null, XtreamParser.yearOf(info.name), null, null, null))
        }
        if (writer.channels + writer.movies + writer.series == 0) {
            writer.abort()
            throw ImportException("La playlist ne contient aucune chaîne")
        }
        val epgUrls = header?.epgUrls.orEmpty()
        writer.commit(epgUrls.firstOrNull())
        onProgress(writer.total)
        return Result(writer.channels, writer.movies, writer.series, epgUrls, parsed.reachedEntryLimit)
    }

    companion object {
        private const val PROGRESS_EVERY = 2_000

        fun epgKey(e: M3uEntry): String = CatalogWriter.epgKey(e)

        fun extensionOf(url: String): String? {
            val path = url.substringBefore('?').substringBefore('#')
            val name = path.substringAfterLast('/')
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return null
            return name.substring(dot + 1).lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
        }
    }
}

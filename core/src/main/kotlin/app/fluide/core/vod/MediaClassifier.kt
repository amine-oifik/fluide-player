package app.fluide.core.vod

import app.fluide.core.m3u.M3uEntry
import app.fluide.core.m3u.StreamKind

enum class MediaKind { LIVE, MOVIE, EPISODE }

/** "Breaking Bad S01 E02 - Cat's in the Bag" -> series "Breaking Bad", season 1, episode 2. */
data class EpisodeName(val series: String, val season: Int, val episode: Int, val title: String?)

/**
 * Sorts the entries of an M3U playlist into live channels, movies and series episodes, the way
 * IPTV players present them: provider playlists (`get.php?type=m3u_plus`) put `/movie/` and `/series/`
 * in the URL; other playlists are recognised by file extension and episode numbering in the name.
 */
object MediaClassifier {

    fun classify(entry: M3uEntry): MediaKind {
        val path = entry.url.substringBefore('?').lowercase()
        if ("/series/" in path) return MediaKind.EPISODE
        if ("/movie/" in path || "/movies/" in path || "/vod/" in path) {
            return if (parseEpisode(entry.name) != null) MediaKind.EPISODE else MediaKind.MOVIE
        }
        if ("/live/" in path) return MediaKind.LIVE
        if (entry.kind == StreamKind.PROGRESSIVE && !isAudio(path)) {
            return if (parseEpisode(entry.name) != null) MediaKind.EPISODE else MediaKind.MOVIE
        }
        return MediaKind.LIVE
    }

    private fun isAudio(path: String): Boolean =
        path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".m4a") || path.endsWith(".flac") || path.endsWith(".ogg")

    /**
     * Recognises `S01E02`, `S01 E02`, `S1.E2`, `1x02` and `Saison 1 Episode 2` / `Season 1 Episode 2`
     * (case-insensitive). The series name is what precedes the marker, cleaned of separators.
     */
    fun parseEpisode(name: String): EpisodeName? {
        val text = name.trim()
        val lower = text.lowercase()
        var i = 0
        while (i < lower.length) {
            val match = matchAt(lower, i)
            if (match != null) {
                val series = cleanSeries(text.substring(0, i))
                if (series.isEmpty()) return null
                val rest = text.substring(match.end).trim().trimStart('-', '–', ':', '.', '|', ' ').trim()
                return EpisodeName(series, match.season, match.episode, rest.ifEmpty { null })
            }
            i++
        }
        return null
    }

    private class Match(val season: Int, val episode: Int, val end: Int)

    private fun matchAt(s: String, start: Int): Match? {
        // Must start a word: "Class S01E02" yes, "ClassS01E02" no (avoids matching inside words).
        if (start > 0 && s[start - 1].isLetterOrDigit()) return null
        sxe(s, start)?.let { return it }
        crossed(s, start)?.let { return it }
        return worded(s, start)
    }

    /** s01e02, s01 e02, s1.e2, s01-e02. */
    private fun sxe(s: String, start: Int): Match? {
        if (s[start] != 's') return null
        var i = start + 1
        val season = digits(s, i, 3) ?: return null
        i += season.second
        while (i < s.length && (s[i] == ' ' || s[i] == '.' || s[i] == '-' || s[i] == '_')) i++
        if (i >= s.length || (s[i] != 'e' && s[i] != 'x')) return null
        i++
        if (i < s.length && s[i] == 'p') i++ // "S01EP02"
        while (i < s.length && s[i] == ' ') i++
        val episode = digits(s, i, 4) ?: return null
        i += episode.second
        if (i < s.length && s[i].isLetterOrDigit() && !s[i].isLetter()) return null
        return Match(season.first, episode.first, i)
    }

    /** 1x02 */
    private fun crossed(s: String, start: Int): Match? {
        val season = digits(s, start, 2) ?: return null
        var i = start + season.second
        if (i >= s.length || s[i] != 'x') return null
        i++
        val episode = digits(s, i, 3) ?: return null
        if (episode.second < 2) return null
        i += episode.second
        if (i < s.length && s[i].isLetterOrDigit()) return null
        return Match(season.first, episode.first, i)
    }

    /** "saison 1 episode 2", "season 1 ep 2", "saison 1 épisode 2". */
    private fun worded(s: String, start: Int): Match? {
        val seasonWord = listOf("saison", "season", "temporada", "staffel").firstOrNull { s.startsWith(it, start) } ?: return null
        var i = start + seasonWord.length
        while (i < s.length && s[i] == ' ') i++
        val season = digits(s, i, 3) ?: return null
        i += season.second
        while (i < s.length && (s[i] == ' ' || s[i] == '-' || s[i] == ',' || s[i] == ':')) i++
        val episodeWord = listOf("episode", "épisode", "episodio", "folge", "ep.", "ep", "e").firstOrNull { s.startsWith(it, i) } ?: return null
        i += episodeWord.length
        while (i < s.length && s[i] == ' ') i++
        val episode = digits(s, i, 4) ?: return null
        return Match(season.first, episode.first, i + episode.second)
    }

    /** (value, length) of up to [max] digits at [start]. */
    private fun digits(s: String, start: Int, max: Int): Pair<Int, Int>? {
        var i = start
        var value = 0
        while (i < s.length && i - start < max && s[i].isDigit()) {
            value = value * 10 + (s[i] - '0')
            i++
        }
        return if (i == start) null else value to (i - start)
    }

    private fun cleanSeries(prefix: String): String =
        prefix.trim().trimEnd('-', '–', ':', '.', '|', '_', '(', '[').trim().replace('.', ' ').replace('_', ' ')
            .replace(Regex("\\s+"), " ").trim()
}

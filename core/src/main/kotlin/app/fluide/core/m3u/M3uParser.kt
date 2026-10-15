package app.fluide.core.m3u

import app.fluide.core.io.CharReader
import app.fluide.core.io.LineReader

/**
 * Streaming M3U / M3U8 playlist parser.
 *
 * - One pass, one reusable line buffer: a 100 MB playlist is never held in memory.
 * - No regular expressions: attributes are scanned by hand (quoted values may contain commas).
 * - Entries are pushed to [parse]'s callback so the caller can write them to SQLite in batches.
 * - Hard limits on line length and entry count: a malformed file cannot freeze or crash the import.
 */
class M3uParser(
    private val maxEntries: Int = 500_000,
    private val maxLineLength: Int = 64 * 1024,
) {

    fun parse(
        source: CharReader,
        onHeader: (M3uHeader) -> Unit = {},
        onEntry: (M3uEntry) -> Unit,
    ): M3uParseResult {
        val reader = LineReader(source, maxLineLength = maxLineLength)
        val pool = HashMap<String, String>(256)
        var header: M3uHeader? = null
        var info: ExtInf? = null
        var extGroup: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var drmScheme: String? = null
        var drmLicense: String? = null
        var count = 0
        var reachedLimit = false
        var firstLine = true

        while (true) {
            var raw = reader.readLine() ?: break
            if (firstLine) {
                firstLine = false
                if (raw.isNotEmpty() && raw[0] == '﻿') raw = raw.substring(1)
            }
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line[0] == '#') {
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        header = parseHeader(line).also(onHeader)
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> info = parseExtInf(line, pool)
                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        extGroup = line.substring(8).trim().ifEmpty { null }?.let { pool.intern(it) }
                    line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                        val option = line.substring(11)
                        val key = option.substringBefore('=').trim().lowercase()
                        val value = option.substringAfter('=', "").trim().ifEmpty { null }
                        when (key) {
                            "http-user-agent" -> userAgent = value
                            "http-referrer", "http-referer" -> referrer = value
                        }
                    }
                    line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                        val property = line.substring(10)
                        val key = property.substringBefore('=').trim().lowercase()
                        val value = property.substringAfter('=', "").trim().ifEmpty { null }
                        when (key) {
                            "inputstream.adaptive.license_type" -> drmScheme = value
                            "inputstream.adaptive.license_key" -> drmLicense = value?.substringBefore('|')
                        }
                    }
                }
                continue
            }

            // A non-comment line is the stream URL that closes the current entry.
            if (count >= maxEntries) {
                reachedLimit = true
                break
            }
            val attrs = info?.attributes ?: emptyMap()
            val title = info?.title?.ifBlank { null }
            val tvgName = attrs["tvg-name"]?.ifBlank { null }
            val catchupMode = (attrs["catchup"] ?: attrs["catchup-type"])?.ifBlank { null }
            val catchupDays = (attrs["catchup-days"] ?: attrs["timeshift"])?.trim()?.toIntOrNull() ?: 0
            onEntry(
                M3uEntry(
                    index = count,
                    name = title ?: tvgName ?: fallbackName(line),
                    url = line,
                    tvgId = attrs["tvg-id"]?.trim()?.ifEmpty { null },
                    tvgName = tvgName,
                    logoUrl = (attrs["tvg-logo"] ?: attrs["logo"])?.trim()?.ifEmpty { null },
                    group = attrs["group-title"]?.trim()?.ifEmpty { null }?.let { pool.intern(it) } ?: extGroup,
                    channelNumber = (attrs["tvg-chno"] ?: attrs["channel-number"])?.trim()?.toIntOrNull(),
                    durationSeconds = info?.durationSeconds ?: -1,
                    userAgent = userAgent ?: attrs["user-agent"]?.ifBlank { null },
                    referrer = referrer ?: attrs["referrer"]?.ifBlank { null },
                    catchup = if (catchupMode != null || catchupDays > 0) {
                        CatchupInfo(catchupMode ?: "default", catchupDays, attrs["catchup-source"]?.ifBlank { null })
                    } else {
                        null
                    },
                    drm = drmScheme?.let { DrmInfo(it, drmLicense) },
                    kind = StreamKind.infer(line),
                ),
            )
            count++
            info = null
            extGroup = null
            userAgent = null
            referrer = null
            drmScheme = null
            drmLicense = null
        }
        return M3uParseResult(header, count, reader.truncatedLines, reachedLimit)
    }

    private class ExtInf(val durationSeconds: Int, val attributes: Map<String, String>, val title: String)

    private fun parseHeader(line: String): M3uHeader {
        val attributes = HashMap<String, String>()
        parseAttributes(line, 7, attributes, stopAtComma = false)
        val epgUrls = listOfNotNull(attributes["url-tvg"], attributes["x-tvg-url"], attributes["tvg-url"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        return M3uHeader(epgUrls, attributes)
    }

    private fun parseExtInf(line: String, pool: HashMap<String, String>): ExtInf {
        var i = 8 // after "#EXTINF:"
        val n = line.length
        while (i < n && line[i] == ' ') i++
        val durationStart = i
        while (i < n && (line[i].isDigit() || line[i] == '-' || line[i] == '+' || line[i] == '.')) i++
        val duration = line.substring(durationStart, i).toDoubleOrNull()?.toInt() ?: -1
        val attributes = HashMap<String, String>(8)
        val titleStart = parseAttributes(line, i, attributes, stopAtComma = true)
        val title = if (titleStart < n) line.substring(titleStart).trim() else ""
        attributes["group-title"]?.let { attributes["group-title"] = pool.intern(it) }
        return ExtInf(duration, attributes, title)
    }

    /**
     * Scans `key="value"`, `key='value'` and `key=value` pairs starting at [from].
     * Returns the index right after the comma that starts the title, or the line length.
     */
    private fun parseAttributes(line: String, from: Int, out: MutableMap<String, String>, stopAtComma: Boolean): Int {
        var i = from
        val n = line.length
        while (i < n) {
            val c = line[i]
            if (c == ',' && stopAtComma) return i + 1
            if (c == ' ' || c == '\t' || c == ',') {
                i++
                continue
            }
            val keyStart = i
            while (i < n && line[i] != '=' && line[i] != ' ' && line[i] != '\t' && line[i] != ',') i++
            val key = line.substring(keyStart, i).lowercase()
            if (i < n && line[i] == '=') {
                i++
                val value: String
                if (i < n && (line[i] == '"' || line[i] == '\'')) {
                    val quote = line[i++]
                    val valueStart = i
                    while (i < n && line[i] != quote) i++
                    value = line.substring(valueStart, i)
                    if (i < n) i++ // closing quote
                } else {
                    val valueStart = i
                    while (i < n && line[i] != ' ' && line[i] != '\t' && line[i] != ',') i++
                    value = line.substring(valueStart, i)
                }
                if (key.isNotEmpty()) out[key] = value
            } else if (i == keyStart) {
                i++ // defensive: always make progress
            }
        }
        return n
    }

    private fun fallbackName(url: String): String =
        url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifEmpty { url }

    private fun HashMap<String, String>.intern(value: String): String = getOrPut(value) { value }
}

package app.fluide.core.m3u

import app.fluide.core.ChunkedCharReader
import app.fluide.core.io.StringCharReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz,http://epg2.example/a.xml" tvg-shift="0"
        #EXTINF:-1 tvg-id="tf1.fr" tvg-name="TF1 HD" tvg-logo="http://logo/tf1.png" group-title="France, Nationales" tvg-chno="1",TF1 HD
        #EXTVLCOPT:http-user-agent=FluideTest/1.0
        #EXTVLCOPT:http-referrer=http://ref.example/
        http://cdn.example/live/tf1/index.m3u8
        #EXTINF:-1 tvg-id='arte.fr' group-title=Docs catchup="default" catchup-days="7" catchup-source="?utc={utc}",Arte
        http://cdn.example/live/arte.ts?token=abc
        #EXTINF:0,Radio sans attributs
        #EXTGRP:Radios
        http://radio.example/stream
        #EXTINF:-1 tvg-id="canal.fr",Canal+
        #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
        #KODIPROP:inputstream.adaptive.license_key=https://license.example/wv|Content-Type=application/octet-stream
        https://cdn.example/canal/manifest.mpd
    """.trimIndent()

    private fun parseAll(text: String, parser: M3uParser = M3uParser(), chunk: Int = 0): Pair<M3uParseResult, List<M3uEntry>> {
        val entries = ArrayList<M3uEntry>()
        val source = if (chunk > 0) ChunkedCharReader(text, chunk) else StringCharReader(text)
        val result = parser.parse(source) { entries += it }
        return result to entries
    }

    @Test
    fun parsesHeaderAndAttributes() {
        val (result, entries) = parseAll(sample)
        assertEquals(4, result.entries)
        assertEquals(listOf("http://epg.example/guide.xml.gz", "http://epg2.example/a.xml"), result.header!!.epgUrls)

        val tf1 = entries[0]
        assertEquals("TF1 HD", tf1.name)
        assertEquals("tf1.fr", tf1.tvgId)
        assertEquals("http://logo/tf1.png", tf1.logoUrl)
        assertEquals("France, Nationales", tf1.group) // comma inside quotes is not the title separator
        assertEquals(1, tf1.channelNumber)
        assertEquals("FluideTest/1.0", tf1.userAgent)
        assertEquals("http://ref.example/", tf1.referrer)
        assertEquals(StreamKind.HLS, tf1.kind)
        assertEquals(-1, tf1.durationSeconds)
    }

    @Test
    fun handlesSingleQuotesUnquotedValuesAndCatchup() {
        val (_, entries) = parseAll(sample)
        val arte = entries[1]
        assertEquals("arte.fr", arte.tvgId)
        assertEquals("Docs", arte.group)
        assertEquals("Arte", arte.name)
        assertEquals(StreamKind.MPEG_TS, arte.kind)
        assertEquals("default", arte.catchup!!.mode)
        assertEquals(7, arte.catchup!!.days)
        assertEquals("?utc={utc}", arte.catchup!!.source)
        assertNull("options must not leak to the next entry", arte.userAgent)
    }

    @Test
    fun usesExtGrpAndDrmProperties() {
        val (_, entries) = parseAll(sample)
        assertEquals("Radios", entries[2].group)
        assertEquals(0, entries[2].durationSeconds)
        val canal = entries[3]
        assertEquals("com.widevine.alpha", canal.drm!!.scheme)
        assertEquals("https://license.example/wv", canal.drm!!.licenseUrl)
        assertEquals(StreamKind.DASH, canal.kind)
    }

    @Test
    fun sameResultWithTinyReadChunks() {
        val (_, whole) = parseAll(sample)
        for (chunk in listOf(1, 2, 3, 7, 13)) {
            val (_, chunked) = parseAll(sample, chunk = chunk)
            assertEquals("chunk=$chunk", whole, chunked)
        }
    }

    @Test
    fun toleratesBomCrLfMissingHeaderAndBlankLines() {
        val text = "﻿#EXTINF:-1,One\r\nhttp://a/1.ts\r\n\r\n#EXTINF:-1,Two\rhttp://a/2.ts"
        val (result, entries) = parseAll(text)
        assertNull(result.header)
        assertEquals(listOf("One", "Two"), entries.map { it.name })
        assertEquals(listOf("http://a/1.ts", "http://a/2.ts"), entries.map { it.url })
    }

    @Test
    fun urlWithoutExtinfGetsFallbackName() {
        val (_, entries) = parseAll("#EXTM3U\nhttp://host/path/channel42.m3u8?x=1\n")
        assertEquals("channel42.m3u8", entries.single().name)
    }

    @Test
    fun titleFallsBackToTvgName() {
        val (_, entries) = parseAll("#EXTINF:-1 tvg-name=\"Fallback\",\nhttp://a/b.ts")
        assertEquals("Fallback", entries.single().name)
    }

    @Test
    fun stopsAtEntryLimit() {
        val text = (1..10).joinToString("\n") { "#EXTINF:-1,C$it\nhttp://a/$it.ts" }
        val (result, entries) = parseAll(text, M3uParser(maxEntries = 3))
        assertEquals(3, entries.size)
        assertTrue(result.reachedEntryLimit)
    }

    @Test
    fun truncatesAbsurdlyLongLinesInsteadOfAllocating() {
        val longName = "x".repeat(200_000)
        val (result, entries) = parseAll("#EXTINF:-1,$longName\nhttp://a/1.ts", M3uParser(maxLineLength = 1_000))
        assertEquals(1, entries.size)
        assertTrue(entries.single().name.length < 1_000)
        assertEquals(1, result.truncatedLines)
        assertFalse(result.reachedEntryLimit)
    }

    @Test
    fun internsGroupNames() {
        val text = (1..3).joinToString("\n") { "#EXTINF:-1 group-title=\"Sport\",S$it\nhttp://a/$it.ts" }
        val (_, entries) = parseAll(text)
        assertTrue(entries[0].group === entries[1].group && entries[1].group === entries[2].group)
    }

    @Test
    fun infersStreamKinds() {
        assertEquals(StreamKind.HLS, StreamKind.infer("http://h/play?type=m3u8&id=1"))
        assertEquals(StreamKind.RTSP, StreamKind.infer("rtsp://cam.local/stream"))
        assertEquals(StreamKind.UDP, StreamKind.infer("udp://@239.0.0.1:1234"))
        assertEquals(StreamKind.PROGRESSIVE, StreamKind.infer("http://h/movie.MKV"))
        assertEquals(StreamKind.UNKNOWN, StreamKind.infer("http://h/live/user/pass/123"))
    }
}

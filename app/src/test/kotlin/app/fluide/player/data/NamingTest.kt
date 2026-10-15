package app.fluide.player.data

import app.fluide.core.m3u.M3uEntry
import app.fluide.core.m3u.StreamKind
import app.fluide.player.data.importer.PlaylistImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NamingTest {

    private fun entry(name: String, tvgId: String? = null, tvgName: String? = null) = M3uEntry(
        index = 0, name = name, url = "http://h/1.ts", tvgId = tvgId, tvgName = tvgName, logoUrl = null,
        group = null, channelNumber = null, durationSeconds = -1, userAgent = null, referrer = null,
        catchup = null, drm = null, kind = StreamKind.MPEG_TS,
    )

    @Test
    fun qualityTagsAreIgnoredForBackupMatching() {
        assertEquals("fr tf1", ChannelRepository.baseName("fr tf1 fhd backup"))
        assertEquals("fr tf1", ChannelRepository.baseName("fr tf1 hd"))
        assertEquals("canal sport 1", ChannelRepository.baseName("canal sport 1 hevc 50fps"))
    }

    @Test
    fun searchBecomesPrefixQueryOnNormalisedWords() {
        assertEquals("tele* mat*", ChannelRepository.ftsQuery("  Télé-Mat "))
        assertEquals("tf1*", ChannelRepository.ftsQuery("TF1"))
        assertNull(ChannelRepository.ftsQuery(" -- "))
    }

    @Test
    fun epgKeyPrefersTvgIdThenName() {
        assertEquals("tf1.fr", PlaylistImporter.epgKey(entry("TF1 HD", tvgId = " TF1.fr ")))
        assertEquals("n:france 2", PlaylistImporter.epgKey(entry("France 2 HD", tvgName = "France 2")))
        assertEquals("n:arte hd", PlaylistImporter.epgKey(entry("Arte HD", tvgId = "")))
    }
}

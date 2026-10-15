package app.fluide.core.xmltv

import app.fluide.core.ChunkedCharReader
import app.fluide.core.io.StringCharReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class XmltvTimeTest {

    private fun reference(iso: String): Long = OffsetDateTime.parse(iso).toEpochSecond()

    @Test
    fun parsesWithOffsets() {
        assertEquals(reference("2026-10-15T19:30:00+02:00"), XmltvTime.parseEpochSeconds("20261015193000 +0200"))
        assertEquals(reference("2026-10-15T19:30:00-05:30"), XmltvTime.parseEpochSeconds("20261015193000 -0530"))
        assertEquals(reference("2026-10-15T19:30:00+01:00"), XmltvTime.parseEpochSeconds("20261015193000 +01:00"))
        assertEquals(reference("2026-10-15T19:30:00Z"), XmltvTime.parseEpochSeconds("20261015193000"))
        assertEquals(reference("2026-10-15T19:30:00Z"), XmltvTime.parseEpochSeconds("20261015193000 UTC"))
    }

    @Test
    fun parsesPartialTimestamps() {
        assertEquals(reference("2026-10-15T19:30:00Z"), XmltvTime.parseEpochSeconds("202610151930"))
        assertEquals(reference("2026-10-15T00:00:00Z"), XmltvTime.parseEpochSeconds("20261015"))
    }

    @Test
    fun matchesJavaTimeAcrossCenturiesAndLeapDays() {
        val samples = listOf("1970-01-01T00:00:00Z", "2000-02-29T23:59:59Z", "2024-02-29T12:00:00Z", "1999-12-31T23:59:59Z", "2100-03-01T00:00:00Z")
        for (iso in samples) {
            val t = OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC)
            val text = "%04d%02d%02d%02d%02d%02d +0000".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
            assertEquals(iso, t.toEpochSecond(), XmltvTime.parseEpochSeconds(text))
        }
    }

    @Test
    fun rejectsInvalidInput() {
        assertEquals(XmltvTime.INVALID, XmltvTime.parseEpochSeconds(""))
        assertEquals(XmltvTime.INVALID, XmltvTime.parseEpochSeconds("2026101"))
        assertEquals(XmltvTime.INVALID, XmltvTime.parseEpochSeconds("20261315193000"))
        assertEquals(XmltvTime.INVALID, XmltvTime.parseEpochSeconds("20261015193000 +2"))
    }
}

class XmltvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd" [ <!ENTITY test "x"> ]>
        <!-- a comment with <tags> and -->
        <tv generator-info-name="test">
          <channel id="tf1.fr">
            <display-name lang="en">TF1 (EN)</display-name>
            <display-name lang="fr">TF1</display-name>
            <icon src="http://logo/tf1.png?a=1&amp;b=2"/>
          </channel>
          <channel id="unknown.fr"><display-name>Unknown</display-name></channel>
          <programme start="20261015190000 +0200" stop="20261015200000 +0200" channel="tf1.fr">
            <title lang="en">The News</title>
            <title lang="fr">Le Journal &amp; la météo</title>
            <sub-title>Édition du soir</sub-title>
            <desc lang="fr"><![CDATA[Résumé <avec> balises & symboles]]></desc>
            <category lang="fr">Information</category>
            <category lang="fr">Société</category>
          </programme>
          <programme start="20261015200000 +0200" stop="20261015210000 +0200" channel="unknown.fr">
            <title>Should be skipped</title>
          </programme>
          <programme start="20261015210000 +0200" channel="tf1.fr">
            <title>Sans fin &#233;&#x1F600; &unknown; AT&T</title>
          </programme>
          <programme start="20261020210000 +0200" stop="20261020220000 +0200" channel="tf1.fr">
            <title>Outside window</title>
          </programme>
        </tv>
    """.trimIndent()

    private val windowStart = OffsetDateTime.parse("2026-10-15T00:00:00Z").toEpochSecond()
    private val windowEnd = OffsetDateTime.parse("2026-10-17T00:00:00Z").toEpochSecond()

    private fun parse(chunk: Int = 0): Triple<XmltvParseResult, List<XmltvChannel>, List<XmltvProgramme>> {
        val channels = ArrayList<XmltvChannel>()
        val programmes = ArrayList<XmltvProgramme>()
        val parser = XmltvParser(
            acceptChannel = { it == "tf1.fr" },
            windowStartEpochSec = windowStart,
            windowEndEpochSec = windowEnd,
            preferredLanguage = "fr",
            bufferSize = 64,
        )
        val source = if (chunk > 0) ChunkedCharReader(xml, chunk) else StringCharReader(xml)
        val result = parser.parse(source, { channels += it }) { programmes += it }
        return Triple(result, channels, programmes)
    }

    @Test
    fun extractsAcceptedChannelsAndProgrammes() {
        val (result, channels, programmes) = parse()
        assertEquals(listOf(XmltvChannel("tf1.fr", "TF1", "http://logo/tf1.png?a=1&b=2")), channels)
        assertEquals(2, result.programmes)
        assertEquals(2, result.skippedProgrammes)

        val news = programmes[0]
        assertEquals("tf1.fr", news.channelId)
        assertEquals(OffsetDateTime.parse("2026-10-15T19:00:00+02:00").toEpochSecond(), news.startEpochSec)
        assertEquals(news.startEpochSec + 3_600, news.stopEpochSec)
        assertEquals("Le Journal & la météo", news.title) // preferred language wins
        assertEquals("Édition du soir", news.subTitle)
        assertEquals("Résumé <avec> balises & symboles", news.description) // CDATA kept verbatim
        assertEquals("Information", news.category) // first category only
    }

    @Test
    fun decodesEntitiesAndDefaultsMissingStop() {
        val (_, _, programmes) = parse()
        val last = programmes[1]
        assertEquals("Sans fin é😀 &unknown; AT&T", last.title)
        assertEquals(last.startEpochSec + 1_800, last.stopEpochSec)
        assertNull(last.description)
    }

    @Test
    fun sameResultWhateverTheReadChunks() {
        val reference = parse()
        for (chunk in listOf(1, 2, 3, 5, 11)) {
            val chunked = parse(chunk)
            assertEquals("chunk=$chunk", reference.second, chunked.second)
            assertEquals("chunk=$chunk", reference.third, chunked.third)
        }
    }

    @Test
    fun programmeFilterCanDifferFromChannelFilter() {
        val channels = ArrayList<XmltvChannel>()
        val programmes = ArrayList<XmltvProgramme>()
        XmltvParser(
            acceptChannel = { true },
            acceptProgrammeChannel = { it == "tf1.fr" },
            windowStartEpochSec = windowStart,
            windowEndEpochSec = windowEnd,
        ).parse(StringCharReader(xml), { channels += it }) { programmes += it }
        assertEquals(listOf("tf1.fr", "unknown.fr"), channels.map { it.id })
        assertEquals(listOf("tf1.fr", "tf1.fr"), programmes.map { it.channelId })
    }

    @Test
    fun survivesTruncatedDocument() {
        val truncated = xml.substring(0, xml.indexOf("<sub-title>") + 5)
        val programmes = ArrayList<XmltvProgramme>()
        XmltvParser().parse(StringCharReader(truncated)) { programmes += it }
        assertEquals(0, programmes.size)
    }

    @Test
    fun capsTextLength() {
        val long = "a".repeat(10_000)
        val doc = "<tv><programme start=\"20261015190000\" stop=\"20261015200000\" channel=\"c\"><title>$long</title><desc>$long</desc></programme></tv>"
        val programmes = ArrayList<XmltvProgramme>()
        XmltvParser(maxTitleLength = 50, maxDescriptionLength = 100).parse(StringCharReader(doc)) { programmes += it }
        assertEquals(50, programmes.single().title.length)
        assertEquals(100, programmes.single().description!!.length)
    }
}

package app.fluide.core

import app.fluide.core.catchup.CatchupUrl
import app.fluide.core.json.JsonReader
import app.fluide.core.json.JsonToken
import app.fluide.core.json.forEachElement
import app.fluide.core.json.forEachMember
import app.fluide.core.m3u.M3uEntry
import app.fluide.core.m3u.StreamKind
import app.fluide.core.vod.MediaClassifier
import app.fluide.core.vod.MediaKind
import app.fluide.core.xmltv.XmltvTime
import app.fluide.core.xtream.XtreamParser
import app.fluide.core.xtream.XtreamUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XtreamTest {

    private fun reader(json: String, chunk: Int = 7) = JsonReader(ChunkedCharReader(json, chunk), bufferSize = 16)

    @Test
    fun `json reader handles escapes, numbers, nulls and nesting across buffer boundaries`() {
        val r = reader("""{"a":"x\"y\\zé\n","b":12,"c":null,"d":[1,{"e":true}],"f":-3.5e2}""")
        val seen = LinkedHashMap<String, String?>()
        r.forEachMember { name ->
            when (name) {
                "d" -> {
                    r.skipValue()
                    seen[name] = "skipped"
                }
                else -> seen[name] = r.nextString()
            }
        }
        assertEquals("x\"y\\zé\n", seen["a"])
        assertEquals("12", seen["b"])
        assertNull(seen["c"])
        assertEquals("skipped", seen["d"])
        assertEquals("-3.5e2", seen["f"])
        assertEquals(JsonToken.END, r.peek())
    }

    @Test
    fun `json reader is lenient with sloppy panels`() {
        // Doubled comma, number as string, object where a scalar was expected.
        val r = reader("""[{"n":"7",,"o":[]}, {"n":8}]""")
        val values = ArrayList<Long?>()
        r.forEachElement {
            r.forEachMember { name -> if (name == "n") values += r.nextLongOrNull() else assertNull(r.nextString()) }
        }
        assertEquals(listOf(7L, 8L), values)
        assertEquals(3_000L, JsonReader.parseLong("3e3"))
    }

    @Test
    fun `account and categories`() {
        val account = XtreamParser.account(
            reader(
                """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1798761600","is_trial":"0",
                "active_cons":"0","max_connections":"2","allowed_output_formats":["m3u8","ts"]},
                "server_info":{"url":"x","timezone":"Europe/Paris","timestamp_now":1790000000}}""",
            ),
        )
        assertTrue(account.authenticated)
        assertEquals(1_798_761_600L, account.expiresAtSec)
        assertEquals(2, account.maxConnections)
        assertEquals(listOf("m3u8", "ts"), account.allowedFormats)
        assertEquals("Europe/Paris", account.serverTimezone)

        val refused = XtreamParser.account(reader("""{"user_info":{"auth":0}}"""))
        assertFalse(refused.authenticated)

        val categories = XtreamParser.categories(reader("""[{"category_id":"1","category_name":"FR | Sport","parent_id":0},{"category_id":2,"category_name":""}]"""))
        assertEquals(2, categories.size)
        assertEquals("FR | Sport", categories[0].name)
        assertEquals("2", categories[1].name)
    }

    @Test
    fun `live, vod and series lists stream item by item`() {
        val live = ArrayList<String>()
        XtreamParser.liveStreams(
            reader(
                """[{"num":1,"name":"TF1 HD","stream_type":"live","stream_id":101,"stream_icon":"http://l/tf1.png",
                "epg_channel_id":"TF1.fr","category_id":"1","tv_archive":1,"tv_archive_duration":"7"},
                {"num":2,"name":"","stream_id":102}]""",
            ),
        ) { live += "${it.name}/${it.streamId}/${it.epgChannelId}/${it.hasArchive}/${it.archiveDays}" }
        assertEquals(listOf("TF1 HD/101/TF1.fr/true/7"), live)

        val movies = ArrayList<String>()
        XtreamParser.vodStreams(
            reader("""[{"name":"Dune (2021)","stream_id":"5","stream_icon":"","rating":"7.9","container_extension":"mkv","category_id":"9"}]"""),
        ) { movies += "${it.name}/${it.streamId}/${it.icon}/${it.rating}/${it.extension}/${it.year}" }
        assertEquals(listOf("Dune (2021)/5/null/7.9/mkv/2021"), movies)

        val series = ArrayList<String>()
        XtreamParser.series(
            reader("""[{"name":"Lupin","series_id":42,"cover":"https://c/l.jpg","releaseDate":"2021-01-08","rating":"8"}]"""),
        ) { series += "${it.name}/${it.seriesId}/${it.year}" }
        assertEquals(listOf("Lupin/42/2021"), series)
    }

    @Test
    fun `series info with episodes keyed by season, details with backdrop arrays`() {
        val info = XtreamParser.seriesInfo(
            reader(
                """{"seasons":[],"info":{"name":"Lupin","plot":"Assane...","cast":"Omar Sy","backdrop_path":["https://b/1.jpg"],
                "episode_run_time":"45"},"episodes":{"2":[{"id":"202","episode_num":1,"title":"E1","container_extension":"mp4","season":2}],
                "1":[{"id":"102","episode_num":2,"season":1,"info":{"duration":"00:44:10"}},{"id":"101","episode_num":1,"season":1,
                "info":{"duration_secs":2700,"movie_image":"https://i/1.jpg"}}]}}""",
            ),
        )
        assertEquals("Lupin", info.details.title)
        assertEquals("https://b/1.jpg", info.details.backdrop)
        assertEquals(45 * 60L, info.details.durationSec)
        assertEquals(listOf(1, 2), info.seasons.map { it.number })
        assertEquals(listOf(101L, 102L, 202L), info.episodes.map { it.id })
        assertEquals(2_650L, info.episodes[1].durationSec)

        val vod = XtreamParser.vodInfo(
            reader("""{"info":[],"movie_data":{"stream_id":5,"name":"Dune","container_extension":"mkv"}}"""),
        )
        assertEquals("Dune", vod.details.title)
        assertEquals("mkv", vod.extension)
    }

    @Test
    fun `urls`() {
        val urls = XtreamUrls(" myhost.tv:8080/ ", "us er", "p@ss")
        assertEquals("http://myhost.tv:8080", urls.base)
        assertEquals("http://myhost.tv:8080/player_api.php?username=us%20er&password=p%40ss&action=get_live_streams", urls.api("get_live_streams"))
        assertEquals("http://myhost.tv:8080/live/us%20er/p%40ss/101.ts", urls.live(101, "ts"))
        assertEquals("http://myhost.tv:8080/movie/us%20er/p%40ss/5.mkv", urls.movie(5, "mkv"))
        // 2026-10-15 20:30 UTC, server at UTC+2 -> 22-30 local.
        val start = XmltvTime.parseEpochSeconds("20261015203000 +0000")
        assertEquals("http://myhost.tv:8080/timeshift/us%20er/p%40ss/60/2026-10-15:22-30/101.ts", urls.timeshift(101, start, 60, 7_200))

        val parsed = XtreamUrls.fromM3uLink("http://h:80/get.php?username=a%2Bb&password=c&type=m3u_plus&output=ts")
        assertEquals(Triple("http://h:80", "a+b", "c"), parsed)
        assertNull(XtreamUrls.fromM3uLink("http://h/playlist.m3u"))
        assertEquals("http://h:80", XtreamUrls.normalizeServer("http://h:80/player_api.php?username=x"))
    }

    @Test
    fun `catch-up urls`() {
        val start = 1_790_000_000L
        val stop = start + 3_600
        val now = start + 7_200
        assertEquals(
            "http://h/ch.m3u8?utc=$start&lutc=$now",
            CatchupUrl.build("shift", null, "http://h/ch.m3u8", start, stop, now),
        )
        assertEquals(
            "http://h/ch.m3u8?archive=$start&d=60",
            CatchupUrl.build("append", "?archive={utc}&d={duration:60}", "http://h/ch.m3u8", start, stop, now),
        )
        assertEquals(
            "http://h/tv/index-$start-3600.m3u8?token=1",
            CatchupUrl.build("flussonic", null, "http://h/tv/index.m3u8?token=1", start, stop, now),
        )
        assertEquals(
            "http://h:8080/timeshift/u/p/60/${XtreamUrls.formatStart(start)}/101.ts",
            CatchupUrl.build("xc", null, "http://h:8080/live/u/p/101.ts", start, stop, now),
        )
        assertEquals("http://r/2026/09/21-14.ts", CatchupUrl.expand("http://r/\${Y}/{m}/{d}-{H}.ts", start, stop, now))
        assertNull(CatchupUrl.build("default", null, "http://h/ch", start, stop, now))
    }

    @Test
    fun `episode names and m3u classification`() {
        val e = MediaClassifier.parseEpisode("Breaking.Bad.S01E02.Cat's in the Bag")!!
        assertEquals("Breaking Bad", e.series)
        assertEquals(1, e.season)
        assertEquals(2, e.episode)
        assertEquals("Cat's in the Bag", e.title)
        assertEquals(3 to 10, MediaClassifier.parseEpisode("Lupin S03 E10")!!.let { it.season to it.episode })
        assertEquals(2 to 5, MediaClassifier.parseEpisode("Dark 2x05")!!.let { it.season to it.episode })
        assertEquals(1 to 4, MediaClassifier.parseEpisode("Plus belle la vie Saison 1 Episode 4")!!.let { it.season to it.episode })
        assertNull(MediaClassifier.parseEpisode("Fast X (2023)"))
        assertNull(MediaClassifier.parseEpisode("Class S"))

        fun entry(name: String, url: String) = M3uEntry(0, name, url, null, null, null, null, null, -1, null, null, null, null, StreamKind.infer(url))
        assertEquals(MediaKind.LIVE, MediaClassifier.classify(entry("TF1", "http://h/live/u/p/1.ts")))
        assertEquals(MediaKind.LIVE, MediaClassifier.classify(entry("TF1", "http://h/u/p/1")))
        assertEquals(MediaKind.MOVIE, MediaClassifier.classify(entry("Dune", "http://h/movie/u/p/5.mkv")))
        assertEquals(MediaKind.EPISODE, MediaClassifier.classify(entry("Lupin S01 E01", "http://h/series/u/p/9.mp4")))
        assertEquals(MediaKind.MOVIE, MediaClassifier.classify(entry("Holiday video", "http://h/files/a.mp4")))
        assertEquals(MediaKind.LIVE, MediaClassifier.classify(entry("Radio", "http://h/stream.mp3")))
    }
}

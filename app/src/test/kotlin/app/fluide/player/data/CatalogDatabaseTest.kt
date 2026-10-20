package app.fluide.player.data

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import app.fluide.player.Settings
import app.fluide.player.data.backup.BackupManager
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.importer.EpgImporter
import app.fluide.player.data.importer.PlaylistImporter
import app.fluide.player.data.secure.UrlCipher
import app.fluide.player.playback.PlaybackPressure
import kotlinx.coroutines.Job
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Runs the real schema, migrations and queries of the repositories (and of the backup) on SQLite: an
 * SQL typo would otherwise only show up on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CatalogDatabaseTest {

    private lateinit var app: Application
    private lateinit var database: FluideDatabase
    private lateinit var cipher: UrlCipher
    private lateinit var channels: ChannelRepository
    private lateinit var media: MediaRepository
    private var sourceId = 0L

    private val playlist = """
        #EXTM3U url-tvg="http://h/epg.xml"
        #EXTINF:-1 tvg-id="tf1.fr" tvg-chno="1" tvg-logo="http://h/tf1.png" group-title="France" catchup="xc" catchup-days="7",TF1 HD
        http://h:8080/live/u/p/101.ts
        #EXTINF:-1 tvg-id="tf1.fr" group-title="France",TF1 FHD
        http://h:8080/live/u/p/102.ts
        #EXTINF:-1 group-title="XXX Adultes",Late Show
        http://h:8080/live/u/p/103.ts
        #EXTINF:-1 tvg-logo="http://h/dune.jpg" group-title="Films SF",Dune (2021)
        http://h:8080/movie/u/p/5.mkv
        #EXTINF:-1 group-title="Films SF",Arrival 2016
        http://h:8080/movie/u/p/6.mp4
        #EXTINF:-1 tvg-logo="http://h/lupin.jpg" group-title="Séries FR",Lupin S01 E01
        http://h:8080/series/u/p/9.mp4
        #EXTINF:-1 group-title="Séries FR",Lupin S01 E02 Chapitre 2
        http://h:8080/series/u/p/10.mp4
        #EXTINF:-1 group-title="Séries FR",Lupin S02 E01
        http://h:8080/series/u/p/11.mp4
    """.trimIndent()

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        database = FluideDatabase(app)
        cipher = UrlCipher(app)
        channels = ChannelRepository(database, cipher)
        media = MediaRepository(database, cipher)
        sourceId = database.writableDatabase.insertOrThrow(
            "source",
            null,
            ContentValues().apply {
                put("name", "Test")
                put("type", "url")
                put("location", cipher.encrypt("http://h/get.php"))
            },
        )
        val result = PlaylistImporter(database, cipher, PlaybackPressure()).import(sourceId, StringReader(playlist), Job()) { }
        assertEquals(3, result.channels)
        assertEquals(2, result.movies)
        assertEquals(1, result.series)
    }

    @After
    fun tearDown() {
        database.close()
        app.deleteDatabase("fluide.db")
    }

    @Test
    fun channelsListsAndSearch() {
        val all = channels.channelIds(sourceId, ChannelFilter.All)
        assertEquals(3, all.size)
        val adult = setOf("XXX Adultes")
        assertEquals(2, channels.channelIds(sourceId, ChannelFilter.All, adult).size)
        assertEquals(1, channels.channelIds(sourceId, ChannelFilter.Group("XXX Adultes")).size)
        assertEquals(2, channels.channelIds(sourceId, ChannelFilter.Search("tf1"), adult).size)
        assertEquals(1, channels.channelIds(sourceId, ChannelFilter.Catchup).size)
        assertEquals(1, channels.catchupCount(sourceId))
        assertEquals(listOf("France", "XXX Adultes"), channels.groups(sourceId).map { it.name })

        val first = all.first()
        assertTrue(channels.toggleFavorite(sourceId, "TF1 HD"))
        assertEquals(1, channels.channelIds(sourceId, ChannelFilter.Favorites).size)
        channels.recordHistory(sourceId, "TF1 HD")
        assertEquals(listOf(first), channels.channelIds(sourceId, ChannelFilter.Recent).toList())

        val row = channels.rows(all, 0).first()
        assertEquals(7, row.catchupDays)
        assertTrue(row.favorite)
        val playable = assertNotNullAnd(channels.playable(first))
        assertEquals("http://h:8080/live/u/p/101.ts", playable.url)
        assertEquals("xc", playable.catchupMode)
        assertEquals(1, channels.alternates(playable).size)
        // Numbers 1, 2 and 3: no longer number starts with a typed digit.
        assertTrue(channels.numberPrefixes(sourceId).isEmpty())
    }

    @Test
    fun programmesAreStoredAndQueried() {
        val now = System.currentTimeMillis() / 1_000
        channels.storeProgrammes(
            "tf1.fr",
            listOf(
                Programme(now - 3_600, now - 600, "Avant", null, null, null),
                Programme(now - 600, now + 1_800, "Maintenant", null, "Description", null),
                Programme(now + 1_800, now + 3_600, "Après", null, null, null),
            ),
        )
        assertEquals(listOf("Maintenant", "Après"), channels.nowNext("tf1.fr", now).map { it.title })
        assertEquals(3, channels.programmes("tf1.fr", now - 7_200, now + 7_200).size)
        val first = channels.channelIds(sourceId, ChannelFilter.All).first()
        assertEquals("Maintenant", channels.rows(longArrayOf(first), now).first().nowTitle)
    }

    @Test
    fun moviesAndSeries() {
        assertEquals(listOf("Films SF"), media.groups(sourceId, Section.MOVIES).map { it.name })
        for (sort in MediaSort.entries) {
            assertEquals(2, media.ids(sourceId, Section.MOVIES, MediaFilter.All, sort, emptySet()).size)
        }
        assertEquals(2, media.ids(sourceId, Section.MOVIES, MediaFilter.RecentlyAdded, MediaSort.DEFAULT, emptySet()).size)
        val dune = media.ids(sourceId, Section.MOVIES, MediaFilter.Search("dune"), MediaSort.DEFAULT, emptySet())
        assertEquals(1, dune.size)
        val record = assertNotNullAnd(media.record(Section.MOVIES, dune.first()))
        assertEquals("http://h:8080/movie/u/p/5.mkv", record.url)
        assertEquals(2021, record.year)
        assertEquals("mkv", record.extension)

        assertTrue(media.toggleFavorite(sourceId, Section.MOVIES, record.key))
        assertEquals(1, media.ids(sourceId, Section.MOVIES, MediaFilter.Favorites, MediaSort.DEFAULT, emptySet()).size)
        media.saveProgress(sourceId, MediaRepository.KIND_MOVIE, record.key, null, record.name, null, null, null, 60_000, 600_000)
        assertEquals(1, media.ids(sourceId, Section.MOVIES, MediaFilter.ContinueWatching, MediaSort.DEFAULT, emptySet()).size)
        val tile = media.rows(Section.MOVIES, dune).first()
        assertTrue(tile.favorite)
        assertEquals(0.1f, tile.progress, 0.001f)
        assertEquals(60_000L, media.progress(sourceId, MediaRepository.KIND_MOVIE, record.key)?.positionMs)

        val series = media.ids(sourceId, Section.SERIES, MediaFilter.All, MediaSort.NAME_ASC, emptySet())
        assertEquals(1, series.size)
        val lupin = assertNotNullAnd(media.record(Section.SERIES, series.first()))
        assertEquals("Lupin", lupin.name)
        val episodes = media.storedEpisodes(sourceId, lupin.key)
        assertEquals(listOf("s1e1", "s1e2", "s2e1"), episodes.map { it.key })
        assertEquals("Chapitre 2", episodes[1].title)
        media.saveProgress(sourceId, MediaRepository.KIND_EPISODE, "s1e2", lupin.key, lupin.name, null, 1, 2, 100_000, 2_000_000)
        assertEquals("s1e2", media.lastEpisodeKey(sourceId, lupin.key))
        assertEquals(setOf("s1e2"), media.episodeProgress(sourceId, lupin.key).keys)
        assertEquals(1, media.ids(sourceId, Section.SERIES, MediaFilter.ContinueWatching, MediaSort.DEFAULT, emptySet()).size)
        assertEquals(0, media.ids(sourceId, Section.SERIES, MediaFilter.All, MediaSort.DEFAULT, setOf("Séries FR")).size)

        media.setRule(sourceId, Section.SERIES, "Séries FR", CategoryMode.HIDDEN)
        assertEquals(mapOf("Séries FR" to CategoryMode.HIDDEN), media.rules(sourceId, Section.SERIES))
        media.setRule(sourceId, Section.SERIES, "Séries FR", null)
        assertTrue(media.rules(sourceId, Section.SERIES).isEmpty())
        media.clearProgress(sourceId)
        assertFalse(media.isFavorite(sourceId, Section.SERIES, lupin.key))
    }

    @Test
    fun reimportKeepsFavoritesAndProgressKeys() {
        val before = media.ids(sourceId, Section.MOVIES, MediaFilter.All, MediaSort.DEFAULT, emptySet())
        val key = assertNotNullAnd(media.record(Section.MOVIES, before.first())).key
        media.toggleFavorite(sourceId, Section.MOVIES, key)
        PlaylistImporter(database, cipher, PlaybackPressure()).import(sourceId, StringReader(playlist), Job()) { }
        assertEquals(1, media.ids(sourceId, Section.MOVIES, MediaFilter.Favorites, MediaSort.DEFAULT, emptySet()).size)
        assertEquals(3, channels.channelIds(sourceId, ChannelFilter.All).size)
    }

    @Test
    fun version1DatabaseIsMigrated() {
        database.close()
        app.deleteDatabase("fluide.db")
        val path = app.getDatabasePath("fluide.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            for (statement in V1_SCHEMA) db.execSQL(statement)
            db.execSQL("INSERT INTO source(name, type, location) VALUES ('Ancienne', 'url', x'00112233445566778899AABBCCDDEEFF00')")
            db.version = 1
        }
        database = FluideDatabase(app)
        val db = database.readableDatabase
        assertEquals(3, db.version)
        db.rawQuery("SELECT name, movie_count, series_count, username, epg_offset_min FROM source", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Ancienne", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.isNull(3))
            assertEquals(0, c.getInt(4))
        }
        db.rawQuery("SELECT COUNT(*) FROM vod", null).use { c -> assertTrue(c.moveToFirst()) }
        db.rawQuery("SELECT COUNT(*) FROM watch", null).use { c -> assertTrue(c.moveToFirst()) }
    }

    @Test
    fun newDatabaseHasTheGuideShift() {
        assertEquals(3, database.readableDatabase.version)
        database.readableDatabase.rawQuery("SELECT epg_offset_min FROM source WHERE id = $sourceId", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun guideShiftAppliesToImportedAndStoredProgrammes() {
        val format = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun at(sec: Long): String = format.format(Date(sec * 1_000))
        // Next full hour: inside the import window.
        val start = (System.currentTimeMillis() / 3_600_000 + 1) * 3_600
        val guide = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="tf1.fr"><display-name>TF1</display-name></channel>
              <programme start="${at(start)}" stop="${at(start + 3_600)}" channel="tf1.fr"><title>Journal</title></programme>
              <programme start="${at(start + 3_600)}" stop="${at(start + 5_400)}" channel="tf1.fr"><title>Film</title></programme>
            </tv>
        """.trimIndent()
        val importer = EpgImporter(database, PlaybackPressure())
        val result = importer.import(sourceId, StringReader(guide), Job(), 2, 7, offsetMinutes = 90) { }
        assertEquals(2, result.programmes)
        fun stored() = channels.programmes("tf1.fr", start - 86_400, start + 86_400)
        assertEquals(listOf(start + 5_400, start + 9_000), stored().map { it.startSec })
        assertEquals(listOf(start + 9_000, start + 10_800), stored().map { it.stopSec })

        // Back by 90 minutes, then +60: the first programme lands where the second one was (primary key).
        importer.shiftStored(sourceId, -90 * 60L)
        assertEquals(listOf(start, start + 3_600), stored().map { it.startSec })
        importer.shiftStored(sourceId, 60 * 60L)
        assertEquals(listOf(start + 3_600, start + 7_200), stored().map { it.startSec })
        assertEquals(listOf(start + 7_200, start + 9_000), stored().map { it.stopSec })
        assertEquals(listOf("Journal", "Film"), stored().map { it.title })
    }

    @Test
    fun backupRestoresPlaylistsFavoritesRulesAndSettings() {
        database.writableDatabase.execSQL("UPDATE source SET epg_offset_min = 90, user_agent = 'UA' WHERE id = $sourceId")
        assertTrue(channels.toggleFavorite(sourceId, "TF1 HD"))
        val movie = media.ids(sourceId, Section.MOVIES, MediaFilter.All, MediaSort.DEFAULT, emptySet()).first()
        val movieKey = assertNotNullAnd(media.record(Section.MOVIES, movie)).key
        assertTrue(media.toggleFavorite(sourceId, Section.MOVIES, movieKey))
        media.setRule(sourceId, Section.LIVE, "XXX Adultes", CategoryMode.HIDDEN)
        val settings = Settings(app)
        settings.update { it.copy(theme = "rubis", parentalPin = "1234", clock24h = false) }
        val backup = BackupManager(app, database, cipher, settings)
        val document = backup.buildBackup().toString()

        // Same name and location as an existing playlist: never added twice.
        assertEquals(0, backup.restoreBackup(JSONObject(document)))

        database.writableDatabase.execSQL("UPDATE source SET name = 'Renommée' WHERE id = $sourceId")
        settings.update { it.copy(theme = "fluide", parentalPin = "0000", clock24h = true, activeSourceId = sourceId) }
        assertEquals(1, backup.restoreBackup(JSONObject(document)))
        val restored = database.readableDatabase.rawQuery(
            "SELECT id, name, type, location, user_agent, epg_offset_min, updated_at FROM source WHERE id != $sourceId",
            null,
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Test", c.getString(1))
            assertEquals("url", c.getString(2))
            assertEquals("http://h/get.php", cipher.decrypt(c.getBlob(3)))
            assertEquals("UA", c.getString(4))
            assertEquals(90, c.getInt(5))
            assertEquals(0L, c.getLong(6))
            assertFalse(c.moveToNext())
            c.getLong(0)
        }
        assertTrue(media.isFavorite(restored, Section.MOVIES, movieKey))
        assertEquals(mapOf("XXX Adultes" to CategoryMode.HIDDEN), media.rules(restored, Section.LIVE))
        database.readableDatabase.rawQuery("SELECT name FROM favorite WHERE source_id = $restored", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("TF1 HD", c.getString(0))
        }
        assertEquals("rubis", settings.current.theme)
        assertEquals("1234", settings.current.parentalPin)
        assertFalse(settings.current.clock24h)
        assertEquals(sourceId, settings.current.activeSourceId)
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        /** Schema of version 1 (first release), to check the in-place migration. */
        val V1_SCHEMA = listOf(
            """CREATE TABLE source(id INTEGER PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, location BLOB NOT NULL,
                epg_url BLOB, header_epg_url BLOB, user_agent TEXT, max_connections INTEGER NOT NULL DEFAULT 1, etag TEXT,
                last_modified TEXT, epg_etag TEXT, epg_last_modified TEXT, updated_at INTEGER NOT NULL DEFAULT 0,
                epg_updated_at INTEGER NOT NULL DEFAULT 0, channel_count INTEGER NOT NULL DEFAULT 0,
                programme_count INTEGER NOT NULL DEFAULT 0, last_error TEXT)""",
            """CREATE TABLE channel(id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL, position INTEGER NOT NULL, number INTEGER,
                name TEXT NOT NULL, name_norm TEXT NOT NULL, grp TEXT, logo TEXT, epg_key TEXT, url BLOB NOT NULL,
                kind INTEGER NOT NULL, user_agent TEXT, referrer TEXT, drm_scheme TEXT, drm_license BLOB, catchup_mode TEXT,
                catchup_days INTEGER, catchup_source TEXT)""",
            """CREATE TABLE channel_staging(id INTEGER PRIMARY KEY, source_id INTEGER NOT NULL, position INTEGER NOT NULL,
                number INTEGER, name TEXT NOT NULL, name_norm TEXT NOT NULL, grp TEXT, logo TEXT, epg_key TEXT, url BLOB NOT NULL,
                kind INTEGER NOT NULL, user_agent TEXT, referrer TEXT, drm_scheme TEXT, drm_license BLOB, catchup_mode TEXT,
                catchup_days INTEGER, catchup_source TEXT)""",
            "CREATE VIRTUAL TABLE channel_fts USING fts4(content=\"channel\", name_norm, prefix=\"2,3\")",
            """CREATE TABLE programme(epg_key TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL,
                subtitle TEXT, description TEXT, category TEXT, PRIMARY KEY(epg_key, start)) WITHOUT ROWID""",
            "CREATE TABLE favorite(source_id INTEGER NOT NULL, name TEXT NOT NULL, PRIMARY KEY(source_id, name)) WITHOUT ROWID",
        )
    }
}

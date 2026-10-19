package app.fluide.player

import android.app.Application
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.MediaRepository
import app.fluide.player.data.backup.BackupManager
import app.fluide.player.data.SourceManager
import app.fluide.player.data.db.FluideDatabase
import app.fluide.player.data.secure.UrlCipher
import app.fluide.player.device.DeviceProfile
import app.fluide.player.device.DeviceProfiler
import app.fluide.player.net.HttpStack
import app.fluide.player.playback.PlaybackPressure
import app.fluide.player.playback.SourceHealthStore

/**
 * Hand-written dependency graph: a dozen singletons do not need a DI framework, and nothing here is
 * created before it is used (cold start matters on TV boxes).
 */
class AppGraph(val app: Application) {
    val device: DeviceProfile by lazy { DeviceProfiler.profile(app) }
    val settings: Settings by lazy { Settings(app) }
    val http: HttpStack by lazy { HttpStack(app) }
    val cipher: UrlCipher by lazy { UrlCipher(app) }
    val database: FluideDatabase by lazy { FluideDatabase(app) }
    val channels: ChannelRepository by lazy { ChannelRepository(database, cipher) }
    val media: MediaRepository by lazy { MediaRepository(database, cipher) }
    val backup: BackupManager by lazy { BackupManager(app, database, cipher, settings) }
    val health: SourceHealthStore by lazy { SourceHealthStore(app) }
    val pressure = PlaybackPressure()
    val sources: SourceManager by lazy { SourceManager(app, database, cipher, http, device, pressure) }

    /** Runs the expensive initialisations off the UI thread (codec list, Keystore, database open). */
    fun warmUp() {
        runCatching { device }
        runCatching { cipher.warmUp() }
        runCatching { database.writableDatabase }
        runCatching { health.tracker }
        runCatching { http.playerClient }
    }
}

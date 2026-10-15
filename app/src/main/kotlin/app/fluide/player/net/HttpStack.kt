package app.fluide.player.net

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.fluide.player.BuildConfig
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * All HTTP traffic of the app, split by priority:
 *
 * - [playerClient]: the stream. Its own dispatcher, so logo downloads to the same server can never
 *   queue in front of a video segment (OkHttp limits concurrent calls per host per dispatcher).
 *   Short timeouts (the watchdog reacts before them) and HTTP/2 pings to detect dead connections.
 * - [downloadClient]: playlists and EPG, long read timeout.
 * - [imageClient]: channel logos, low-priority threads and at most 4 calls per host.
 *
 * They share one DNS cache and one connection pool (a zap reuses the TLS connection of the previous
 * channel when it is on the same server).
 */
class HttpStack(private val context: Context) {

    val dns = CachingDns()
    val manifestCache = ManifestCache()
    val defaultUserAgent =
        "FluidePlayer/${BuildConfig.VERSION_NAME} (Linux; Android ${Build.VERSION.RELEASE}) AndroidXMedia3/1.11"

    private val pool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    private val base: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectionPool(pool)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    val playerClient: OkHttpClient = base.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 16 })
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .addInterceptor(manifestCache)
        .build()

    val downloadClient: OkHttpClient = base.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    val imageClient: OkHttpClient = base.newBuilder()
        .dispatcher(Dispatcher(backgroundExecutor("fluide-img")).apply { maxRequests = 8; maxRequestsPerHost = 4 })
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Data sources for one playback session: its own User-Agent / Referer, byte counting for the watchdog. */
    @OptIn(UnstableApi::class)
    fun playerDataSourceFactory(userAgent: String?, referrer: String?, listener: TransferListener): DataSource.Factory {
        val network = OkHttpDataSource.Factory(playerClient)
            .setUserAgent(userAgent ?: defaultUserAgent)
        if (referrer != null) network.setDefaultRequestProperties(mapOf("Referer" to referrer))
        // DefaultDataSource adds file://, content:// and udp:// on top of HTTP.
        return DefaultDataSource.Factory(context, network).setTransferListener(listener)
    }

    /** Closes idle pooled connections, e.g. after a network change or a suspected dead connection. */
    fun evictIdleConnections() = pool.evictAll()

    private fun backgroundExecutor(name: String): ExecutorService =
        ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, name).apply { isDaemon = true }
        }
}

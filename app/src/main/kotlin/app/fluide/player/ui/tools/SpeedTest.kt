package app.fluide.player.ui.tools

import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.PlayableChannel
import app.fluide.player.net.HttpStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the measured speed allows. */
enum class SpeedVerdict { POOR, SD, HD, FULL_HD, UHD }

/** Step of the test. */
enum class SpeedTestPhase { IDLE, LATENCY, DOWNLOAD, DONE }

/** Why a test failed: the screen shows a localized explanation, [SpeedTestState.error] the technical detail. */
enum class SpeedTestError {
    /** No active playlist. */
    NO_SOURCE,

    /** No channel of the playlist has an http(s) stream the test can download. */
    NO_STREAM,

    /** The server refused the stream (401, 403, 429...): expired account or no connection left. */
    REFUSED,

    /** Any other HTTP error. */
    HTTP,

    /** The server did not answer in time. */
    TIMEOUT,

    /** No network, unknown host, connection refused or reset. */
    NETWORK,

    /** The server answered but sent no video. */
    NO_DATA,
}

data class SpeedTestState(
    val running: Boolean = false,
    /** 0..1 while running. */
    val progress: Float = 0f,
    val latencyMs: Long? = null,
    val downloadKbps: Long? = null,
    val verdict: SpeedVerdict? = null,
    val error: String? = null,
    /** Playlist tested: the results of another playlist must not be shown. */
    val sourceId: Long = 0,
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    /** Set with [error] when the test failed. */
    val errorKind: SpeedTestError? = null,
    /** Bytes received by the throughput measurement. */
    val bytes: Long = 0,
    /** Wall-clock time (ms) the last test ended, 0 if none. */
    val finishedAt: Long = 0,
)

/** Server of a playlist, for display: host (and port when not the default one) and the user name already partly masked. */
data class ServerInfo(val host: String, val secure: Boolean, val maskedUser: String?)

/**
 * Connection test against the playlist's own server: latency, then the throughput of a real stream.
 *
 * - Latency: median of [LATENCY_SAMPLES] HEAD requests to the root of the server of the first channel
 *   (any HTTP answer counts). Servers that drop such requests fall back to the time to the first answer
 *   of the stream request.
 * - Throughput: the first channel of the playlist is downloaded with the player's HTTP client (same
 *   User-Agent and Referer as playback) for up to [WINDOW_MS] or [MAX_BYTES], from its first byte. HLS
 *   playlists are followed (best variant, then the last segments). A dead first channel is replaced by
 *   the next ones ([MAX_TRIES]).
 *
 * Every request is cancelled as soon as it is not needed (end of the budget, [cancel], time limit): no
 * connection is ever left open on an account that may allow only one.
 */
class SpeedTest(private val http: HttpStack, private val channels: ChannelRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = mutable.asStateFlow()

    private var job: Job? = null

    /** Incremented by every [run] and [cancel]: a test that was replaced can no longer publish anything. */
    @Volatile
    private var generation = 0

    fun run(sourceId: Long) {
        job?.cancel()
        val token = ++generation
        mutable.value = SpeedTestState(running = true, sourceId = sourceId, phase = SpeedTestPhase.LATENCY)
        job = scope.launch {
            try {
                val result = measure(sourceId, token)
                publish(token) { result }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val failure = failureOf(e)
                publish(token) {
                    it.copy(
                        running = false,
                        phase = SpeedTestPhase.DONE,
                        errorKind = failure.kind,
                        error = failure.detail ?: failure.kind.name,
                        finishedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    /** Stops a running test (its connections are closed at once); the results of a finished one stay. */
    fun cancel() {
        job?.cancel()
        job = null
        generation++
        mutable.update { if (it.running) SpeedTestState(sourceId = it.sourceId) else it }
    }

    /**
     * Server of [sourceId] as the account screen shows it (from the URL of its first channel), null when
     * unknown. Reads the database on the IO threads.
     */
    suspend fun server(sourceId: Long): ServerInfo? = withContext(Dispatchers.IO) {
        runCatching { candidates(sourceId, 1).firstOrNull()?.url?.toHttpUrlOrNull()?.let(::describe) }.getOrNull()
    }

    // ------------------------------------------------------------------ measurement

    private suspend fun measure(sourceId: Long, token: Int): SpeedTestState {
        if (sourceId == 0L) throw TestFailure(SpeedTestError.NO_SOURCE, null)
        val streams = withContext(Dispatchers.IO) { candidates(sourceId, MAX_TRIES) }
        if (streams.isEmpty()) throw TestFailure(SpeedTestError.NO_STREAM, null)

        val latency = latency(streams.first(), token)

        publish(token) { it.copy(phase = SpeedTestPhase.DOWNLOAD, progress = LATENCY_SHARE) }
        val meter = Meter(token)
        val attempt = withTimeoutOrNull(HARD_LIMIT_MS) { Attempt(throughput(streams, meter)) }
        meter.stop()
        if (!meter.usable()) {
            throw when {
                attempt == null -> TestFailure(SpeedTestError.TIMEOUT, "no video after ${HARD_LIMIT_MS / 1_000} s")
                attempt.failure != null -> attempt.failure
                else -> TestFailure(SpeedTestError.NO_DATA, "empty stream")
            }
        }
        val kbps = meter.kbps()
        return SpeedTestState(
            running = false,
            progress = 1f,
            latencyMs = latency ?: meter.answerMs,
            downloadKbps = kbps,
            verdict = verdictOf(kbps),
            sourceId = sourceId,
            phase = SpeedTestPhase.DONE,
            bytes = meter.bytes,
            finishedAt = System.currentTimeMillis(),
        )
    }

    /** Median time of HEAD requests to the root of the stream's server; null when the server does not answer them. */
    private suspend fun latency(stream: PlayableChannel, token: Int): Long? {
        val origin = stream.url.toHttpUrlOrNull()?.newBuilder()
            ?.username("")
            ?.password("")
            ?.encodedPath("/")
            ?.query(null)
            ?.fragment(null)
            ?.build() ?: return null
        val client: OkHttpClient = http.playerClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
        val samples = ArrayList<Long>(LATENCY_SAMPLES)
        for (i in 0 until LATENCY_SAMPLES) {
            val request = Request.Builder()
                .url(origin)
                .head()
                .header("User-Agent", stream.userAgent ?: http.defaultUserAgent)
                .build()
            val start = System.nanoTime()
            val answered = try {
                execute(client.newCall(request)) { }
                true
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                false
            }
            // A server that drops these requests would only waste seconds on the next ones.
            if (!answered) break
            samples += (System.nanoTime() - start) / 1_000_000
            val median = median(samples)
            publish(token) { it.copy(latencyMs = median, progress = LATENCY_SHARE * (i + 1) / LATENCY_SAMPLES) }
        }
        return if (samples.isEmpty()) null else median(samples)
    }

    /** Downloads the first stream that works; null when one was measured, else the last failure. */
    private suspend fun throughput(streams: List<PlayableChannel>, meter: Meter): TestFailure? {
        var last: TestFailure? = null
        for (stream in streams) {
            meter.reset()
            val failure = try {
                download(stream, meter)
                if (meter.usable()) return null
                // Ended almost at once (tiny body, short error video...): try the next channel.
                TestFailure(SpeedTestError.NO_DATA, "stream ended after ${meter.bytes} bytes")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                failureOf(e)
            }
            // Cut after enough video (server closing a live stream...): that is a measurement.
            if (meter.usable()) return null
            last = failure
            if (failure.kind != SpeedTestError.HTTP && failure.kind != SpeedTestError.NO_DATA) break
        }
        return last
    }

    private suspend fun download(stream: PlayableChannel, meter: Meter) {
        val url = stream.url.toHttpUrlOrNull() ?: throw TestFailure(SpeedTestError.NO_STREAM, null)
        var playlist = fetch(stream, url, meter) ?: return
        if (playlist.text.contains("#EXT-X-STREAM-INF")) {
            // Master playlist: measure with the best quality, the one the player would like to play.
            val variant = bestVariant(playlist) ?: throw TestFailure(SpeedTestError.NO_DATA, "no variant in the playlist")
            playlist = fetch(stream, variant, meter) ?: return
        }
        val segments = segments(playlist)
        if (segments.isEmpty()) throw TestFailure(SpeedTestError.NO_DATA, "empty playlist")
        // Live playlists: the oldest segments may already be gone, start near the live edge like the player.
        for (segment in segments.takeLast(MAX_SEGMENTS)) {
            fetch(stream, segment, meter)
            if (meter.spent()) return
        }
    }

    /**
     * GETs [url] with the headers of [stream]. A video body is counted by [meter] until its budget is
     * spent or the body ends (null is returned); an HLS playlist is returned instead.
     */
    private suspend fun fetch(stream: PlayableChannel, url: HttpUrl, meter: Meter): Playlist? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", stream.userAgent ?: http.defaultUserAgent)
            .apply { stream.referrer?.let { header("Referer", it) } }
            .build()
        val call = http.playerClient.newCall(request)
        val start = System.nanoTime()
        return execute(call) { response ->
            meter.answered((System.nanoTime() - start) / 1_000_000)
            if (!response.isSuccessful) throw httpFailure(response.code)
            val body = response.body ?: throw TestFailure(SpeedTestError.NO_DATA, "no body")
            val source = body.source()
            val type = body.contentType()?.toString()?.lowercase().orEmpty()
            if ("mpegurl" in type || startsWithPlaylistTag(source)) {
                Playlist(readText(source), response.request.url)
            } else {
                meter.drain(source, call)
                null
            }
        }
    }

    /**
     * Runs [block] on the IO threads with the response of [call], closed afterwards. Cancelling the
     * coroutine (test cancelled, time limit) cancels the call: a read blocked on a silent server returns
     * at once instead of after the read timeout.
     */
    private suspend fun <T> execute(call: Call, block: (Response) -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                call.execute().use(block).also { finished.set(true) }
            }
        } finally {
            guard.cancel()
        }
    }

    /** Up to [limit] channels of [sourceId] (playlist order) whose stream the test can download. Blocking. */
    private fun candidates(sourceId: Long, limit: Int): List<PlayableChannel> {
        val ids = channels.channelIds(sourceId, ChannelFilter.All)
        val out = ArrayList<PlayableChannel>(limit)
        for (i in 0 until minOf(ids.size, SCAN_LIMIT)) {
            val channel = channels.playable(ids[i]) ?: continue
            val path = channel.url.substringBefore('?').lowercase()
            // DRM and DASH streams need a real player; udp://, rtmp://... are not HTTP.
            if (channel.drmScheme != null || path.endsWith(".mpd") || channel.url.toHttpUrlOrNull() == null) continue
            out += channel
            if (out.size >= limit) break
        }
        return out
    }

    private fun publish(token: Int, transform: (SpeedTestState) -> SpeedTestState) {
        mutable.update { if (token == generation) transform(it) else it }
    }

    // ------------------------------------------------------------------ throughput meter

    /** Bytes received and time since the first byte (the connection time is the latency's business). */
    private inner class Meter(private val token: Int) {
        var bytes = 0L
            private set

        /** Time to the first answer of the stream server, used when the latency probe got nothing. */
        var answerMs: Long? = null
            private set

        private var startNs = 0L
        private var endNs = 0L
        private var publishedNs = 0L

        fun reset() {
            bytes = 0
            startNs = 0
            endNs = 0
        }

        fun answered(ms: Long) {
            if (answerMs == null) answerMs = ms
        }

        fun stop() {
            if (endNs == 0L && startNs != 0L) endNs = System.nanoTime()
        }

        fun elapsedMs(): Long = when {
            startNs == 0L -> 0
            endNs != 0L -> (endNs - startNs) / 1_000_000
            else -> (System.nanoTime() - startNs) / 1_000_000
        }

        fun kbps(): Long {
            val ms = elapsedMs()
            // Bits per millisecond are kilobits per second.
            return if (ms <= 0) 0 else bytes * 8 / ms
        }

        fun spent(): Boolean = bytes >= MAX_BYTES || elapsedMs() >= WINDOW_MS

        /** Enough data for a meaningful figure (a slow line sends little in a long time). */
        fun usable(): Boolean = bytes > 0 && (elapsedMs() >= MIN_WINDOW_MS || bytes >= MIN_BYTES)

        /** Reads [source] until the budget is spent (then cancels [call]) or the body ends. Blocking. */
        fun drain(source: BufferedSource, call: Call) {
            val sink = Buffer()
            while (true) {
                val read = source.read(sink, CHUNK_BYTES)
                if (read == -1L) return
                sink.clear()
                add(read)
                if (spent()) {
                    call.cancel()
                    return
                }
            }
        }

        private fun add(count: Long) {
            val now = System.nanoTime()
            if (startNs == 0L) startNs = now
            bytes += count
            if (now - publishedNs < PUBLISH_INTERVAL_NS) return
            publishedNs = now
            val ms = (now - startNs) / 1_000_000
            val share = maxOf(ms.toFloat() / WINDOW_MS, bytes.toFloat() / MAX_BYTES).coerceIn(0f, 1f)
            val received = bytes
            val kbps = if (ms >= MIN_DISPLAY_MS) received * 8 / ms else null
            publish(token) {
                it.copy(
                    progress = LATENCY_SHARE + (1f - LATENCY_SHARE) * share,
                    downloadKbps = kbps ?: it.downloadKbps,
                    bytes = received,
                )
            }
        }
    }

    /** Result of the throughput phase when it ended before its time limit. */
    private class Attempt(val failure: TestFailure?)

    /** A downloaded HLS playlist and the URL its relative URIs resolve against. */
    private class Playlist(val text: String, val url: HttpUrl)

    private class TestFailure(val kind: SpeedTestError, val detail: String?) : Exception(detail)

    companion object {
        private const val LATENCY_SAMPLES = 3
        private const val PROBE_TIMEOUT_S = 4L

        /** Share of the progress bar given to the latency step. */
        private const val LATENCY_SHARE = 0.1f

        /** Throughput budget: 8 s from the first byte, or 30 MB. */
        private const val WINDOW_MS = 8_000L
        private const val MAX_BYTES = 30L * 1024 * 1024

        /** Whole throughput step, connections and retries included. */
        private const val HARD_LIMIT_MS = 14_000L

        private const val MIN_WINDOW_MS = 1_000L
        private const val MIN_BYTES = 256L * 1024
        private const val MIN_DISPLAY_MS = 300L
        private const val PUBLISH_INTERVAL_NS = 150_000_000L
        private const val CHUNK_BYTES = 64L * 1024

        /** Channels tried for the throughput, and channels scanned to find them. */
        private const val MAX_TRIES = 3
        private const val SCAN_LIMIT = 40

        private const val MAX_SEGMENTS = 6
        private const val MAX_PLAYLIST_BYTES = 1L * 1024 * 1024

        private val PLAYLIST_TAG = "#EXTM3U".encodeUtf8()
        private val PLAYLIST_TAG_BOM = "﻿#EXTM3U".encodeUtf8()
        private val BANDWIDTH = Regex("[:,]BANDWIDTH=(\\d+)")
        private val URL_IN_TEXT = Regex("[a-zA-Z][a-zA-Z0-9+.-]*://\\S+")

        /** HTTP codes meaning "not for you" rather than "not here": credentials, connection limit, ban. */
        private val REFUSED_CODES = setOf(401, 402, 403, 407, 423, 429, 451, 456, 458, 509, 512)

        /** Xtream paths: /live/<user>/<password>/<id>.ts (also movie, series, timeshift). */
        private val XTREAM_KINDS = setOf("live", "movie", "series", "timeshift")

        /** Minimum speed for each quality: < 1.5 Mbit/s poor, < 3 SD, < 7 HD, < 20 Full HD, else 4K. */
        fun verdictOf(kbps: Long): SpeedVerdict = when {
            kbps < 1_500 -> SpeedVerdict.POOR
            kbps < 3_000 -> SpeedVerdict.SD
            kbps < 7_000 -> SpeedVerdict.HD
            kbps < 20_000 -> SpeedVerdict.FULL_HD
            else -> SpeedVerdict.UHD
        }

        /** Host (with a non-default port) and masked user name of a stream URL. Never the password. */
        fun describe(url: HttpUrl): ServerInfo {
            val host = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
            return ServerInfo(host, url.isHttps, userOf(url)?.let(::mask))
        }

        /** "ab••••z": enough to recognise one's account on a screen others can see. */
        fun mask(user: String): String = when {
            user.length <= 2 -> "•".repeat(user.length.coerceAtLeast(1))
            user.length <= 5 -> user.take(1) + "•".repeat(user.length - 1)
            else -> user.take(2) + "•".repeat(minOf(user.length - 3, 6)) + user.takeLast(1)
        }

        private fun userOf(url: HttpUrl): String? {
            url.queryParameter("username")?.takeIf { it.isNotBlank() }?.let { return it }
            val parts = url.pathSegments.filter { it.isNotEmpty() }
            if (parts.size >= 4 && parts[0].lowercase() in XTREAM_KINDS) return parts[1]
            // Xtream panels exported as M3U: /<user>/<password>/<id>.
            if (parts.size == 3) {
                val id = parts[2].substringBefore('.')
                if (id.isNotEmpty() && id.all(Char::isDigit)) return parts[0]
            }
            return null
        }

        private fun median(samples: List<Long>): Long = samples.sorted()[samples.size / 2]

        private fun startsWithPlaylistTag(source: BufferedSource): Boolean =
            (source.request(PLAYLIST_TAG.size.toLong()) && source.buffer.rangeEquals(0, PLAYLIST_TAG)) ||
                (source.request(PLAYLIST_TAG_BOM.size.toLong()) && source.buffer.rangeEquals(0, PLAYLIST_TAG_BOM))

        private fun readText(source: BufferedSource): String {
            val sink = Buffer()
            while (source.read(sink, 8_192) != -1L) {
                if (sink.size > MAX_PLAYLIST_BYTES) throw TestFailure(SpeedTestError.NO_DATA, "playlist too large")
            }
            return sink.readUtf8()
        }

        /** URI of the variant with the highest BANDWIDTH of a master playlist. */
        private fun bestVariant(playlist: Playlist): HttpUrl? {
            val lines = playlist.text.lines()
            var best: HttpUrl? = null
            var bestRate = -1L
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val rate = BANDWIDTH.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uri = (i + 1 until lines.size).firstNotNullOfOrNull { j ->
                    lines[j].trim().takeIf { it.isNotEmpty() && !it.startsWith("#") }
                } ?: continue
                val url = playlist.url.resolve(uri) ?: continue
                if (rate > bestRate) {
                    best = url
                    bestRate = rate
                }
            }
            return best
        }

        private fun segments(playlist: Playlist): List<HttpUrl> = playlist.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { playlist.url.resolve(it) }

        private fun httpFailure(code: Int) =
            TestFailure(if (code in REFUSED_CODES) SpeedTestError.REFUSED else SpeedTestError.HTTP, "HTTP $code")

        private fun failureOf(e: Throwable): TestFailure = when (e) {
            is TestFailure -> e
            // SocketTimeoutException is an InterruptedIOException.
            is InterruptedIOException -> TestFailure(SpeedTestError.TIMEOUT, clean(e))
            is UnknownHostException -> TestFailure(SpeedTestError.NETWORK, "unknown host")
            else -> TestFailure(SpeedTestError.NETWORK, clean(e))
        }

        /** Exception text for the screen: never a full URL (it could hold the credentials). */
        private fun clean(e: Throwable): String {
            val message = e.message?.replace(URL_IN_TEXT) { it.value.toHttpUrlOrNull()?.host ?: "…" }
            return (if (message.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $message").take(120)
        }
    }
}

package app.fluide.player.net

import android.os.SystemClock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Zapping accelerator for HLS/DASH: the playlist of a neighbouring channel is downloaded ahead of time,
 * and the player's first request for it is answered from memory, saving one or two round trips
 * (often 100-400 ms on IPTV servers far away).
 *
 * Live playlists go stale quickly, so entries are single-use and short-lived: a media playlist
 * (segments listed) lives [mediaTtlMs], a master playlist (variants only) lives [masterTtlMs].
 * Only used when the source allows more than one connection: some servers count every request.
 */
class ManifestCache(
    private val mediaTtlMs: Long = 2_500,
    private val masterTtlMs: Long = 30_000,
    private val maxEntries: Int = 6,
    private val maxBytes: Int = 512 * 1024,
) : Interceptor {

    /** [finalUrl]: URL after redirects. Relative segment URIs are resolved against it by the parser. */
    private class Entry(val bytes: ByteArray, val contentType: String?, val finalUrl: String, val expiresAt: Long)

    private val entries = LinkedHashMap<String, Entry>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method == "GET" && request.header("Range") == null) {
            val hit = take(request.url.toString())
            if (hit != null) {
                // OkHttpDataSource reports response.request.url as the stream URI: keep the redirected one.
                val served = if (hit.finalUrl == request.url.toString()) request else request.newBuilder().url(hit.finalUrl).build()
                return Response.Builder()
                    .request(served)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK (prefetched)")
                    .apply { hit.contentType?.let { header("Content-Type", it) } }
                    .body(hit.bytes.toResponseBody(hit.contentType?.toMediaTypeOrNull()))
                    .build()
            }
        }
        return chain.proceed(request)
    }

    /**
     * Downloads [url] with [client] and keeps it for the next player request. Blocking: call it from a
     * background thread. Failures are ignored, the player will simply download it itself.
     */
    fun prefetch(client: OkHttpClient, url: String, userAgent: String, referrer: String?) {
        synchronized(entries) {
            val existing = entries[url]
            if (existing != null && existing.expiresAt > SystemClock.elapsedRealtime()) return
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .apply { if (referrer != null) header("Referer", referrer) }
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                if (body.contentLength() > maxBytes) return
                val bytes = body.bytes()
                if (bytes.size > maxBytes) return
                val text = String(bytes, 0, minOf(bytes.size, 4_096), Charsets.UTF_8)
                val ttl = if (text.contains("#EXT-X-STREAM-INF") || text.contains("<MPD")) masterTtlMs else mediaTtlMs
                val finalUrl = response.request.url.toString()
                put(url, Entry(bytes, response.header("Content-Type"), finalUrl, SystemClock.elapsedRealtime() + ttl))
            }
        }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    private fun put(url: String, entry: Entry) = synchronized(entries) {
        entries.remove(url)
        entries[url] = entry
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    private fun take(url: String): Entry? = synchronized(entries) {
        val entry = entries.remove(url) ?: return null
        if (entry.expiresAt < SystemClock.elapsedRealtime()) null else entry
    }
}

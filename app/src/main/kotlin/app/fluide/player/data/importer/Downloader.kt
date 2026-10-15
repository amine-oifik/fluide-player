package app.fluide.player.data.importer

import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.Reader

class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Validators of a previous download, sent back as If-None-Match / If-Modified-Since. */
data class Validators(val etag: String?, val lastModified: String?)

/** Conditional, streaming download of playlists and EPG files. */
internal class Downloader(private val client: OkHttpClient) {

    /**
     * Downloads [url] and hands the decoded text to [consume] while it arrives (nothing is buffered
     * whole). Returns null when the server answers 304: the previous import is still current.
     */
    fun <T> fetch(
        url: String,
        userAgent: String,
        previous: Validators,
        job: Job,
        consume: (reader: Reader, validators: Validators) -> T,
    ): T? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .apply {
                previous.etag?.let { header("If-None-Match", it) }
                previous.lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        val call = client.newCall(request)
        val cancelHook = job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (response.code == 304) return null
                if (!response.isSuccessful) throw HttpStatusException(response.code)
                val body = response.body ?: throw IOException("Empty response")
                val validators = Validators(response.header("ETag"), response.header("Last-Modified"))
                TextInput.open(body.byteStream()).use { reader -> return consume(reader, validators) }
            }
        } finally {
            cancelHook.dispose()
        }
    }
}

package app.fluide.player.data.importer

import app.fluide.core.io.CharReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream

/** Turns a raw download into text for the streaming parsers. */
internal object TextInput {

    private const val BUFFER = 64 * 1024

    /**
     * Transparent gunzip (detected by its magic bytes: many EPG servers send `.xml.gz` as
     * application/octet-stream), then charset detection on the first 64 KB: UTF-8 when it decodes
     * cleanly, Windows-1252 otherwise (old Latin-1 playlists). Malformed bytes become U+FFFD instead of
     * aborting the import.
     */
    fun open(raw: InputStream): Reader {
        val buffered = BufferedInputStream(raw, BUFFER)
        val text = if (isGzip(buffered)) BufferedInputStream(GZIPInputStream(buffered, BUFFER), BUFFER) else buffered
        return InputStreamReader(text, detectCharset(text))
    }

    private fun isGzip(input: BufferedInputStream): Boolean {
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return b1 == 0x1f && b2 == 0x8b
    }

    private fun detectCharset(input: BufferedInputStream): Charset {
        val probe = ByteArray(BUFFER)
        input.mark(BUFFER)
        var length = 0
        while (length < probe.size) {
            val read = input.read(probe, length, probe.size - length)
            if (read < 0) break
            length += read
        }
        input.reset()
        if (length >= 2) {
            val b0 = probe[0].toInt() and 0xff
            val b1 = probe[1].toInt() and 0xff
            if (b0 == 0xff && b1 == 0xfe) return Charsets.UTF_16LE
            if (b0 == 0xfe && b1 == 0xff) return Charsets.UTF_16BE
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // endOfInput = false: a multi-byte character cut by the 64 KB boundary is not an error.
        val result = decoder.decode(ByteBuffer.wrap(probe, 0, length), CharBuffer.allocate(length + 1), false)
        return if (result.isError) Charset.forName("windows-1252") else Charsets.UTF_8
    }
}

/** Adapts a java.io.Reader to the core parsers and stops promptly when the import is cancelled. */
internal class CancellableCharReader(private val reader: Reader, private val job: Job) : CharReader {
    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        job.ensureActive()
        return reader.read(buffer, offset, length)
    }
}

package app.fluide.core.io

/**
 * Minimal streaming character source.
 *
 * The core module never depends on java.io so that it can move to Kotlin Multiplatform unchanged.
 * On Android, adapt a reader with `CharReader(reader::read)`.
 */
fun interface CharReader {
    /** Reads up to [length] chars into [buffer] at [offset]; returns the count read, or -1 at end of input. */
    fun read(buffer: CharArray, offset: Int, length: Int): Int
}

/** [CharReader] over an in-memory string (tests and small inputs). */
class StringCharReader(private val text: String) : CharReader {
    private var position = 0

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (position >= text.length) return -1
        val count = minOf(length, text.length - position)
        for (i in 0 until count) buffer[offset + i] = text[position + i]
        position += count
        return count
    }
}

/**
 * Reads lines from a [CharReader] with a single reusable buffer.
 *
 * Handles `\n`, `\r\n` and `\r`. A line longer than [maxLineLength] is cut at that length and counted in
 * [truncatedLines]: a hostile or corrupted file can never make the parser allocate without bound.
 */
class LineReader(
    private val source: CharReader,
    bufferSize: Int = 64 * 1024,
    private val maxLineLength: Int = 64 * 1024,
) {
    private val buffer = CharArray(bufferSize)
    private var position = 0
    private var limit = 0
    private var endOfInput = false
    private val line = StringBuilder(256)

    var truncatedLines = 0
        private set

    /** Returns the next line without its terminator, or null at the end of the input. */
    fun readLine(): String? {
        line.setLength(0)
        var truncated = false
        var readAnything = false
        while (true) {
            if (position >= limit && !fill()) {
                return if (readAnything) finish(truncated) else null
            }
            val c = buffer[position++]
            readAnything = true
            when (c) {
                '\n' -> return finish(truncated)
                '\r' -> {
                    if (position >= limit && !fill()) return finish(truncated)
                    if (buffer[position] == '\n') position++
                    return finish(truncated)
                }
                else -> if (line.length < maxLineLength) line.append(c) else truncated = true
            }
        }
    }

    private fun finish(truncated: Boolean): String {
        if (truncated) truncatedLines++
        return line.toString()
    }

    private fun fill(): Boolean {
        if (endOfInput) return false
        var emptyReads = 0
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count > 0) {
                position = 0
                limit = count
                return true
            }
            // A conforming reader blocks instead of returning 0; guard against broken ones anyway.
            if (count < 0 || ++emptyReads > 1_000) {
                endOfInput = true
                position = 0
                limit = 0
                return false
            }
        }
    }
}

package app.fluide.core

import app.fluide.core.io.CharReader

/** Returns at most [chunk] chars per read: exercises every buffer-boundary path of the parsers. */
class ChunkedCharReader(private val text: String, private val chunk: Int) : CharReader {
    private var position = 0

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        if (position >= text.length) return -1
        val count = minOf(length, chunk, text.length - position)
        for (i in 0 until count) buffer[offset + i] = text[position + i]
        position += count
        return count
    }
}

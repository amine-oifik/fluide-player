package app.fluide.core.json

import app.fluide.core.io.CharReader

enum class JsonToken { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, STRING, NUMBER, BOOLEAN, NULL, END }

/**
 * Lenient streaming JSON pull reader over a [CharReader].
 *
 * IPTV panels answer with lists of 50 000+ entries (tens of MB): they are read entry by entry with a
 * single reusable buffer, never as a tree. The reader is deliberately forgiving because these servers
 * are not: commas and colons are treated as separators (a missing or doubled comma is not an error),
 * numbers may be quoted and strings may be numbers, so callers read every scalar with [nextString].
 * A string longer than [maxStringLength] is cut (the rest is skipped): a hostile answer cannot make
 * the reader allocate without bound.
 */
class JsonReader(
    private val source: CharReader,
    bufferSize: Int = 32 * 1024,
    private val maxStringLength: Int = 32 * 1024,
) {
    private val buffer = CharArray(bufferSize)
    private var position = 0
    private var limit = 0
    private var endOfInput = false
    private val text = StringBuilder(64)

    /** Type of the next value, without consuming it. */
    fun peek(): JsonToken {
        val c = peekChar() ?: return JsonToken.END
        return when (c) {
            '{' -> JsonToken.BEGIN_OBJECT
            '}' -> JsonToken.END_OBJECT
            '[' -> JsonToken.BEGIN_ARRAY
            ']' -> JsonToken.END_ARRAY
            '"', '\'' -> JsonToken.STRING
            't', 'f' -> JsonToken.BOOLEAN
            'n' -> JsonToken.NULL
            else -> JsonToken.NUMBER
        }
    }

    /** True while the current object or array has more members. */
    fun hasNext(): Boolean {
        val token = peek()
        return token != JsonToken.END_OBJECT && token != JsonToken.END_ARRAY && token != JsonToken.END
    }

    fun beginObject() = expect('{')
    fun endObject() = expect('}')
    fun beginArray() = expect('[')
    fun endArray() = expect(']')

    /** Name of the next object member. */
    fun nextName(): String = nextString() ?: "null"

    /**
     * The next scalar as text: strings unescaped, numbers and booleans as written, `null` as null.
     * An object or array in place of a scalar (seen on real panels: `"info": []`) is skipped and gives null.
     */
    fun nextString(): String? {
        return when (peek()) {
            JsonToken.STRING -> readQuoted()
            JsonToken.NULL -> {
                readBare()
                null
            }
            JsonToken.NUMBER, JsonToken.BOOLEAN -> readBare()
            JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> {
                skipValue()
                null
            }
            JsonToken.END_OBJECT, JsonToken.END_ARRAY, JsonToken.END -> throw JsonException("value expected")
        }
    }

    fun nextLongOrNull(): Long? = nextString()?.let(::parseLong)

    fun nextIntOrNull(): Int? = nextLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    fun nextDoubleOrNull(): Double? = nextString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Skips the next value, whatever it is (nested objects and arrays included). */
    fun skipValue() {
        var depth = 0
        do {
            when (peek()) {
                JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY -> {
                    position++
                    depth++
                }
                JsonToken.END_OBJECT, JsonToken.END_ARRAY -> {
                    if (depth == 0) return
                    position++
                    depth--
                }
                JsonToken.STRING -> skipQuoted()
                JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> readBare()
                JsonToken.END -> return
            }
        } while (depth > 0)
    }

    private fun expect(c: Char) {
        val next = peekChar() ?: throw JsonException("'$c' expected, end of input")
        if (next != c) throw JsonException("'$c' expected, found '$next'")
        position++
    }

    /** Next significant char (separators skipped), not consumed. Null at the end of the input. */
    private fun peekChar(): Char? {
        while (true) {
            if (position >= limit && !fill()) return null
            val c = buffer[position]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':' || c == '﻿') {
                position++
            } else {
                return c
            }
        }
    }

    private fun readQuoted(): String {
        val quote = buffer[position++]
        text.setLength(0)
        while (true) {
            if (position >= limit && !fill()) throw JsonException("unterminated string")
            val c = buffer[position++]
            when {
                c == quote -> return text.toString()
                c == '\\' -> {
                    val escaped = readEscape()
                    if (text.length < maxStringLength) text.append(escaped)
                }
                text.length < maxStringLength -> text.append(c)
            }
        }
    }

    private fun skipQuoted() {
        val quote = buffer[position++]
        while (true) {
            if (position >= limit && !fill()) return
            val c = buffer[position++]
            if (c == quote) return
            if (c == '\\') readEscape()
        }
    }

    private fun readEscape(): Char {
        if (position >= limit && !fill()) throw JsonException("unterminated escape")
        return when (val c = buffer[position++]) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                var value = 0
                repeat(4) {
                    if (position >= limit && !fill()) throw JsonException("unterminated escape")
                    val digit = Character.digit(buffer[position++], 16)
                    if (digit < 0) return '�'
                    value = value * 16 + digit
                }
                value.toChar()
            }
            else -> c // \" \\ \/ and anything unknown: the char itself
        }
    }

    /** Unquoted scalar (number, true, false, null, or a bare word from a sloppy server). */
    private fun readBare(): String {
        text.setLength(0)
        while (true) {
            if (position >= limit && !fill()) break
            val c = buffer[position]
            if (c == ',' || c == '}' || c == ']' || c == ':' || c == ' ' || c == '\n' || c == '\r' || c == '\t') break
            if (text.length < maxStringLength) text.append(c)
            position++
        }
        if (text.isEmpty()) throw JsonException("unexpected character")
        return text.toString()
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
            if (count < 0 || ++emptyReads > 1_000) {
                endOfInput = true
                position = 0
                limit = 0
                return false
            }
        }
    }

    companion object {
        /** "12", " 12 ", "12.0", "1.2e3" -> Long; anything else -> null. */
        fun parseLong(value: String): Long? {
            val trimmed = value.trim()
            trimmed.toLongOrNull()?.let { return it }
            val asDouble = trimmed.toDoubleOrNull() ?: return null
            if (!asDouble.isFinite() || asDouble > Long.MAX_VALUE || asDouble < Long.MIN_VALUE) return null
            return asDouble.toLong()
        }
    }
}

class JsonException(message: String) : RuntimeException(message)

/** Reads every member of the next object, calling [onMember] with each name. [onMember] must consume the value. */
inline fun JsonReader.forEachMember(onMember: (name: String) -> Unit) {
    if (peek() != JsonToken.BEGIN_OBJECT) {
        skipValue()
        return
    }
    beginObject()
    while (hasNext()) onMember(nextName())
    endObject()
}

/** Reads every element of the next array (an object is read as the list of its values). [onElement] must consume the value. */
inline fun JsonReader.forEachElement(onElement: () -> Unit) {
    when (peek()) {
        JsonToken.BEGIN_ARRAY -> {
            beginArray()
            while (hasNext()) onElement()
            endArray()
        }
        JsonToken.BEGIN_OBJECT -> {
            beginObject()
            while (hasNext()) {
                nextName()
                onElement()
            }
            endObject()
        }
        else -> skipValue()
    }
}

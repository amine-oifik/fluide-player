package app.fluide.core.xmltv

import app.fluide.core.io.CharReader

/**
 * Streaming XMLTV parser specialised for EPG import.
 *
 * Why not DOM or a general SAX parser: an EPG can weigh hundreds of MB. This scanner reads one 64 K
 * buffer, never builds a tree, allocates strings only for the fields it keeps, and drops unwanted
 * programmes (unknown channel, outside the time window) before reading their text.
 *
 * Supported XML: elements, attributes with `"` or `'`, predefined and numeric entities, CDATA,
 * comments, processing instructions and DOCTYPE (with internal subset). Malformed input never throws:
 * the scanner skips what it cannot understand and keeps going.
 */
class XmltvParser(
    /** Filter for `<channel>` elements. */
    private val acceptChannel: (String) -> Boolean = { true },
    /**
     * Filter for the `channel` attribute of `<programme>` elements. Defaults to [acceptChannel]; a
     * separate filter lets an importer read every channel (to match by display name) while still
     * dropping programmes of unknown channels before their text is read.
     */
    private val acceptProgrammeChannel: (String) -> Boolean = acceptChannel,
    private val windowStartEpochSec: Long = Long.MIN_VALUE,
    private val windowEndEpochSec: Long = Long.MAX_VALUE,
    private val preferredLanguage: String? = null,
    private val maxTitleLength: Int = 300,
    private val maxDescriptionLength: Int = 2_000,
    private val bufferSize: Int = 64 * 1024,
    /** Duration assumed when a programme has no `stop` attribute. */
    private val defaultDurationSec: Long = 1_800,
) {

    fun parse(
        source: CharReader,
        onChannel: (XmltvChannel) -> Unit = {},
        onProgramme: (XmltvProgramme) -> Unit,
    ): XmltvParseResult = Session(source, onChannel, onProgramme).run()

    private inner class Session(
        private val source: CharReader,
        private val onChannel: (XmltvChannel) -> Unit,
        private val onProgramme: (XmltvProgramme) -> Unit,
    ) {
        private val buffer = CharArray(bufferSize)
        private var pos = 0
        private var limit = 0
        private var endOfInput = false

        private val name = StringBuilder(32)
        private val attributeName = StringBuilder(32)
        private val attributeValue = StringBuilder(256)
        private val entity = StringBuilder(16)
        private val text = StringBuilder(512)

        // Attributes of the current start tag that the EPG needs.
        private var attrId: String? = null
        private var attrStart: String? = null
        private var attrStop: String? = null
        private var attrChannel: String? = null
        private var attrLang: String? = null
        private var attrSrc: String? = null

        private var state = STATE_NONE
        private var captureTarget = TARGET_NONE
        private var captureLang: String? = null
        private var captureLimit = 0

        private var channelId: String? = null
        private var channelName: String? = null
        private var channelNameLang: String? = null
        private var channelIcon: String? = null

        private var programmeChannel = ""
        private var programmeStart = 0L
        private var programmeStop = 0L
        private var title: String? = null
        private var titleLang: String? = null
        private var subTitle: String? = null
        private var description: String? = null
        private var descriptionLang: String? = null
        private var category: String? = null

        private var channels = 0
        private var programmes = 0
        private var skipped = 0

        fun run(): XmltvParseResult {
            while (true) {
                val c = next()
                if (c < 0) break
                if (c == '<'.code) {
                    markup()
                } else if (captureTarget != TARGET_NONE) {
                    if (c == '&'.code) decodeEntity(text, captureLimit) else appendLimited(text, c.toChar(), captureLimit)
                }
            }
            return XmltvParseResult(channels, programmes, skipped)
        }

        // ---------------------------------------------------------------- markup

        private fun markup() {
            if (!ensure(1)) return
            when (buffer[pos]) {
                '?' -> skipPast("?>")
                '!' -> {
                    pos++
                    when {
                        lookingAt("--") -> {
                            pos += 2
                            skipPast("-->")
                        }
                        lookingAt("[CDATA[") -> {
                            pos += 7
                            readCData()
                        }
                        else -> skipDeclaration()
                    }
                }
                '/' -> {
                    pos++
                    readName()
                    skipPast(">")
                    endElement()
                }
                else -> {
                    readName()
                    if (name.isEmpty()) return
                    clearAttributes()
                    val selfClosing = readAttributes()
                    startElement()
                    if (selfClosing) endElement()
                }
            }
        }

        private fun readName() {
            name.setLength(0)
            while (true) {
                if (!ensure(1)) return
                val c = buffer[pos]
                if (c == '>' || c == '/' || c == '=' || c.isXmlSpace()) return
                name.append(c)
                pos++
            }
        }

        /** Reads attributes up to the end of the tag. Returns true for a self-closing tag. */
        private fun readAttributes(): Boolean {
            while (true) {
                skipSpaces()
                val c = next()
                if (c < 0) return false
                when (c) {
                    '>'.code -> return false
                    '/'.code -> {
                        skipPast(">")
                        return true
                    }
                }
                attributeName.setLength(0)
                attributeName.append(c.toChar())
                while (ensure(1)) {
                    val ch = buffer[pos]
                    if (ch == '=' || ch == '>' || ch == '/' || ch.isXmlSpace()) break
                    attributeName.append(ch)
                    pos++
                }
                skipSpaces()
                if (!ensure(1) || buffer[pos] != '=') continue // attribute without value
                pos++
                skipSpaces()
                if (!ensure(1)) return false
                val quote = buffer[pos]
                attributeValue.setLength(0)
                if (quote == '"' || quote == '\'') {
                    pos++
                    while (true) {
                        val v = next()
                        if (v < 0 || v == quote.code) break
                        if (v == '&'.code) decodeEntity(attributeValue, MAX_ATTRIBUTE_LENGTH)
                        else appendLimited(attributeValue, v.toChar(), MAX_ATTRIBUTE_LENGTH)
                    }
                } else {
                    while (ensure(1)) {
                        val v = buffer[pos]
                        if (v == '>' || v.isXmlSpace()) break
                        appendLimited(attributeValue, v, MAX_ATTRIBUTE_LENGTH)
                        pos++
                    }
                }
                storeAttribute()
            }
        }

        private fun storeAttribute() {
            when {
                attributeName.contentEquals("id") -> attrId = attributeValue.toString()
                attributeName.contentEquals("start") -> attrStart = attributeValue.toString()
                attributeName.contentEquals("stop") -> attrStop = attributeValue.toString()
                attributeName.contentEquals("channel") -> attrChannel = attributeValue.toString()
                attributeName.contentEquals("lang") -> attrLang = attributeValue.toString()
                attributeName.contentEquals("src") -> attrSrc = attributeValue.toString()
            }
        }

        private fun clearAttributes() {
            attrId = null
            attrStart = null
            attrStop = null
            attrChannel = null
            attrLang = null
            attrSrc = null
        }

        // ---------------------------------------------------------------- elements

        private fun startElement() {
            when (state) {
                STATE_NONE -> when {
                    name.contentEquals("channel") -> {
                        state = STATE_CHANNEL
                        channelId = attrId
                        channelName = null
                        channelNameLang = null
                        channelIcon = null
                    }
                    name.contentEquals("programme") -> startProgramme()
                }
                STATE_CHANNEL -> when {
                    name.contentEquals("display-name") -> beginCapture(TARGET_DISPLAY_NAME, maxTitleLength)
                    name.contentEquals("icon") -> if (channelIcon == null) channelIcon = attrSrc
                }
                STATE_PROGRAMME -> when {
                    name.contentEquals("title") -> beginCapture(TARGET_TITLE, maxTitleLength)
                    name.contentEquals("sub-title") -> beginCapture(TARGET_SUBTITLE, maxTitleLength)
                    name.contentEquals("desc") -> beginCapture(TARGET_DESCRIPTION, maxDescriptionLength)
                    name.contentEquals("category") -> if (category == null) beginCapture(TARGET_CATEGORY, maxTitleLength)
                }
                STATE_SKIP_PROGRAMME -> Unit
            }
        }

        private fun startProgramme() {
            val channel = attrChannel
            val start = attrStart?.let { XmltvTime.parseEpochSeconds(it) } ?: XmltvTime.INVALID
            var stop = attrStop?.let { XmltvTime.parseEpochSeconds(it) } ?: XmltvTime.INVALID
            if (start != XmltvTime.INVALID && (stop == XmltvTime.INVALID || stop <= start)) {
                stop = start + defaultDurationSec
            }
            if (channel == null || start == XmltvTime.INVALID || stop <= windowStartEpochSec ||
                start >= windowEndEpochSec || !acceptProgrammeChannel(channel)
            ) {
                state = STATE_SKIP_PROGRAMME
                skipped++
                return
            }
            state = STATE_PROGRAMME
            programmeChannel = channel
            programmeStart = start
            programmeStop = stop
            title = null
            titleLang = null
            subTitle = null
            description = null
            descriptionLang = null
            category = null
        }

        private fun endElement() {
            if (captureTarget != TARGET_NONE && name.contentEquals(targetElementName(captureTarget))) endCapture()
            when (state) {
                STATE_CHANNEL -> if (name.contentEquals("channel")) {
                    val id = channelId
                    if (id != null && acceptChannel(id)) {
                        onChannel(XmltvChannel(id, channelName, channelIcon))
                        channels++
                    }
                    state = STATE_NONE
                }
                STATE_PROGRAMME -> if (name.contentEquals("programme")) {
                    val programmeTitle = title
                    if (programmeTitle != null) {
                        onProgramme(
                            XmltvProgramme(
                                channelId = programmeChannel,
                                startEpochSec = programmeStart,
                                stopEpochSec = programmeStop,
                                title = programmeTitle,
                                subTitle = subTitle,
                                description = description,
                                category = category,
                            ),
                        )
                        programmes++
                    } else {
                        skipped++
                    }
                    state = STATE_NONE
                }
                STATE_SKIP_PROGRAMME -> if (name.contentEquals("programme")) state = STATE_NONE
                STATE_NONE -> Unit
            }
        }

        private fun beginCapture(target: Int, maxLength: Int) {
            captureTarget = target
            captureLang = attrLang
            captureLimit = maxLength
            text.setLength(0)
        }

        private fun endCapture() {
            val value = text.trim().toString()
            val lang = captureLang
            val target = captureTarget
            captureTarget = TARGET_NONE
            if (value.isEmpty()) return
            val preferred = preferredLanguage
            when (target) {
                TARGET_DISPLAY_NAME ->
                    if (channelName == null || (preferred != null && lang == preferred && channelNameLang != preferred)) {
                        channelName = value
                        channelNameLang = lang
                    }
                TARGET_TITLE ->
                    if (title == null || (preferred != null && lang == preferred && titleLang != preferred)) {
                        title = value
                        titleLang = lang
                    }
                TARGET_SUBTITLE -> if (subTitle == null) subTitle = value
                TARGET_DESCRIPTION ->
                    if (description == null || (preferred != null && lang == preferred && descriptionLang != preferred)) {
                        description = value
                        descriptionLang = lang
                    }
                TARGET_CATEGORY -> if (category == null) category = value
            }
        }

        // ---------------------------------------------------------------- text helpers

        private fun readCData() {
            while (ensure(1)) {
                val c = buffer[pos]
                if (c == ']' && lookingAt("]]>")) {
                    pos += 3
                    return
                }
                pos++
                if (captureTarget != TARGET_NONE) appendLimited(text, c, captureLimit)
            }
        }

        /** `&` has been consumed. Appends the decoded character, or the raw text if it is not an entity. */
        private fun decodeEntity(out: StringBuilder, maxLength: Int) {
            entity.setLength(0)
            while (true) {
                val c = next()
                if (c < 0) {
                    appendRawEntity(out, maxLength, terminated = false)
                    return
                }
                if (c == ';'.code) break
                if (entity.length >= 12 || c == '<'.code || c == '&'.code || c.toChar().isXmlSpace()) {
                    appendRawEntity(out, maxLength, terminated = false)
                    if (c == '<'.code || c == '&'.code) pos-- else appendLimited(out, c.toChar(), maxLength)
                    return
                }
                entity.append(c.toChar())
            }
            when {
                entity.contentEquals("amp") -> appendLimited(out, '&', maxLength)
                entity.contentEquals("lt") -> appendLimited(out, '<', maxLength)
                entity.contentEquals("gt") -> appendLimited(out, '>', maxLength)
                entity.contentEquals("quot") -> appendLimited(out, '"', maxLength)
                entity.contentEquals("apos") -> appendLimited(out, '\'', maxLength)
                entity.contentEquals("nbsp") -> appendLimited(out, ' ', maxLength)
                entity.length > 1 && entity[0] == '#' -> {
                    val hex = entity[1] == 'x' || entity[1] == 'X'
                    val digits = if (hex) entity.substring(2) else entity.substring(1)
                    val codePoint = digits.toIntOrNull(if (hex) 16 else 10)
                    if (codePoint == null || codePoint < 0 || codePoint > 0x10FFFF) {
                        appendRawEntity(out, maxLength, terminated = true)
                    } else {
                        appendCodePoint(out, codePoint, maxLength)
                    }
                }
                else -> appendRawEntity(out, maxLength, terminated = true)
            }
        }

        private fun appendRawEntity(out: StringBuilder, maxLength: Int, terminated: Boolean) {
            appendLimited(out, '&', maxLength)
            for (i in entity.indices) appendLimited(out, entity[i], maxLength)
            if (terminated) appendLimited(out, ';', maxLength)
        }

        private fun appendCodePoint(out: StringBuilder, codePoint: Int, maxLength: Int) {
            if (codePoint < 0x10000) {
                appendLimited(out, codePoint.toChar(), maxLength)
            } else if (out.length + 2 <= maxLength) {
                val offset = codePoint - 0x10000
                out.append((0xD800 + (offset ushr 10)).toChar())
                out.append((0xDC00 + (offset and 0x3FF)).toChar())
            }
        }

        private fun appendLimited(out: StringBuilder, c: Char, maxLength: Int) {
            if (out.length < maxLength) out.append(c)
        }

        // ---------------------------------------------------------------- low-level scanning

        private fun skipSpaces() {
            while (ensure(1) && buffer[pos].isXmlSpace()) pos++
        }

        private fun skipPast(terminator: String) {
            val first = terminator[0]
            while (ensure(1)) {
                if (buffer[pos] == first && lookingAt(terminator)) {
                    pos += terminator.length
                    return
                }
                pos++
            }
        }

        /** Skips `<!DOCTYPE ...>` including an internal subset in brackets. */
        private fun skipDeclaration() {
            var depth = 0
            while (true) {
                val c = next()
                if (c < 0) return
                when (c) {
                    '['.code -> depth++
                    ']'.code -> if (depth > 0) depth--
                    '>'.code -> if (depth == 0) return
                }
            }
        }

        private fun lookingAt(expected: String): Boolean {
            if (!ensure(expected.length)) return false
            for (i in expected.indices) if (buffer[pos + i] != expected[i]) return false
            return true
        }

        private fun next(): Int {
            if (pos >= limit && !ensure(1)) return -1
            return buffer[pos++].code
        }

        /** Makes at least [count] characters available from [pos], compacting the buffer if needed. */
        private fun ensure(count: Int): Boolean {
            if (limit - pos >= count) return true
            if (endOfInput) return false
            val remaining = limit - pos
            if (pos > 0) {
                buffer.copyInto(buffer, 0, pos, limit)
                pos = 0
                limit = remaining
            }
            var emptyReads = 0
            while (limit - pos < count && !endOfInput) {
                val read = source.read(buffer, limit, buffer.size - limit)
                when {
                    read > 0 -> limit += read
                    read < 0 || ++emptyReads > 1_000 -> endOfInput = true
                }
            }
            return limit - pos >= count
        }
    }

    private companion object {
        const val STATE_NONE = 0
        const val STATE_CHANNEL = 1
        const val STATE_PROGRAMME = 2
        const val STATE_SKIP_PROGRAMME = 3

        const val TARGET_NONE = 0
        const val TARGET_DISPLAY_NAME = 1
        const val TARGET_TITLE = 2
        const val TARGET_SUBTITLE = 3
        const val TARGET_DESCRIPTION = 4
        const val TARGET_CATEGORY = 5

        const val MAX_ATTRIBUTE_LENGTH = 4_096

        fun targetElementName(target: Int): String = when (target) {
            TARGET_DISPLAY_NAME -> "display-name"
            TARGET_TITLE -> "title"
            TARGET_SUBTITLE -> "sub-title"
            TARGET_DESCRIPTION -> "desc"
            TARGET_CATEGORY -> "category"
            else -> ""
        }

        fun Char.isXmlSpace(): Boolean = this == ' ' || this == '\n' || this == '\r' || this == '\t'
    }
}

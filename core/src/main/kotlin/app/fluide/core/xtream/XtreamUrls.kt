package app.fluide.core.xtream

/**
 * URLs of an Xtream Codes server. [server] is what the user typed ("host:8080", "http://host:8080/",
 * "https://host/c/"...), normalised to `scheme://host[:port][/path]` without a trailing slash.
 */
class XtreamUrls(server: String, private val username: String, private val password: String) {

    val base: String = normalizeServer(server)

    private val user = encode(username)
    private val pass = encode(password)

    fun api(action: String? = null, vararg params: Pair<String, String>): String = buildString {
        append(base).append("/player_api.php?username=").append(user).append("&password=").append(pass)
        if (action != null) append("&action=").append(action)
        for ((key, value) in params) append('&').append(key).append('=').append(encode(value))
    }

    fun xmltv(): String = "$base/xmltv.php?username=$user&password=$pass"

    /** Live stream; [format] "ts" (MPEG-TS, the most compatible) or "m3u8" (HLS). */
    fun live(streamId: Long, format: String): String = "$base/live/$user/$pass/$streamId.${format.ifBlank { "ts" }}"

    fun movie(streamId: Long, extension: String?): String = "$base/movie/$user/$pass/$streamId.${extension ?: "mp4"}"

    fun episode(episodeId: Long, extension: String?): String = "$base/series/$user/$pass/$episodeId.${extension ?: "mp4"}"

    /**
     * Recording of a live channel (catch-up). The start time is written in the server's local time,
     * [utcOffsetSec] being that zone's offset at [startSec].
     */
    fun timeshift(streamId: Long, startSec: Long, durationMin: Int, utcOffsetSec: Int): String =
        "$base/timeshift/$user/$pass/${durationMin.coerceAtLeast(1)}/${formatStart(startSec + utcOffsetSec)}/$streamId.ts"

    companion object {
        fun normalizeServer(server: String): String {
            var s = server.trim()
            if (!s.contains("://")) s = "http://$s"
            // A pasted full link ("http://host:8080/get.php?username=...") keeps only the server part.
            s = s.substringBefore('?')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
            }
            return s.trimEnd('/')
        }

        /**
         * Recognises an Xtream M3U link (`.../get.php?username=U&password=P&type=m3u_plus...`) so it can be
         * imported through the richer API (movies and series with their details). Null for other URLs.
         */
        fun fromM3uLink(url: String): Triple<String, String, String>? {
            val trimmed = url.trim()
            val path = trimmed.substringBefore('?')
            if (!path.endsWith("/get.php", ignoreCase = true)) return null
            val query = trimmed.substringAfter('?', "")
            var user: String? = null
            var pass: String? = null
            for (part in query.split('&')) {
                val key = part.substringBefore('=')
                val value = decode(part.substringAfter('=', ""))
                when (key) {
                    "username" -> user = value
                    "password" -> pass = value
                }
            }
            val u = user?.takeIf { it.isNotEmpty() } ?: return null
            val p = pass?.takeIf { it.isNotEmpty() } ?: return null
            return Triple(normalizeServer(path), u, p)
        }

        /** "yyyy-MM-dd:HH-mm" of local epoch seconds (the format Xtream timeshift URLs expect). */
        fun formatStart(localEpochSec: Long): String {
            val days = localEpochSec.floorDiv(86_400L)
            val secondsOfDay = localEpochSec.mod(86_400L).toInt()
            val (year, month, day) = civilFromDays(days)
            val hour = secondsOfDay / 3_600
            val minute = secondsOfDay % 3_600 / 60
            return "${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}:${pad(hour, 2)}-${pad(minute, 2)}"
        }

        /** Inverse of XmltvTime.daysFromCivil (Howard Hinnant's algorithm). */
        fun civilFromDays(days: Long): Triple<Int, Int, Int> {
            val z = days + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val dayOfEra = z - era * 146_097
            val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val mp = (5 * dayOfYear + 2) / 153
            val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
            return Triple(year, month, day)
        }

        private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

        /** Percent-encoding of a URL path segment or query value (UTF-8). */
        fun encode(value: String): String {
            val out = StringBuilder(value.length + 8)
            for (byte in value.encodeToByteArray()) {
                val c = byte.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                    out.append(ch)
                } else {
                    out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xf])
                }
            }
            return out.toString()
        }

        fun decode(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c == '%' && i + 2 < value.length) {
                    val hi = value[i + 1].digitToIntOrNull(16)
                    val lo = value[i + 2].digitToIntOrNull(16)
                    if (hi != null && lo != null) {
                        bytes += (hi * 16 + lo).toByte()
                        i += 3
                        continue
                    }
                }
                if (c == '+') {
                    bytes += ' '.code.toByte()
                } else {
                    for (b in c.toString().encodeToByteArray()) bytes += b
                }
                i++
            }
            return bytes.toByteArray().decodeToString()
        }

        private const val HEX = "0123456789ABCDEF"
    }
}

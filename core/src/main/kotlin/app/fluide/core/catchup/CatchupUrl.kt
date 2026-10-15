package app.fluide.core.catchup

import app.fluide.core.xtream.XtreamUrls

/**
 * Builds the URL of a past programme (catch-up / replay) from the playlist's `catchup` attributes,
 * following the conventions most IPTV players share:
 *
 * - `default` / `append`: `catchup-source` is a template, appended to the channel URL when it starts
 *   with `?` or `&` (append), used alone otherwise;
 * - `shift`: `?utc={utc}&lutc={lutc}` appended to the channel URL;
 * - `flussonic` / `fs`: `.../index.m3u8` becomes `.../index-{utc}-{duration}.m3u8`, `.../mpegts`
 *   becomes `.../timeshift_abs-{utc}.ts`;
 * - `xc`: Xtream Codes `/live/u/p/id.ext` becomes `/timeshift/u/p/{minutes}/{start}/id.ts`.
 *
 * Template placeholders: `{utc}` `${start}` `{start}` (start, epoch s), `{utcend}` `${end}` `{end}`,
 * `{lutc}` `${now}` `${timestamp}` (now), `{duration}` (s), `{duration:N}` (duration / N),
 * `{offset:N}` (now - start, / N), `{Y} {m} {d} {H} {M} {S}` (start, UTC) and their `${...}` forms.
 */
object CatchupUrl {

    /** Null when the channel has no usable catch-up definition. */
    fun build(
        mode: String?,
        source: String?,
        channelUrl: String,
        startSec: Long,
        stopSec: Long,
        nowSec: Long,
        serverUtcOffsetSec: Int = 0,
    ): String? {
        val duration = (stopSec - startSec).coerceAtLeast(60)
        return when (mode?.trim()?.lowercase()) {
            null, "", "default" -> source?.takeIf { it.isNotBlank() }?.let {
                val expanded = expand(it, startSec, stopSec, nowSec)
                if (it.startsWith("?") || it.startsWith("&")) channelUrl + expanded else expanded
            }
            "append" -> source?.takeIf { it.isNotBlank() }?.let { channelUrl + expand(it, startSec, stopSec, nowSec) }
            "shift", "timeshift" -> {
                val separator = if ('?' in channelUrl) '&' else '?'
                "$channelUrl${separator}utc=$startSec&lutc=$nowSec"
            }
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(channelUrl, startSec, duration)
            "xc" -> xtream(channelUrl, startSec, duration, serverUtcOffsetSec)
                ?: source?.takeIf { it.isNotBlank() }?.let { expand(it, startSec, stopSec, nowSec) }
            else -> source?.takeIf { it.isNotBlank() }?.let { expand(it, startSec, stopSec, nowSec) }
        }
    }

    fun expand(template: String, startSec: Long, stopSec: Long, nowSec: Long): String {
        val duration = (stopSec - startSec).coerceAtLeast(0)
        val out = StringBuilder(template.length + 32)
        var i = 0
        while (i < template.length) {
            val dollar = template[i] == '$' && i + 1 < template.length && template[i + 1] == '{'
            val open = if (dollar) i + 1 else i
            if (template[open] == '{') {
                val close = template.indexOf('}', open)
                if (close > open) {
                    val value = placeholder(template.substring(open + 1, close), startSec, stopSec, nowSec, duration)
                    if (value != null) {
                        out.append(value)
                        i = close + 1
                        continue
                    }
                }
            }
            out.append(template[i])
            i++
        }
        return out.toString()
    }

    private fun placeholder(name: String, start: Long, stop: Long, now: Long, duration: Long): String? {
        val key = name.substringBefore(':')
        val divisor = name.substringAfter(':', "").toLongOrNull()?.takeIf { it > 0 } ?: 1
        return when (key) {
            "utc", "start", "timestamp_start" -> start.toString()
            "utcend", "end" -> stop.toString()
            "lutc", "now", "timestamp" -> now.toString()
            "duration" -> (duration / divisor).toString()
            "offset" -> ((now - start).coerceAtLeast(0) / divisor).toString()
            "Y", "m", "d", "H", "M", "S" -> {
                val days = start.floorDiv(86_400L)
                val seconds = start.mod(86_400L).toInt()
                val (y, mo, d) = XtreamUrls.civilFromDays(days)
                when (key) {
                    "Y" -> y.toString().padStart(4, '0')
                    "m" -> mo.toString().padStart(2, '0')
                    "d" -> d.toString().padStart(2, '0')
                    "H" -> (seconds / 3_600).toString().padStart(2, '0')
                    "M" -> (seconds % 3_600 / 60).toString().padStart(2, '0')
                    else -> (seconds % 60).toString().padStart(2, '0')
                }
            }
            else -> null
        }
    }

    private fun flussonic(url: String, start: Long, duration: Long): String? {
        val query = url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = url.substringBefore('?')
        val slash = path.lastIndexOf('/')
        if (slash < 0) return null
        val dir = path.substring(0, slash)
        val file = path.substring(slash + 1)
        return when {
            file.endsWith(".m3u8") -> "$dir/${file.removeSuffix(".m3u8")}-$start-$duration.m3u8$query"
            file == "mpegts" || file.endsWith(".ts") -> "$dir/timeshift_abs-$start.ts$query"
            else -> "$path/timeshift_abs-$start.ts$query"
        }
    }

    /** `scheme://host/live/user/pass/id.ext` (or `/user/pass/id`) -> Xtream timeshift URL. */
    private fun xtream(url: String, start: Long, duration: Long, offset: Int): String? {
        val path = url.substringBefore('?')
        val schemeEnd = path.indexOf("://")
        if (schemeEnd < 0) return null
        val hostEnd = path.indexOf('/', schemeEnd + 3)
        if (hostEnd < 0) return null
        val base = path.substring(0, hostEnd)
        val parts = path.substring(hostEnd + 1).split('/').filter { it.isNotEmpty() }
        val tail = if (parts.firstOrNull() == "live") parts.drop(1) else parts
        if (tail.size < 3) return null
        val (user, pass) = tail[tail.size - 3] to tail[tail.size - 2]
        val id = tail.last().substringBefore('.').toLongOrNull() ?: return null
        val minutes = ((duration + 59) / 60).toInt()
        return "$base/timeshift/$user/$pass/$minutes/${XtreamUrls.formatStart(start + offset)}/$id.ts"
    }
}

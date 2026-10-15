package app.fluide.core.m3u

/** Container or protocol of a stream, guessed from its URL so the player can skip content sniffing. */
enum class StreamKind {
    HLS, DASH, MPEG_TS, PROGRESSIVE, RTSP, UDP, UNKNOWN;

    companion object {
        private val progressiveExtensions = arrayOf(
            ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi", ".mp3", ".aac", ".m4a", ".flac", ".ogg",
        )

        fun infer(url: String): StreamKind {
            val lower = url.trim().lowercase()
            if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) return RTSP
            if (lower.startsWith("udp://") || lower.startsWith("rtp://")) return UDP

            val path = lower.substringBefore('?').substringBefore('#')
            return when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> HLS
                path.endsWith(".mpd") -> DASH
                path.endsWith(".ts") || path.endsWith(".mts") || path.endsWith(".m2ts") -> MPEG_TS
                progressiveExtensions.any { path.endsWith(it) } -> PROGRESSIVE
                lower.contains("type=m3u8") || lower.contains("output=m3u8") ||
                    lower.contains("format=m3u8") || lower.contains("/hls/") -> HLS
                lower.contains("type=mpd") || lower.contains("format=mpd") || lower.contains("/dash/") -> DASH
                lower.contains("output=ts") || lower.contains("type=ts") || lower.contains("output=mpegts") -> MPEG_TS
                else -> UNKNOWN
            }
        }
    }
}

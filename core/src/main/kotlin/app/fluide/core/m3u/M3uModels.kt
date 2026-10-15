package app.fluide.core.m3u

/** Attributes of the `#EXTM3U` line; [epgUrls] comes from `url-tvg` / `x-tvg-url`. */
data class M3uHeader(
    val epgUrls: List<String>,
    val attributes: Map<String, String>,
)

/** One playable entry of an M3U playlist. */
data class M3uEntry(
    /** Position in the playlist, 0-based; keeps the provider's order. */
    val index: Int,
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val group: String?,
    /** `tvg-chno`, used for direct number entry on the remote. */
    val channelNumber: Int?,
    /** `-1` for live entries. */
    val durationSeconds: Int,
    val userAgent: String?,
    val referrer: String?,
    val catchup: CatchupInfo?,
    /** DRM declared with standard `#KODIPROP` lines, for licensed protected streams. */
    val drm: DrmInfo?,
    val kind: StreamKind,
)

data class CatchupInfo(val mode: String, val days: Int, val source: String?)

data class DrmInfo(val scheme: String, val licenseUrl: String?)

class M3uParseResult(
    val header: M3uHeader?,
    val entries: Int,
    /** Lines that were cut because they exceeded the maximum line length. */
    val truncatedLines: Int,
    /** True when the entry limit stopped the import early. */
    val reachedEntryLimit: Boolean,
)

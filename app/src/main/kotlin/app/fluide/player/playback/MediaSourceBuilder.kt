package app.fluide.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.fluide.core.m3u.StreamKind
import app.fluide.player.net.HttpStack

/** Everything needed to open one stream. */
data class StreamRequest(
    val mediaId: String,
    val url: String,
    val kind: StreamKind,
    val userAgent: String?,
    val referrer: String?,
    val drmScheme: String?,
    val drmLicenseUrl: String?,
    /** C.TIME_UNSET keeps the distance to the live edge defined by the stream itself. */
    val liveTargetOffsetMs: Long,
    val liveMinSpeed: Float,
    val liveMaxSpeed: Float,
)

/**
 * Builds the MediaSource for a channel explicitly instead of letting Media3 guess from the URL: IPTV
 * URLs often have no extension ("/live/user/pass/1234"), and each format gets settings tuned for
 * broadcast streams.
 */
@OptIn(UnstableApi::class)
class MediaSourceBuilder(
    private val http: HttpStack,
    private val transferListener: TransferListener,
) {
    private val errorPolicy = IptvLoadErrorPolicy()

    /**
     * MPEG-TS settings for broadcast feeds:
     * - ALLOW_NON_IDR_KEYFRAMES: start decoding on the first recovery point instead of waiting for an
     *   IDR frame, which some encoders send only every few seconds (faster zapping).
     * - DETECT_ACCESS_UNITS: frame boundaries are found even when the encoder omits AUD NAL units
     *   (otherwise: corrupted or frozen pictures on some IPTV feeds).
     * - ENABLE_HDMV_DTS_AUDIO_STREAMS: DTS tracks of Blu-ray style multiplexes.
     */
    private val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
        DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS

    private val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(tsFlags)
        .setConstantBitrateSeekingEnabled(true)

    fun create(request: StreamRequest): MediaSource {
        val dataSources = http.playerDataSourceFactory(request.userAgent, request.referrer, transferListener)
        val item = MediaItem.Builder()
            .setUri(request.url)
            .setMediaId(request.mediaId)
            .setMimeType(mimeTypeOf(request.kind))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(request.liveTargetOffsetMs)
                    .setMinPlaybackSpeed(request.liveMinSpeed)
                    .setMaxPlaybackSpeed(request.liveMaxSpeed)
                    .build(),
            )
            .apply {
                val scheme = request.drmScheme?.let(::drmUuid)
                if (scheme != null) {
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(scheme)
                            .setLicenseUri(request.drmLicenseUrl)
                            .setMultiSession(true)
                            .build(),
                    )
                }
            }
            .build()
        val drm = DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(dataSources) }
        return when (request.kind) {
            StreamKind.HLS -> HlsMediaSource.Factory(dataSources)
                .setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true))
                .setAllowChunklessPreparation(true)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .setDrmSessionManagerProvider(drm)
                .createMediaSource(item)
            StreamKind.DASH -> DashMediaSource.Factory(dataSources)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .setDrmSessionManagerProvider(drm)
                .createMediaSource(item)
            StreamKind.RTSP -> RtspMediaSource.Factory()
                .setUserAgent(request.userAgent ?: http.defaultUserAgent)
                .setTimeoutMs(RTSP_TIMEOUT_MS)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .createMediaSource(item)
            // MPEG_TS, PROGRESSIVE, UDP and UNKNOWN: the extractors sniff the container themselves.
            else -> ProgressiveMediaSource.Factory(dataSources, extractors)
                .setLoadErrorHandlingPolicy(errorPolicy)
                .setDrmSessionManagerProvider(drm)
                .createMediaSource(item)
        }
    }

    private fun mimeTypeOf(kind: StreamKind): String? = when (kind) {
        StreamKind.HLS -> MimeTypes.APPLICATION_M3U8
        StreamKind.DASH -> MimeTypes.APPLICATION_MPD
        StreamKind.RTSP -> MimeTypes.APPLICATION_RTSP
        StreamKind.MPEG_TS, StreamKind.UDP -> MimeTypes.VIDEO_MP2T
        else -> null
    }

    private fun drmUuid(scheme: String) = when (scheme.lowercase()) {
        "com.widevine.alpha", "widevine" -> C.WIDEVINE_UUID
        "com.microsoft.playready", "playready" -> C.PLAYREADY_UUID
        "org.w3.clearkey", "clearkey" -> C.CLEARKEY_UUID
        else -> Util.getDrmUuid(scheme)
    }

    private companion object {
        const val RTSP_TIMEOUT_MS = 8_000L
    }
}

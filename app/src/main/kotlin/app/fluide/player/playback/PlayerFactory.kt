package app.fluide.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import app.fluide.core.playback.StabilityProfile
import app.fluide.core.playback.StabilityTier
import app.fluide.player.device.DeviceProfile

/**
 * Builds an ExoPlayer for one stability profile. A player is only rebuilt when the profile tier (or a
 * hardware option) changes, which is rare: zapping keeps the same instance and reuses its decoders.
 */
@OptIn(UnstableApi::class)
class PlayerFactory(
    private val context: Context,
    private val device: DeviceProfile,
    private val bandwidthMeter: DefaultBandwidthMeter,
) {

    /** What requires a new player instance when it changes. */
    data class Config(
        val tier: StabilityTier,
        val tunneling: Boolean,
        val pcmOnly: Boolean,
        val appManagesFrameRate: Boolean,
    )

    class Built(val player: ExoPlayer, val speedControl: BufferAwareLiveSpeedControl, val config: Config)

    fun create(config: Config, profile: StabilityProfile): Built {
        // Buffers per profile: STABLE starts after 1 s of media, HOSTILE waits for 2.5 s and keeps up to
        // a minute. The byte cap comes from the device class (20 MB on 1 GB boxes) so a 4K stream can
        // never push a weak box into memory pressure, which is itself a cause of freezes.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, profile.startBufferMs, profile.resumeBufferMs)
            .setTargetBufferBytes(device.budget.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(device.budget.prioritizeTimeOverSize)
            .setBackBuffer(0, false)
            .build()

        // A lower bandwidth fraction on unstable servers: choose a variant that leaves headroom.
        val trackSelector = DefaultTrackSelector(
            context,
            AdaptiveTrackSelection.Factory(
                AdaptiveTrackSelection.DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS,
                AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                profile.bandwidthFraction,
            ),
        )
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTunnelingEnabled(config.tunneling)
                .setExceedRendererCapabilitiesIfNecessary(true),
        )

        val speedControl = BufferAwareLiveSpeedControl(
            DefaultLivePlaybackSpeedControl.Builder()
                .setFallbackMinPlaybackSpeed(profile.liveMinSpeed)
                .setFallbackMaxPlaybackSpeed(profile.liveMaxSpeed)
                // After a stall Media3 moves further from the live edge; on bad servers, by bigger steps.
                .setTargetLiveOffsetIncrementOnRebufferMs(
                    when (profile.tier) {
                        StabilityTier.STABLE -> 1_000L
                        StabilityTier.UNSTABLE -> 2_000L
                        StabilityTier.HOSTILE -> 3_000L
                    },
                )
                .setMaxLiveOffsetErrorMsForUnitSpeed(50)
                .build(),
            profile.speedPolicy,
        )

        val renderers = FluideRenderersFactory(context, config.pcmOnly)
            .setEnableDecoderFallback(true)
            // Uses decoder extensions (e.g. FFmpeg for MP2/AC-3 on boxes without them) when they are added.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setBandwidthMeter(bandwidthMeter)
            .setLivePlaybackSpeedControl(speedControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // With AFR enabled the activity picks the display mode itself (see DisplayModeController).
            .setVideoChangeFrameRateStrategy(
                if (config.appManagesFrameRate) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
                else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
            )
            // Media3's own stuck-player detection is kept as a last-resort backstop: the watchdog of
            // PlaybackController reacts within 1.5-4 s, long before these timeouts.
            .setStuckBufferingDetectionTimeoutMs(STUCK_BUFFERING_BACKSTOP_MS)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setName("fluide")
            .build()
        return Built(player, speedControl, config)
    }

    /** Optionally forces PCM output when the HDMI chain rejects passthrough (AC-3/E-AC-3/DTS bitstream). */
    private class FluideRenderersFactory(context: Context, private val pcmOnly: Boolean) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink? {
            if (!pcmOnly) return super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
            // Without a Context the sink uses only the capabilities given here: PCM, no passthrough.
            @Suppress("DEPRECATION")
            return DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }
    }

    private companion object {
        const val STUCK_BUFFERING_BACKSTOP_MS = 45_000
    }
}

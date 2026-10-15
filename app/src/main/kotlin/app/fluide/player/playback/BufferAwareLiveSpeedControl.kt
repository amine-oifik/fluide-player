package app.fluide.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LivePlaybackSpeedControl
import app.fluide.core.playback.AntiStallSpeedPolicy

/**
 * Media3's live speed control keeps the distance to the live edge on target. This wrapper adds the
 * anti-freeze rule on top: while the buffer is thin, playback is capped slightly below 1.0x (0.93-0.97,
 * pitch preserved, not perceptible) so the buffer refills instead of running dry. When the buffer is
 * comfortable the cap disappears and Media3 may catch up (up to its max speed) as usual.
 *
 * Called on the playback thread; [policy] is swapped from the main thread.
 */
@OptIn(UnstableApi::class)
class BufferAwareLiveSpeedControl(
    private val delegate: LivePlaybackSpeedControl,
    initialPolicy: AntiStallSpeedPolicy,
) : LivePlaybackSpeedControl {

    @Volatile
    var policy: AntiStallSpeedPolicy = initialPolicy

    /** Last speed returned, for the statistics overlay. */
    @Volatile
    var lastSpeed: Float = 1f
        private set

    private var cap = 1f

    override fun setLiveConfiguration(liveConfiguration: MediaItem.LiveConfiguration) {
        delegate.setLiveConfiguration(liveConfiguration)
        cap = 1f
    }

    override fun setTargetLiveOffsetOverrideUs(liveOffsetUs: Long) {
        delegate.setTargetLiveOffsetOverrideUs(liveOffsetUs)
    }

    override fun notifyRebuffer() {
        delegate.notifyRebuffer()
        // After a stall, restart gently: the buffer is at its lowest.
        cap = policy.minSpeed
    }

    override fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long): Float {
        val live = delegate.getAdjustedPlaybackSpeed(liveOffsetUs, bufferedDurationUs)
        cap = policy.nextSpeed(cap, bufferedDurationUs / 1_000)
        val speed = if (cap >= 1f) live else minOf(live, cap)
        lastSpeed = speed
        return speed
    }

    override fun getTargetLiveOffsetUs(): Long = delegate.targetLiveOffsetUs
}

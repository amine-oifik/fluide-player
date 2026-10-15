package app.fluide.player.playback

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlin.math.abs

/**
 * Finds the frame rate of the stream for auto frame rate. Uses the container value when there is one,
 * otherwise measures the median interval of the first [SAMPLES] presentation timestamps (MPEG-TS feeds
 * rarely declare a frame rate), then snaps it to the broadcast rates (23.976, 25, 29.97, 50, 59.94...).
 *
 * Frame callbacks arrive on the playback thread; the result is posted to the main thread once per stream.
 */
@OptIn(UnstableApi::class)
class FrameRateEstimator(private val onFrameRate: (Float) -> Unit) : VideoFrameMetadataListener {

    private val main = Handler(Looper.getMainLooper())
    private val deltas = LongArray(SAMPLES)

    @Volatile
    private var generation = 0
    private var sampledGeneration = -1
    private var count = 0
    private var lastPtsUs = Long.MIN_VALUE
    private var reported = false

    /** Call when a new stream starts (main thread). */
    fun reset() {
        generation++
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        val current = generation
        if (current != sampledGeneration) {
            sampledGeneration = current
            count = 0
            lastPtsUs = Long.MIN_VALUE
            reported = false
        }
        if (reported) return
        if (format.frameRate > 0f) {
            report(current, snap(format.frameRate))
            return
        }
        val last = lastPtsUs
        lastPtsUs = presentationTimeUs
        if (last == Long.MIN_VALUE) return
        val delta = presentationTimeUs - last
        if (delta <= 0 || delta > 200_000) return // discontinuity or reordering: ignore
        deltas[count++] = delta
        if (count == SAMPLES) {
            deltas.sort()
            report(current, snap(1_000_000f / deltas[SAMPLES / 2]))
        }
    }

    private fun report(forGeneration: Int, fps: Float) {
        reported = true
        main.post { if (generation == forGeneration) onFrameRate(fps) }
    }

    private companion object {
        const val SAMPLES = 48
        val BROADCAST_RATES = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f)

        fun snap(fps: Float): Float {
            val nearest = BROADCAST_RATES.minByOrNull { abs(it - fps) } ?: return fps
            return if (abs(nearest - fps) / nearest < 0.02f) nearest else fps
        }
    }
}

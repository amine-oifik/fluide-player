package app.fluide.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {

    private var now = 0L
    private var position = 0L
    private var frames = 0
    private var bytes = 0L
    private var dropped = 0L
    private var underruns = 0
    private val detector = StallDetector()

    private fun sample(
        phase: PlayerPhase = PlayerPhase.READY,
        playing: Boolean = phase == PlayerPhase.READY,
        wants: Boolean = true,
        renderedFrames: Int = frames,
    ) = PlaybackSample(now, phase, wants, playing, position, 3_000, renderedFrames, dropped, bytes, underruns)

    /** Advances time by [ms] in 500 ms ticks and collects every signal fired. */
    private fun run(ms: Long, advancePosition: Boolean, advanceFrames: Boolean, phase: PlayerPhase = PlayerPhase.READY,
                    advanceBytes: Boolean = true, wants: Boolean = true): List<StallSignal> {
        val fired = ArrayList<StallSignal>()
        repeat((ms / 500).toInt()) {
            now += 500
            if (advancePosition) position += 500
            if (advanceFrames) frames += 12
            if (advanceBytes) bytes += 10_000
            fired += detector.onSample(sample(phase = phase, wants = wants))
        }
        return fired
    }

    @Test
    fun healthyPlaybackFiresNothing() {
        assertTrue(run(30_000, advancePosition = true, advanceFrames = true).isEmpty())
    }

    @Test
    fun frozenPositionFiresAfterThresholdThenRefires() {
        run(2_000, advancePosition = true, advanceFrames = true)
        val fired = run(10_000, advancePosition = false, advanceFrames = false)
        assertEquals(StallSignal.POSITION_FROZEN, fired.first())
        // 1.5 s to detect, then again every 4 s while it lasts.
        assertEquals(3, fired.count { it == StallSignal.POSITION_FROZEN })
    }

    @Test
    fun pausedPlayerIsNotFrozen() {
        run(2_000, advancePosition = true, advanceFrames = true)
        val fired = ArrayList<StallSignal>()
        repeat(20) {
            now += 500
            fired += detector.onSample(sample(playing = false, wants = false))
        }
        assertTrue(fired.isEmpty())
    }

    @Test
    fun frozenPictureWithRunningAudioIsVideoFrozen() {
        run(2_000, advancePosition = true, advanceFrames = true)
        val fired = run(3_000, advancePosition = true, advanceFrames = false)
        assertEquals(listOf(StallSignal.VIDEO_FROZEN), fired.distinct())
    }

    @Test
    fun audioOnlyStreamNeverReportsVideoFrozen() {
        frames = 0
        val fired = run(10_000, advancePosition = true, advanceFrames = false)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun unknownFrameCountDisablesVideoCheck() {
        val fired = ArrayList<StallSignal>()
        repeat(20) {
            now += 500
            position += 500
            fired += detector.onSample(sample(renderedFrames = -1))
        }
        assertTrue(fired.isEmpty())
    }

    @Test
    fun longBufferingAndSilentNetworkAreDetected() {
        run(1_000, advancePosition = true, advanceFrames = true)
        val fired = run(5_000, advancePosition = false, advanceFrames = false, phase = PlayerPhase.BUFFERING, advanceBytes = false)
        assertTrue(StallSignal.NETWORK_SILENT in fired)
        assertTrue(StallSignal.BUFFERING_TOO_LONG in fired)
        assertTrue(fired.indexOf(StallSignal.NETWORK_SILENT) < fired.indexOf(StallSignal.BUFFERING_TOO_LONG))
    }

    @Test
    fun bufferingWithDataFlowingIsOnlyTooLongNotSilent() {
        val fired = run(5_000, advancePosition = false, advanceFrames = false, phase = PlayerPhase.BUFFERING, advanceBytes = true)
        assertEquals(listOf(StallSignal.BUFFERING_TOO_LONG), fired.distinct())
    }

    @Test
    fun repeatedUnderrunsMeanAudioStarving() {
        run(1_000, advancePosition = true, advanceFrames = true)
        val fired = ArrayList<StallSignal>()
        repeat(3) {
            underruns++
            fired += run(500, advancePosition = true, advanceFrames = true)
        }
        assertTrue(StallSignal.AUDIO_STARVING in fired)
    }

    @Test
    fun highDropRatioMeansFrameDropping() {
        val fired = ArrayList<StallSignal>()
        repeat(30) {
            now += 500
            position += 500
            frames += 10
            dropped += 3 // ~23 % dropped
            fired += detector.onSample(sample())
        }
        assertTrue(StallSignal.FRAME_DROPPING in fired)
    }

    @Test
    fun resetForgetsPreviousSession() {
        run(2_000, advancePosition = true, advanceFrames = true)
        run(1_000, advancePosition = false, advanceFrames = false)
        detector.reset()
        val fired = run(1_000, advancePosition = false, advanceFrames = false)
        assertTrue(fired.isEmpty())
    }
}

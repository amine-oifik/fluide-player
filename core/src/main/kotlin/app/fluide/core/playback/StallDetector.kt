package app.fluide.core.playback

/** What the watchdog has observed. Each signal maps to a different recovery. */
enum class StallSignal {
    /** READY and playing, but the position does not move: pipeline or timestamps blocked. */
    POSITION_FROZEN,

    /** The position (audio clock) moves but no video frame is rendered: frozen picture, sound OK. */
    VIDEO_FROZEN,

    /** BUFFERING for too long while the user wants to watch. */
    BUFFERING_TOO_LONG,

    /** BUFFERING and not a single byte received: network cut or silent server. */
    NETWORK_SILENT,

    /** Repeated audio underruns: the device cannot keep up. */
    AUDIO_STARVING,

    /** A large share of video frames dropped: the decoder is saturated. */
    FRAME_DROPPING,
}

/**
 * Multi-signal freeze detector. Pure logic: the platform layer feeds [PlaybackSample]s, the detector
 * returns the signals that crossed their threshold. A persisting condition fires again every
 * [Config.refireIntervalMs] so the recovery ladder can escalate.
 */
class StallDetector(private val config: Config = Config()) {

    data class Config(
        val positionFrozenMs: Long = 1_500,
        val videoFrozenMs: Long = 2_000,
        val bufferingTooLongMs: Long = 4_000,
        val networkSilentMs: Long = 3_000,
        val audioUnderrunWindowMs: Long = 30_000,
        val audioUnderrunThreshold: Int = 3,
        val dropWindowMs: Long = 10_000,
        val dropRatioThreshold: Double = 0.05,
        val minFramesForDropRatio: Long = 60,
        val refireIntervalMs: Long = 4_000,
    )

    private var lastPosition = Long.MIN_VALUE
    private var positionStillSince = NOT_SET
    private var lastFrames = Int.MIN_VALUE
    private var framesStillSince = NOT_SET
    private var sessionHasVideo = false
    private var bufferingSince = NOT_SET
    private var lastBytes = Long.MIN_VALUE
    private var bytesStillSince = NOT_SET
    private var lastUnderrunCount = 0
    private val underrunTimes = ArrayDeque<Long>()
    private val frameWindow = ArrayDeque<LongArray>() // [time, dropped, rendered]
    private val lastFired = LongArray(StallSignal.entries.size) { NOT_SET }

    /** Call when a new channel starts: all timers and counters belong to the previous session. */
    fun reset() {
        lastPosition = Long.MIN_VALUE
        positionStillSince = NOT_SET
        lastFrames = Int.MIN_VALUE
        framesStillSince = NOT_SET
        sessionHasVideo = false
        bufferingSince = NOT_SET
        lastBytes = Long.MIN_VALUE
        bytesStillSince = NOT_SET
        lastUnderrunCount = 0
        underrunTimes.clear()
        frameWindow.clear()
        lastFired.fill(NOT_SET)
    }

    fun onSample(sample: PlaybackSample): List<StallSignal> {
        val now = sample.nowMs
        var fired: MutableList<StallSignal>? = null
        fun fire(signal: StallSignal) {
            val last = lastFired[signal.ordinal]
            if (last == NOT_SET || now - last >= config.refireIntervalMs) {
                lastFired[signal.ordinal] = now
                (fired ?: ArrayList<StallSignal>(2).also { fired = it }).add(signal)
            }
        }
        fun clear(signal: StallSignal) {
            lastFired[signal.ordinal] = NOT_SET
        }

        // Position frozen while READY and playing.
        if (sample.isPlaying) {
            if (sample.positionMs == lastPosition) {
                if (positionStillSince == NOT_SET) positionStillSince = now
            } else {
                positionStillSince = NOT_SET
            }
        } else {
            positionStillSince = NOT_SET
        }
        lastPosition = sample.positionMs
        if (positionStillSince != NOT_SET && now - positionStillSince >= config.positionFrozenMs) {
            fire(StallSignal.POSITION_FROZEN)
        } else {
            clear(StallSignal.POSITION_FROZEN)
        }

        // Video frozen while the audio clock advances. Only for sessions that have shown video.
        val frames = sample.renderedFrames
        if (frames > 0) sessionHasVideo = true
        if (sample.isPlaying && frames >= 0 && sessionHasVideo && positionStillSince == NOT_SET) {
            if (frames == lastFrames) {
                if (framesStillSince == NOT_SET) framesStillSince = now
            } else {
                framesStillSince = NOT_SET
            }
        } else {
            framesStillSince = NOT_SET
        }
        lastFrames = frames
        if (framesStillSince != NOT_SET && now - framesStillSince >= config.videoFrozenMs) {
            fire(StallSignal.VIDEO_FROZEN)
        } else {
            clear(StallSignal.VIDEO_FROZEN)
        }

        // Buffering too long, and buffering with no data at all.
        val stalledBuffering = sample.phase == PlayerPhase.BUFFERING && sample.wantsToPlay
        if (stalledBuffering) {
            if (bufferingSince == NOT_SET) bufferingSince = now
            if (sample.bytesLoaded == lastBytes) {
                if (bytesStillSince == NOT_SET) bytesStillSince = now
            } else {
                bytesStillSince = NOT_SET
            }
        } else {
            bufferingSince = NOT_SET
            bytesStillSince = NOT_SET
        }
        lastBytes = sample.bytesLoaded
        if (bufferingSince != NOT_SET && now - bufferingSince >= config.bufferingTooLongMs) {
            fire(StallSignal.BUFFERING_TOO_LONG)
        } else {
            clear(StallSignal.BUFFERING_TOO_LONG)
        }
        if (bytesStillSince != NOT_SET && now - bytesStillSince >= config.networkSilentMs) {
            fire(StallSignal.NETWORK_SILENT)
        } else {
            clear(StallSignal.NETWORK_SILENT)
        }

        // Audio underruns within a sliding window.
        val newUnderruns = sample.audioUnderruns - lastUnderrunCount
        if (newUnderruns > 0) repeat(minOf(newUnderruns, 16)) { underrunTimes.addLast(now) }
        lastUnderrunCount = sample.audioUnderruns
        while (underrunTimes.isNotEmpty() && now - underrunTimes.first() > config.audioUnderrunWindowMs) {
            underrunTimes.removeFirst()
        }
        if (underrunTimes.size >= config.audioUnderrunThreshold) {
            fire(StallSignal.AUDIO_STARVING)
        } else {
            clear(StallSignal.AUDIO_STARVING)
        }

        // Dropped-frame ratio over a sliding window.
        if (frames >= 0) {
            frameWindow.addLast(longArrayOf(now, sample.droppedFrames, frames.toLong()))
            while (frameWindow.size > 2 && now - frameWindow.first()[0] > config.dropWindowMs) {
                frameWindow.removeFirst()
            }
            val oldest = frameWindow.first()
            if (now - oldest[0] >= config.dropWindowMs / 2) {
                val dropped = sample.droppedFrames - oldest[1]
                val rendered = frames - oldest[2]
                val total = dropped + rendered
                if (total >= config.minFramesForDropRatio && dropped > total * config.dropRatioThreshold) {
                    fire(StallSignal.FRAME_DROPPING)
                } else {
                    clear(StallSignal.FRAME_DROPPING)
                }
            }
        }

        return fired ?: emptyList()
    }

    private companion object {
        const val NOT_SET = Long.MIN_VALUE
    }
}

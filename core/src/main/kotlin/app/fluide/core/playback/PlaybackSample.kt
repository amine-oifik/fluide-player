package app.fluide.core.playback

enum class PlayerPhase { IDLE, BUFFERING, READY, ENDED }

/**
 * One snapshot of the player, taken every ~500 ms by the platform layer.
 *
 * All counters are cumulative since the start of the session; times use a monotonic clock
 * (never wall-clock time, which can jump when the TV synchronises its clock).
 */
data class PlaybackSample(
    val nowMs: Long,
    val phase: PlayerPhase,
    /** The user wants playback (playWhenReady). */
    val wantsToPlay: Boolean,
    /** READY, wanted, and not suppressed (audio focus, unsuitable output...). */
    val isPlaying: Boolean,
    val positionMs: Long,
    val bufferedMs: Long,
    /** Video frames rendered so far, or -1 when unknown (for example in tunneled mode). */
    val renderedFrames: Int,
    val droppedFrames: Long,
    val bytesLoaded: Long,
    val audioUnderruns: Int,
)

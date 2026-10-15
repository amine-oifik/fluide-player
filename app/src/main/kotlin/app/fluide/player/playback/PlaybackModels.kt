package app.fluide.player.playback

import app.fluide.core.playback.StabilityTier

enum class PlaybackPhase { IDLE, STARTING, PLAYING, BUFFERING, PAUSED, ERROR }

enum class ErrorKind { SIGNAL_LOST, ACCESS_DENIED, NOT_FOUND, HTTP, DECODER, DRM, FORMAT, NETWORK }

/**
 * @property retryAtMs elapsedRealtime of the automatic retry, 0 when the user must retry.
 */
data class PlaybackError(val kind: ErrorKind, val httpCode: Int = 0, val retryAtMs: Long = 0)

enum class Notice { BACKUP_SOURCE }

data class PlaybackUiState(
    val channelId: Long = 0,
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    /** elapsedRealtime when the current wait began, 0 when not waiting. The UI shows a spinner only after a delay. */
    val waitingSinceMs: Long = 0,
    val error: PlaybackError? = null,
    val notice: Notice? = null,
    val noticeAtMs: Long = 0,
    /** Frame rate of the stream once known (for auto frame rate), 0 otherwise. */
    val contentFrameRate: Float = 0f,
)

/** "Stats for nerds" overlay. Pulled once per second while visible, never computed otherwise. */
data class PlaybackStats(
    val video: String,
    val audio: String,
    val decoder: String?,
    val bufferMs: Long,
    val liveOffsetMs: Long,
    val speed: Float,
    val bandwidthKbps: Long,
    val droppedFrames: Long,
    val tier: StabilityTier,
    val ladderLevel: Int,
    val startupMs: Long,
    val rebuffers: Int,
    val rebufferMs: Long,
    val recoveries: Int,
    val host: String,
    val tunneling: Boolean,
    val backupSource: Boolean,
)

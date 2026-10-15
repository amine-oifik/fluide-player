package app.fluide.core.playback

/** How reliable a source has proven to be. Learned per source by [SourceHealthTracker]. */
enum class StabilityTier { STABLE, UNSTABLE, HOSTILE }

/**
 * Playback tuning for a stability tier: the more a source stutters, the more the player trades
 * latency and start time for smoothness, so that freezes stop being visible.
 */
data class StabilityProfile(
    val tier: StabilityTier,
    /** Media buffered before the first picture after a zap. */
    val startBufferMs: Int,
    /** Media buffered before resuming after a rebuffer: higher avoids rebuffer loops. */
    val resumeBufferMs: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    /** Extra distance from the live edge, giving room for a bigger buffer on live streams. */
    val liveExtraOffsetMs: Long,
    val liveMinSpeed: Float,
    val liveMaxSpeed: Float,
    val speedPolicy: AntiStallSpeedPolicy,
    /** Share of the measured bandwidth the adaptive bitrate logic may use. */
    val bandwidthFraction: Float,
    val allowTunneling: Boolean,
    /** Start on the lowest rendition and climb, instead of guessing high and rebuffering. */
    val startAtLowestQuality: Boolean,
) {
    init {
        require(minBufferMs >= startBufferMs && minBufferMs >= resumeBufferMs) { "minBuffer below start thresholds" }
        require(maxBufferMs >= minBufferMs) { "maxBuffer below minBuffer" }
        require(liveMinSpeed <= 1f && liveMaxSpeed >= 1f)
    }

    companion object {
        val STABLE = StabilityProfile(
            tier = StabilityTier.STABLE,
            startBufferMs = 1_000,
            resumeBufferMs = 2_500,
            minBufferMs = 15_000,
            maxBufferMs = 30_000,
            liveExtraOffsetMs = 0,
            liveMinSpeed = 0.97f,
            liveMaxSpeed = 1.03f,
            speedPolicy = AntiStallSpeedPolicy(comfortMs = 3_000, criticalMs = 800, minSpeed = 0.97f),
            bandwidthFraction = 0.75f,
            allowTunneling = true,
            startAtLowestQuality = false,
        )

        val UNSTABLE = StabilityProfile(
            tier = StabilityTier.UNSTABLE,
            startBufferMs = 1_500,
            resumeBufferMs = 4_000,
            minBufferMs = 25_000,
            maxBufferMs = 50_000,
            liveExtraOffsetMs = 6_000,
            liveMinSpeed = 0.96f,
            liveMaxSpeed = 1.02f,
            speedPolicy = AntiStallSpeedPolicy(comfortMs = 6_000, criticalMs = 1_500, minSpeed = 0.95f),
            bandwidthFraction = 0.65f,
            allowTunneling = false,
            startAtLowestQuality = true,
        )

        val HOSTILE = StabilityProfile(
            tier = StabilityTier.HOSTILE,
            startBufferMs = 2_500,
            resumeBufferMs = 6_000,
            minBufferMs = 40_000,
            maxBufferMs = 60_000,
            liveExtraOffsetMs = 12_000,
            liveMinSpeed = 0.95f,
            liveMaxSpeed = 1.01f,
            speedPolicy = AntiStallSpeedPolicy(comfortMs = 10_000, criticalMs = 2_500, minSpeed = 0.93f),
            bandwidthFraction = 0.55f,
            allowTunneling = false,
            startAtLowestQuality = true,
        )

        fun forTier(tier: StabilityTier): StabilityProfile = when (tier) {
            StabilityTier.STABLE -> STABLE
            StabilityTier.UNSTABLE -> UNSTABLE
            StabilityTier.HOSTILE -> HOSTILE
        }
    }
}

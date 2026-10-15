package app.fluide.core.playback

import kotlin.math.pow

/**
 * Learns how reliable each source is and picks its [StabilityTier].
 *
 * Each rebuffer or failure adds to a score that decays with a half-life, so old incidents fade.
 * Downgrades are immediate (protect the viewer now); upgrades need a long clean period (a source
 * that stuttered five minutes ago is not trusted again after thirty seconds).
 */
class SourceHealthTracker(private val config: Config = Config()) {

    data class Config(
        val halfLifeMs: Long = 30 * 60_000,
        val unstableScore: Double = 2.0,
        val hostileScore: Double = 5.0,
        val cleanTimeToUpgradeMs: Long = 20 * 60_000,
    )

    data class Snapshot(val key: String, val score: Double, val tier: StabilityTier, val updatedAtMs: Long)

    private class Health(var score: Double, var updatedAt: Long, var tier: StabilityTier, var lastIncidentAt: Long)

    private val sources = HashMap<String, Health>()

    fun tierFor(key: String, nowMs: Long): StabilityTier {
        val health = sources[key] ?: return StabilityTier.STABLE
        decay(health, nowMs)
        reclassify(health, nowMs)
        return health.tier
    }

    fun profileFor(key: String, nowMs: Long): StabilityProfile = StabilityProfile.forTier(tierFor(key, nowMs))

    /** A visible rebuffer: weight grows with its duration (a 10 s freeze is worse than a 1 s one). */
    fun recordRebuffer(key: String, durationMs: Long, nowMs: Long) =
        add(key, 1.0 + (durationMs / 5_000.0).coerceAtMost(2.0), nowMs)

    /** A recovery that needed escalation, or a failed start. */
    fun recordFailure(key: String, nowMs: Long) = add(key, 2.0, nowMs)

    /** A silent recovery (no visible freeze): small weight, still a sign of fragility. */
    fun recordSilentRecovery(key: String, nowMs: Long) = add(key, 0.5, nowMs)

    fun snapshot(): List<Snapshot> = sources.map { (key, h) -> Snapshot(key, h.score, h.tier, h.updatedAt) }

    fun restore(snapshots: List<Snapshot>) {
        for (s in snapshots) sources[s.key] = Health(s.score, s.updatedAtMs, s.tier, s.updatedAtMs)
    }

    private fun add(key: String, weight: Double, nowMs: Long) {
        val health = sources.getOrPut(key) { Health(0.0, nowMs, StabilityTier.STABLE, nowMs) }
        decay(health, nowMs)
        health.score += weight
        health.lastIncidentAt = nowMs
        reclassify(health, nowMs)
    }

    private fun decay(health: Health, nowMs: Long) {
        val elapsed = nowMs - health.updatedAt
        if (elapsed > 0) {
            health.score *= 0.5.pow(elapsed.toDouble() / config.halfLifeMs)
            health.updatedAt = nowMs
        } else if (elapsed < 0) {
            health.updatedAt = nowMs // clock moved backwards: restart from now without decaying
        }
    }

    private fun reclassify(health: Health, nowMs: Long) {
        val measured = when {
            health.score >= config.hostileScore -> StabilityTier.HOSTILE
            health.score >= config.unstableScore -> StabilityTier.UNSTABLE
            else -> StabilityTier.STABLE
        }
        if (measured.ordinal > health.tier.ordinal) {
            health.tier = measured
        } else if (measured.ordinal < health.tier.ordinal &&
            nowMs - health.lastIncidentAt >= config.cleanTimeToUpgradeMs
        ) {
            // Climb back one tier at a time.
            health.tier = StabilityTier.entries[health.tier.ordinal - 1]
            health.lastIncidentAt = nowMs
        }
    }
}

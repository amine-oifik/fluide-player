package app.fluide.core.net

import kotlin.random.Random

/**
 * Exponential backoff with jitter: base × 2^(attempt-1), capped, plus a random 0..jitter.
 * Jitter keeps thousands of TVs from retrying at the same instant after an outage.
 */
class Backoff(
    val baseMs: Long,
    val maxMs: Long,
    val jitterMs: Long = 0,
    private val random: (Long) -> Long = { bound -> if (bound <= 0) 0 else Random.nextLong(bound) },
) {
    init {
        require(baseMs > 0 && maxMs >= baseMs && jitterMs >= 0)
    }

    /** Delay before retry number [attempt] (1-based). */
    fun delayFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 30)
        val exponential = if (baseMs > (maxMs shr shift)) maxMs else minOf(maxMs, baseMs shl shift)
        return exponential + random(jitterMs + 1)
    }
}

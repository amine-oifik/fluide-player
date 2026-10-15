package app.fluide.core.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Invisible freeze" speed policy.
 *
 * When the buffer drains, playing a few percent slower (pitch preserved by the player's time
 * stretcher) gives the network time to catch up: a 0.95× speed adds 3 s of margin per minute, and
 * the viewer does not notice it, whereas a rebuffer is always visible. Above [comfortMs] playback is
 * at normal speed; at [criticalMs] and below it reaches [minSpeed]; in between it is interpolated.
 */
class AntiStallSpeedPolicy(
    val comfortMs: Long,
    val criticalMs: Long,
    val minSpeed: Float,
    private val step: Float = 0.01f,
    private val hysteresis: Float = 0.015f,
) {
    init {
        require(comfortMs > criticalMs && criticalMs >= 0) { "comfortMs must exceed criticalMs" }
        require(minSpeed > 0.5f && minSpeed <= 1f) { "minSpeed out of range" }
    }

    /** Highest speed allowed for this buffer level. Never above 1: this policy only slows down. */
    fun maxSpeedFor(bufferedMs: Long): Float {
        if (bufferedMs >= comfortMs) return 1f
        if (bufferedMs <= criticalMs) return minSpeed
        val t = (bufferedMs - criticalMs).toFloat() / (comfortMs - criticalMs)
        return quantize(minSpeed + (1f - minSpeed) * t)
    }

    /** Speed to apply now, given the current one: changes only by meaningful steps to avoid wobble. */
    fun nextSpeed(currentSpeed: Float, bufferedMs: Long): Float {
        val target = maxSpeedFor(bufferedMs)
        return when {
            target == 1f -> 1f
            abs(target - currentSpeed) >= hysteresis -> target
            else -> currentSpeed
        }
    }

    private fun quantize(speed: Float): Float = ((speed / step).roundToInt() * step).coerceIn(minSpeed, 1f)
}

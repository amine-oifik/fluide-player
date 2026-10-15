package app.fluide.core.display

import kotlin.math.abs
import kotlin.math.roundToInt

data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * Picks the display mode whose refresh rate is a whole multiple of the content frame rate.
 *
 * 50 fps sport on a 60 Hz output repeats one frame in five: a regular stutter viewers call a
 * "micro-freeze" even with a perfect network. Switching to 50 Hz removes it.
 */
object DisplayModeSelector {
    private const val TOLERANCE_HZ = 0.15f

    /** Returns the id of the mode to switch to, or null to keep the current mode. */
    fun select(contentFps: Float, current: DisplayModeInfo, modes: List<DisplayModeInfo>): Int? {
        if (contentFps < 10f || contentFps > 120f) return null
        if (multipleOf(current.refreshRate, contentFps) != null) return null
        return modes
            .filter { it.width == current.width && it.height == current.height }
            .mapNotNull { mode -> multipleOf(mode.refreshRate, contentFps)?.let { k -> mode to k } }
            .minWithOrNull(compareBy<Pair<DisplayModeInfo, Int>>({ it.second }, { abs(it.first.refreshRate - it.second * contentFps) }))
            ?.first
            ?.id
    }

    /** The integer k (1..5) with refresh ≈ k × fps, or null. */
    fun multipleOf(refreshRate: Float, fps: Float): Int? {
        val k = (refreshRate / fps).roundToInt()
        if (k !in 1..5) return null
        return if (abs(refreshRate - k * fps) <= TOLERANCE_HZ * k) k else null
    }
}

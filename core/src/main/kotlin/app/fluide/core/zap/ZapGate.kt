package app.fluide.core.zap

/**
 * Decides when a channel change should start loading.
 *
 * The first press loads immediately (no artificial delay). During a burst of presses, a channel only
 * loads if it stays on screen for [dwellMs]: we do not open and kill ten streams while the user
 * scrolls, which would waste bandwidth and, with providers limited to one connection, get the
 * account temporarily blocked. Connections and manifests are still warmed during the burst.
 */
class ZapGate(
    private val dwellMs: Long = 300,
    private val burstWindowMs: Long = 600,
) {
    private var lastPressAt = Long.MIN_VALUE

    /** Registers a zap key press; returns the delay before loading should start (0 = now). */
    fun onPress(nowMs: Long): Long {
        val inBurst = lastPressAt != Long.MIN_VALUE && nowMs - lastPressAt < burstWindowMs
        lastPressAt = nowMs
        return if (inBurst) dwellMs else 0
    }

    fun reset() {
        lastPressAt = Long.MIN_VALUE
    }
}

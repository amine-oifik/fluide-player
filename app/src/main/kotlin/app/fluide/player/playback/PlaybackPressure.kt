package app.fluide.player.playback

/**
 * Lets background work (playlist / EPG imports) yield to the player. The controller publishes its buffer
 * level; importers call [throttle] between batches and pause while the buffer is low, so an import can
 * never be the reason a stream freezes on a weak box.
 */
class PlaybackPressure {
    @Volatile
    var bufferedMs: Long = Long.MAX_VALUE

    @Volatile
    var playing: Boolean = false

    val isHigh: Boolean get() = playing && bufferedMs < LOW_BUFFER_MS

    /** Blocks up to [MAX_WAIT_MS] while playback is fragile. Never throws. */
    fun throttle() {
        var waited = 0L
        while (isHigh && waited < MAX_WAIT_MS) {
            try {
                Thread.sleep(STEP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            waited += STEP_MS
        }
    }

    private companion object {
        const val LOW_BUFFER_MS = 5_000L
        const val STEP_MS = 100L
        const val MAX_WAIT_MS = 10_000L
    }
}

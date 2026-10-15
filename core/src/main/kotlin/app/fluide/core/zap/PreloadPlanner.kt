package app.fluide.core.zap

/** How much is prepared for a neighbouring channel. */
enum class PreloadLevel {
    /** P1: DNS resolved and host reachable. Costs nothing on the provider side. */
    DNS,

    /** P2: HLS/DASH manifests fetched and kept fresh. Uses an extra request. */
    MANIFEST,

    /** P3: a second player prepared and paused on the channel. Uses an extra stream connection. */
    PLAYER,
}

data class PreloadTarget(val index: Int, val level: PreloadLevel)

/**
 * Chooses which channels to warm up around the current one.
 *
 * Next/previous in the direction of the last zap get the most, the recall channel and the channel
 * focused in the list get manifests, and nothing ever exceeds what the device and the source allow.
 */
object PreloadPlanner {

    fun plan(
        currentIndex: Int,
        channelCount: Int,
        lastZapDirection: Int,
        recallIndex: Int?,
        focusedIndex: Int?,
        deviceMaxLevel: PreloadLevel,
        sourceAllowsExtraConnections: Boolean,
    ): List<PreloadTarget> {
        if (channelCount <= 1 || currentIndex !in 0 until channelCount) return emptyList()
        val cap = if (sourceAllowsExtraConnections) deviceMaxLevel else PreloadLevel.DNS
        val result = LinkedHashMap<Int, PreloadLevel>()

        fun offer(index: Int?, wanted: PreloadLevel) {
            if (index == null || index == currentIndex || index !in 0 until channelCount) return
            val level = minOf(wanted, cap)
            val existing = result[index]
            if (existing == null || level > existing) result[index] = level
        }

        val forward = if (lastZapDirection < 0) -1 else 1
        offer(wrap(currentIndex + forward, channelCount), PreloadLevel.PLAYER)
        offer(wrap(currentIndex - forward, channelCount), PreloadLevel.MANIFEST)
        offer(recallIndex, PreloadLevel.MANIFEST)
        offer(focusedIndex, PreloadLevel.MANIFEST)
        offer(wrap(currentIndex + 2 * forward, channelCount), PreloadLevel.DNS)

        return result.entries
            .sortedByDescending { it.value }
            .map { PreloadTarget(it.key, it.value) }
    }

    private fun wrap(index: Int, count: Int): Int = ((index % count) + count) % count
}

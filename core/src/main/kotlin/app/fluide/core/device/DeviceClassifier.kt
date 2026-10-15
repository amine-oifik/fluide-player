package app.fluide.core.device

import app.fluide.core.zap.PreloadLevel

enum class DeviceTier { A_LOW, B_MID, C_HIGH }

/** Resource budgets for one class of device (section 10 of the architecture document). */
data class DeviceBudget(
    val tier: DeviceTier,
    /** Byte cap for the player buffer. */
    val targetBufferBytes: Int,
    /** Time thresholds may override the byte cap only where memory allows it. */
    val prioritizeTimeOverSize: Boolean,
    val imageMemoryCacheBytes: Long,
    val epgPastDays: Int,
    val epgFutureDays: Int,
    val maxPreload: PreloadLevel,
    val tunnelingCandidate: Boolean,
)

object DeviceClassifier {
    private const val GB = 1024L * 1024 * 1024
    private const val MB = 1024 * 1024

    fun classify(totalRamBytes: Long, isLowRamDevice: Boolean, videoDecoderInstances: Int): DeviceBudget = when {
        isLowRamDevice || totalRamBytes in 1..(1_200L * 1024 * 1024) -> DeviceBudget(
            tier = DeviceTier.A_LOW,
            targetBufferBytes = 20 * MB,
            prioritizeTimeOverSize = false,
            imageMemoryCacheBytes = 16L * MB,
            epgPastDays = 1,
            epgFutureDays = 2,
            maxPreload = PreloadLevel.DNS,
            tunnelingCandidate = true,
        )
        totalRamBytes < 2_500L * 1024 * 1024 || videoDecoderInstances < 2 -> DeviceBudget(
            tier = DeviceTier.B_MID,
            targetBufferBytes = 40 * MB,
            prioritizeTimeOverSize = true,
            imageMemoryCacheBytes = 32L * MB,
            epgPastDays = 3,
            epgFutureDays = 4,
            maxPreload = PreloadLevel.MANIFEST,
            tunnelingCandidate = true,
        )
        else -> DeviceBudget(
            tier = DeviceTier.C_HIGH,
            targetBufferBytes = if (totalRamBytes >= 4 * GB) 96 * MB else 64 * MB,
            prioritizeTimeOverSize = true,
            imageMemoryCacheBytes = 64L * MB,
            epgPastDays = 7,
            epgFutureDays = 7,
            maxPreload = PreloadLevel.PLAYER,
            tunnelingCandidate = true,
        )
    }
}

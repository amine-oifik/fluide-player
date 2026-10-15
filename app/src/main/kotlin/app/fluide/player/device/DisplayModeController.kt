package app.fluide.player.device

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import app.fluide.core.display.DisplayModeInfo
import app.fluide.core.display.DisplayModeSelector

/**
 * Auto frame rate (AFR): switches the TV to a refresh rate that is a multiple of the content frame rate
 * (50 Hz for 25/50 fps channels, 24 Hz for films), which removes the regular judder of 25 fps on 60 Hz.
 *
 * Respects the system "match content frame rate" preference on Android 12+: never switches when the
 * user chose "never", and only seamless switches when they chose "seamless only".
 */
class DisplayModeController(private val activity: Activity) {

    private var appliedModeId = 0

    fun apply(contentFps: Float) {
        if (contentFps <= 0f) return
        val display = currentDisplay() ?: return
        val current = display.mode
        val modes = display.supportedModes
        val targetId = DisplayModeSelector.select(
            contentFps,
            current.toInfo(),
            modes.map { it.toInfo() },
        ) ?: return
        val target = modes.firstOrNull { it.modeId == targetId } ?: return
        if (!userAllows(current, target)) return
        if (appliedModeId == targetId) return
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = targetId
        activity.window.attributes = attributes
        appliedModeId = targetId
    }

    /** Gives the display back to the system default mode (leaving the player, app in background). */
    fun reset() {
        if (appliedModeId == 0) return
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = 0
        activity.window.attributes = attributes
        appliedModeId = 0
    }

    private fun userAllows(current: Display.Mode, target: Display.Mode): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val manager = activity.getSystemService(DisplayManager::class.java) ?: return true
        return when (manager.matchContentFrameRateUserPreference) {
            DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER -> false
            DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY ->
                current.physicalWidth == target.physicalWidth &&
                    current.physicalHeight == target.physicalHeight &&
                    current.alternativeRefreshRates.any { kotlin.math.abs(it - target.refreshRate) < 0.01f }
            else -> true
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay

    private fun Display.Mode.toInfo() = DisplayModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)
}

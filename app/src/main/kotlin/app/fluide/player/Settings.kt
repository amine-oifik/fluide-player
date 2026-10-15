package app.fluide.player

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsValues(
    val resumeLastChannel: Boolean = true,
    /** Switch the display refresh rate to the content (25/50 fps on a 60 Hz TV judders). */
    val autoFrameRate: Boolean = false,
    /** Tunneled playback: off by default, too many boxes have broken implementations. */
    val tunneling: Boolean = false,
    val audioPassthrough: Boolean = true,
    val showStats: Boolean = false,
    val activeSourceId: Long = 0,
    val lastChannelName: String? = null,
)

/** Small key-value settings, exposed as a flow for the UI. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val values: StateFlow<SettingsValues> = state.asStateFlow()

    val current: SettingsValues get() = state.value

    @Synchronized
    fun update(transform: (SettingsValues) -> SettingsValues) {
        val next = transform(state.value)
        if (next == state.value) return
        prefs.edit()
            .putBoolean(KEY_RESUME, next.resumeLastChannel)
            .putBoolean(KEY_AFR, next.autoFrameRate)
            .putBoolean(KEY_TUNNELING, next.tunneling)
            .putBoolean(KEY_PASSTHROUGH, next.audioPassthrough)
            .putBoolean(KEY_STATS, next.showStats)
            .putLong(KEY_ACTIVE_SOURCE, next.activeSourceId)
            .putString(KEY_LAST_CHANNEL, next.lastChannelName)
            .apply()
        state.value = next
    }

    private fun read() = SettingsValues(
        resumeLastChannel = prefs.getBoolean(KEY_RESUME, true),
        autoFrameRate = prefs.getBoolean(KEY_AFR, false),
        tunneling = prefs.getBoolean(KEY_TUNNELING, false),
        audioPassthrough = prefs.getBoolean(KEY_PASSTHROUGH, true),
        showStats = prefs.getBoolean(KEY_STATS, false),
        activeSourceId = prefs.getLong(KEY_ACTIVE_SOURCE, 0),
        lastChannelName = prefs.getString(KEY_LAST_CHANNEL, null),
    )

    private companion object {
        const val KEY_RESUME = "resume_last_channel"
        const val KEY_AFR = "auto_frame_rate"
        const val KEY_TUNNELING = "tunneling"
        const val KEY_PASSTHROUGH = "audio_passthrough"
        const val KEY_STATS = "show_stats"
        const val KEY_ACTIVE_SOURCE = "active_source"
        const val KEY_LAST_CHANNEL = "last_channel"
    }
}

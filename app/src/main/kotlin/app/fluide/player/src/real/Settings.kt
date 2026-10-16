package app.fluide.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the picture fills the screen (PlayerView resize modes). */
enum class AspectMode { FIT, FILL, ZOOM }

data class SettingsValues(
    /** Start on the last channel instead of the home screen. */
    val resumeLastChannel: Boolean = false,
    /** Switch the display refresh rate to the content (25/50 fps on a 60 Hz TV judders). */
    val autoFrameRate: Boolean = false,
    /** Tunneled playback: off by default, too many boxes have broken implementations. */
    val tunneling: Boolean = false,
    val audioPassthrough: Boolean = true,
    val showStats: Boolean = false,
    val activeSourceId: Long = 0,
    val lastChannelName: String? = null,
    /** Parental control: categories recognised as adult (and the ones locked by hand) need the PIN. */
    val parentalEnabled: Boolean = true,
    val parentalPin: String = DEFAULT_PIN,
    val aspectMode: AspectMode = AspectMode.FIT,
    /** Series: the next episode starts by itself at the end of the current one. */
    val autoNextEpisode: Boolean = true,
    /** Open the app when the box starts. */
    val startOnBoot: Boolean = false,
    /** Hours of the clock: true 24 h, false 12 h. */
    val clock24h: Boolean = true,
    /** Interface language as a BCP 47 tag: "" follows the system, otherwise one of [LANGUAGES]. */
    val language: String = "",
) {
    companion object {
        const val DEFAULT_PIN = "0000"

        /** Languages offered in the settings, in their cycling order ("" = system language). */
        val LANGUAGES: List<String> = listOf("", "fr", "en", "ar")
    }
}

/** Small key-value settings, exposed as a flow for the UI. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
            .putBoolean(KEY_PARENTAL, next.parentalEnabled)
            .putString(KEY_PIN, next.parentalPin)
            .putString(KEY_ASPECT, next.aspectMode.name)
            .putBoolean(KEY_AUTO_NEXT, next.autoNextEpisode)
            .putBoolean(KEY_BOOT, next.startOnBoot)
            .putBoolean(KEY_CLOCK, next.clock24h)
            .putString(KEY_LANGUAGE, next.language)
            .apply()
        state.value = next
    }

    private fun read() = SettingsValues(
        resumeLastChannel = prefs.getBoolean(KEY_RESUME, false),
        autoFrameRate = prefs.getBoolean(KEY_AFR, false),
        tunneling = prefs.getBoolean(KEY_TUNNELING, false),
        audioPassthrough = prefs.getBoolean(KEY_PASSTHROUGH, true),
        showStats = prefs.getBoolean(KEY_STATS, false),
        activeSourceId = prefs.getLong(KEY_ACTIVE_SOURCE, 0),
        lastChannelName = prefs.getString(KEY_LAST_CHANNEL, null),
        parentalEnabled = prefs.getBoolean(KEY_PARENTAL, true),
        parentalPin = prefs.getString(KEY_PIN, null)?.takeIf { it.length == 4 } ?: SettingsValues.DEFAULT_PIN,
        aspectMode = prefs.getString(KEY_ASPECT, null)?.let { name -> AspectMode.entries.firstOrNull { it.name == name } }
            ?: AspectMode.FIT,
        autoNextEpisode = prefs.getBoolean(KEY_AUTO_NEXT, true),
        startOnBoot = prefs.getBoolean(KEY_BOOT, false),
        clock24h = prefs.getBoolean(KEY_CLOCK, true),
        language = storedLanguage(prefs),
    )

    companion object {
        private const val PREFS_NAME = "settings"
        private const val KEY_RESUME = "resume_last_channel"
        private const val KEY_AFR = "auto_frame_rate"
        private const val KEY_TUNNELING = "tunneling"
        private const val KEY_PASSTHROUGH = "audio_passthrough"
        private const val KEY_STATS = "show_stats"
        private const val KEY_ACTIVE_SOURCE = "active_source"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_PARENTAL = "parental_enabled"
        private const val KEY_PIN = "parental_pin"
        private const val KEY_ASPECT = "aspect_mode"
        private const val KEY_AUTO_NEXT = "auto_next_episode"
        private const val KEY_BOOT = "start_on_boot"
        private const val KEY_CLOCK = "clock_24h"
        private const val KEY_LANGUAGE = "language"

        /**
         * The interface language ("" = system) read straight from the preferences. MainActivity needs it in
         * attachBaseContext, before the view model and the [Settings] instance exist.
         */
        fun storedLanguage(context: Context): String =
            storedLanguage(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        private fun storedLanguage(prefs: SharedPreferences): String =
            prefs.getString(KEY_LANGUAGE, null)?.takeIf { it in SettingsValues.LANGUAGES }.orEmpty()
    }
}

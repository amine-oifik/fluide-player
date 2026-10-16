package app.fluide.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.fluide.player.device.DisplayModeController
import app.fluide.player.ui.FluideRoot
import app.fluide.player.ui.KeyResult
import app.fluide.player.ui.MainViewModel
import app.fluide.player.ui.theme.FluideTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var displayModes: DisplayModeController

    /** Keys whose ACTION_DOWN was consumed: their ACTION_UP is consumed too. */
    private val consumedKeys = HashSet<Int>()

    /**
     * In-app language: the activity resources follow the language chosen in the settings (read straight from
     * the preferences, the view model does not exist yet). The settings screen recreates the activity after
     * a change.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayModes = DisplayModeController(this)
        enterImmersiveMode()
        // Back goes through the dispatcher: with predictive back (default for apps targeting
        // Android 16) the BACK key is no longer delivered to dispatchKeyEvent.
        onBackPressedDispatcher.addCallback(this) {
            if (viewModel.onBack() == KeyResult.EXIT) finish()
        }
        setContent {
            FluideTheme {
                FluideRoot(viewModel)
            }
        }
        // Auto frame rate: follow the content frame rate while the setting is on.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.playback, viewModel.settings) { playback, settings ->
                    if (settings.autoFrameRate) playback.contentFrameRate else -1f
                }
                    .distinctUntilChanged()
                    .collect { fps ->
                        when {
                            fps > 0f -> displayModes.apply(fps)
                            fps < 0f -> displayModes.reset()
                        }
                    }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        viewModel.onStop()
        displayModes.reset()
        super.onStop()
    }

    /**
     * Phones and tablets: leaving the app (Home, recent apps) while a stream plays full screen keeps it
     * playing in a small picture-in-picture window. Not on TV, where Home must stop the playback.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !isInPictureInPictureMode &&
            viewModel.isPlayingFullScreen() &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !viewModel.device.isTv
        ) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            // Throws when the system refuses (PiP disabled for the app in the system settings...).
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    /** The overlays (banner, controls, options) are hidden in the small window. */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        viewModel.setPictureInPicture(isInPictureInPictureMode)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * Remote keys go to the view model first: zapping must not depend on which Compose element has the
     * focus. Keys it does not use continue to Compose (guide and sources navigation).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when (viewModel.onKeyDown(event.keyCode)) {
                KeyResult.HANDLED -> {
                    consumedKeys += event.keyCode
                    return true
                }
                KeyResult.EXIT -> {
                    finish()
                    return true
                }
                KeyResult.IGNORED -> consumedKeys -= event.keyCode
            }
            KeyEvent.ACTION_UP -> if (consumedKeys.remove(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Wraps [base] with the chosen language. Only the locale is overridden (sparse configuration): size,
     * orientation and density keep following the window, which matters because this activity handles those
     * changes itself (rotation, picture-in-picture) instead of being recreated.
     */
    private fun localized(base: Context): Context {
        val tag = runCatching { Settings.storedLanguage(base) }.getOrDefault("")
        if (tag.isEmpty()) {
            if (localeOverridden) restoreSystemLocale()
            return base
        }
        // A plain language tag: before Android 9 the resource matcher reads extensions (-u-...) as regions.
        val locale = Locale.forLanguageTag(tag)
        val chosen = LocaleList(locale)
        // Formatting (dates, numbers) and the layout direction follow the chosen language too.
        LocaleList.setDefault(chosen)
        Locale.setDefault(locale)
        localeOverridden = true
        // Empty configuration (every field undefined) + the locale: a delta applied over the window's own one.
        val config = Configuration()
        config.setLocales(chosen)
        return base.createConfigurationContext(config)
    }

    /** Back to "system language" after an override in this process: the default locale was changed by hand. */
    private fun restoreSystemLocale() {
        val system = Resources.getSystem().configuration.locales
        if (system.size() > 0) {
            LocaleList.setDefault(system)
            Locale.setDefault(system.get(0))
        }
        localeOverridden = false
    }

    private companion object {
        /** True once this process replaced the default locale with the in-app language. */
        var localeOverridden = false
    }
}

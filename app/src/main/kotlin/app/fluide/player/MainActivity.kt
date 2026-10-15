package app.fluide.player

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var displayModes: DisplayModeController

    /** Keys whose ACTION_DOWN was consumed: their ACTION_UP is consumed too. */
    private val consumedKeys = HashSet<Int>()

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
}

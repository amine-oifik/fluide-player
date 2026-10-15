package app.fluide.player.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Media3 PlayerView inside Compose. SurfaceView (the default) for the hardware video path; the
 * controller UI and Media3's own buffering spinner are off, the app draws its own overlays.
 *
 * keepContentOnPlayerReset: during a zap or a silent recovery the last picture stays on screen until
 * the next frame, instead of a black flash.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(player: Player?, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                // Avoids a visible glitch when Compose and the SurfaceView are composited (Media3 >= 1.6).
                setEnableComposeSurfaceSyncWorkaround(true)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isFocusable = false
                keepScreenOn = true
            }
        },
        modifier = modifier,
        onRelease = { view -> view.player = null },
        update = { view -> if (view.player !== player) view.player = player },
    )
}

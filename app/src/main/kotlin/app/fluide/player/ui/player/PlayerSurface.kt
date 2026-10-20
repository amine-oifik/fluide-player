package app.fluide.player.ui.player

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.fluide.player.AspectMode

/**
 * Media3 PlayerView inside Compose. SurfaceView (the default) for the hardware video path; the
 * controller UI and Media3's own buffering spinner are off, the app draws its own overlays.
 *
 * keepContentOnPlayerReset: during a zap or a silent recovery the last picture stays on screen until
 * the next frame, instead of a black flash.
 *
 * Subtitles: white text on a dark box with a soft shadow (readable over any picture, at a distance on
 * a TV), sized by [subtitleScale] (1 = Media3's default size, the "subtitle size" setting).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerSurface(
    player: Player?,
    modifier: Modifier = Modifier,
    aspect: AspectMode = AspectMode.FIT,
    subtitleScale: Float = 1f,
) {
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
                subtitleView?.setStyle(subtitleStyle())
            }
        },
        modifier = modifier,
        onRelease = { view -> view.player = null },
        update = { view ->
            if (view.player !== player) view.player = player
            val mode = when (aspect) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            if (view.resizeMode != mode) view.resizeMode = mode
            view.subtitleView?.let { subtitles ->
                val scale = subtitleScale.coerceIn(0.5f, 2.5f)
                subtitles.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
                // Sizes written in the subtitle file itself (SSA, TTML) would ignore the user's choice.
                subtitles.setApplyEmbeddedFontSizes(scale == 1f)
            }
        },
    )
}

/** White text on a translucent black box with a drop shadow, default typeface. */
@OptIn(UnstableApi::class)
private fun subtitleStyle() = CaptionStyleCompat(
    android.graphics.Color.WHITE,
    0xB3000000.toInt(),
    android.graphics.Color.TRANSPARENT,
    CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
    android.graphics.Color.BLACK,
    null,
)

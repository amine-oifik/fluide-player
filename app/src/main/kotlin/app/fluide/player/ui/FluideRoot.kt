package app.fluide.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.R
import app.fluide.player.data.Section
import app.fluide.player.ui.components.ConfirmDialog
import app.fluide.player.ui.components.PinDialog
import app.fluide.player.ui.home.HomeScreen
import app.fluide.player.ui.live.EpgScreen
import app.fluide.player.ui.live.LiveLayout
import app.fluide.player.ui.live.LiveScreen
import app.fluide.player.ui.media.DetailsScreen
import app.fluide.player.ui.media.MediaScreen
import app.fluide.player.ui.player.ErrorOverlay
import app.fluide.player.ui.player.NoticeHost
import app.fluide.player.ui.player.NumberEntry
import app.fluide.player.ui.player.OptionsDialog
import app.fluide.player.ui.player.PlayerSurface
import app.fluide.player.ui.player.StatsOverlay
import app.fluide.player.ui.player.ToastHost
import app.fluide.player.ui.player.UpNextCard
import app.fluide.player.ui.player.VideoControls
import app.fluide.player.ui.player.WaitingIndicator
import app.fluide.player.ui.player.ZapBanner
import app.fluide.player.ui.search.SearchScreen
import app.fluide.player.ui.settings.CategoriesScreen
import app.fluide.player.ui.settings.SettingsScreen
import app.fluide.player.ui.sources.SourcesScreen

/**
 * One screen, layered: the video is always at the bottom (full screen in the player, in the preview
 * of Live TV and of the programme guide) and the screens are drawn over it. A single player instance
 * serves the whole app: navigating never tears it down.
 */
@Composable
fun FluideRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val guide by vm.guide.collectAsStateWithLifecycle()

    // Kept here so that a details page returns to the same poster.
    val moviesGrid = rememberLazyGridState()
    val seriesGrid = rememberLazyGridState()
    val moviesFocus = remember { mutableIntStateOf(0) }
    val seriesFocus = remember { mutableIntStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val preview = !ui.pip && (ui.screen == Screen.LIVE || ui.screen == Screen.EPG)
        val (origin, size) = LiveLayout.preview(maxWidth)
        PlayerSurface(
            player,
            if (preview) {
                Modifier.offset(origin.x, origin.y).size(size.width, size.height).clip(RoundedCornerShape(12.dp))
            } else {
                Modifier.fillMaxSize()
            },
            aspect = settings.aspectMode,
        )

        // Picture-in-picture: the small window shows the video only.
        if (ui.pip) return@BoxWithConstraints

        when (ui.screen) {
            Screen.PLAYER -> {
                // Touch screens: a tap on the video opens the list (live) or the controls (video).
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (ui.playerMode == PlayerMode.LIVE) vm.openGuide() else vm.toggleControls()
                        },
                )
                playback.error?.let { ErrorOverlay(it, onRetry = vm::retry, modifier = Modifier.align(Alignment.Center)) }
                if (ui.playerMode == PlayerMode.LIVE) {
                    ZapBanner(ui.banner, ui.bannerVisible, Modifier.align(Alignment.BottomCenter), clock24h = settings.clock24h)
                    if (ui.numberEntry.isNotEmpty()) NumberEntry(ui.numberEntry, Modifier.align(Alignment.TopEnd))
                } else {
                    VideoControls(
                        vm = vm,
                        visible = ui.controlsVisible || ui.upNext != null,
                        title = ui.vodTitle,
                        subtitle = ui.vodSubtitle,
                        phase = playback.phase,
                        mode = ui.playerMode,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    ui.upNext?.let { UpNextCard(it, onPlay = vm::playNextNow, modifier = Modifier.align(Alignment.TopEnd)) }
                }
                if (settings.showStats) StatsOverlay(vm::stats, Modifier.align(Alignment.TopStart))
            }
            Screen.HOME -> HomeScreen(vm)
            Screen.LIVE -> LiveScreen(vm, guide, playback.channelId)
            Screen.EPG -> EpgScreen(vm)
            Screen.MOVIES -> MediaScreen(vm, Section.MOVIES, moviesGrid, moviesFocus)
            Screen.SERIES -> MediaScreen(vm, Section.SERIES, seriesGrid, seriesFocus)
            Screen.DETAILS -> DetailsScreen(vm)
            Screen.SOURCES -> SourcesScreen(vm)
            Screen.SETTINGS -> SettingsScreen(vm)
            Screen.CATEGORIES -> CategoriesScreen(vm)
            Screen.SEARCH -> SearchScreen(vm)
        }

        if (ui.screen == Screen.PLAYER) {
            WaitingIndicator(playback.waitingSinceMs, Modifier.align(Alignment.TopEnd).padding(32.dp))
        } else if (preview) {
            WaitingIndicator(
                playback.waitingSinceMs,
                Modifier.offset(origin.x + size.width / 2 - 18.dp, origin.y + size.height / 2 - 18.dp),
            )
        }
        NoticeHost(playback.notice, playback.noticeAtMs, Modifier.align(Alignment.TopCenter))
        ToastHost(ui.toast, vm::consumeToast, Modifier.align(Alignment.TopCenter))
        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))

        if (ui.optionsVisible) OptionsDialog(vm, settings.aspectMode, settings.showStats)
        ui.pin?.let { PinDialog(it, onSubmit = vm::submitPin, onDismiss = vm::cancelPin) }
        if (ui.exitDialog) {
            val activity = androidx.activity.compose.LocalActivity.current
            ConfirmDialog(
                title = stringResource(R.string.exit_title),
                onConfirm = {
                    vm.dismissExit()
                    activity?.finish()
                },
                onDismiss = vm::dismissExit,
            )
        }
    }
}

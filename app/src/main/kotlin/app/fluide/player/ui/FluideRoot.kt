package app.fluide.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fluide.player.ui.guide.ChannelGuide
import app.fluide.player.ui.player.ErrorOverlay
import app.fluide.player.ui.player.NoticeHost
import app.fluide.player.ui.player.NumberEntry
import app.fluide.player.ui.player.PlayerSurface
import app.fluide.player.ui.player.StatsOverlay
import app.fluide.player.ui.player.ToastHost
import app.fluide.player.ui.player.WaitingIndicator
import app.fluide.player.ui.player.ZapBanner
import app.fluide.player.ui.sources.SourcesScreen

/**
 * One screen, layered: the video is always at the bottom and keeps playing; the guide and the
 * sources are drawn over it. Nothing ever tears the player down to navigate.
 */
@Composable
fun FluideRoot(vm: MainViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val guide by vm.guide.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        PlayerSurface(player, Modifier.fillMaxSize())

        if (ui.screen == Screen.PLAYER) {
            // Touch screens: a tap on the video opens the channel list.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        vm.openGuide()
                    },
            )
            playback.error?.let { ErrorOverlay(it, onRetry = vm::retry, modifier = Modifier.align(Alignment.Center)) }
            ZapBanner(ui.banner, ui.bannerVisible, Modifier.align(Alignment.BottomCenter))
            if (ui.numberEntry.isNotEmpty()) NumberEntry(ui.numberEntry, Modifier.align(Alignment.TopEnd))
            if (settings.showStats) StatsOverlay(vm::stats, Modifier.align(Alignment.TopStart))
        }
        if (ui.screen == Screen.GUIDE) {
            ChannelGuide(vm, guide, playback.channelId)
        }
        if (ui.screen == Screen.SOURCES) {
            SourcesScreen(vm)
        } else {
            WaitingIndicator(playback.waitingSinceMs, Modifier.align(Alignment.TopEnd).padding(32.dp))
        }
        NoticeHost(playback.notice, playback.noticeAtMs, Modifier.align(Alignment.TopCenter))
        ToastHost(ui.toast, vm::consumeToast, Modifier.align(Alignment.TopCenter))
        if (ui.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

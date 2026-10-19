package app.fluide.player.ui.tools

import app.fluide.player.data.ChannelRepository
import app.fluide.player.net.HttpStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the measured speed allows. */
enum class SpeedVerdict { POOR, SD, HD, FULL_HD, UHD }

data class SpeedTestState(
    val running: Boolean = false,
    /** 0..1 while running. */
    val progress: Float = 0f,
    val latencyMs: Long? = null,
    val downloadKbps: Long? = null,
    val verdict: SpeedVerdict? = null,
    val error: String? = null,
)

/**
 * Connection test against the playlist's own server: latency, then the throughput of a real stream.
 * (Contract: implemented by the account / speed test work.)
 */
class SpeedTest(private val http: HttpStack, private val channels: ChannelRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = mutable.asStateFlow()

    fun run(sourceId: Long) = Unit

    fun cancel() = Unit
}

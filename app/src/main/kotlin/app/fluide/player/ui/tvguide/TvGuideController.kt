package app.fluide.player.ui.tvguide

import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.Programme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GuideRow(val channel: ChannelRow, val programmes: List<Programme>)

data class TvGuideState(
    /** Start of the visible time window (epoch s). */
    val windowStartSec: Long = 0,
    val windowHours: Int = 3,
    val rows: List<GuideRow> = emptyList(),
    val totalChannels: Int = 0,
    val loading: Boolean = false,
)

/**
 * Full TV guide: channels down, time across. (Contract: implemented by the TV guide work.)
 */
class TvGuideController(private val channels: ChannelRepository, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(TvGuideState())
    val state: StateFlow<TvGuideState> = mutable.asStateFlow()

    /** Loads the guide of [sourceId] around now; [excluded] categories are left out. */
    fun load(sourceId: Long, excluded: Set<String>) = Unit

    /** Moves the time window by [hours] (negative: earlier). */
    fun shift(hours: Int) = Unit
}

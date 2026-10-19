package app.fluide.player.ui.multiview

import android.content.Context
import androidx.media3.common.Player
import app.fluide.player.data.ChannelRepository
import app.fluide.player.net.HttpStack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One tile of the multi-screen view. [channelId] 0: empty tile. */
data class MultiSlot(
    val index: Int,
    val channelId: Long = 0,
    val name: String? = null,
    val logo: String? = null,
    val loading: Boolean = false,
    val error: Boolean = false,
)

data class MultiViewState(
    /** 2 (side by side) or 4 (2 x 2). */
    val layout: Int = 4,
    val slots: List<MultiSlot> = emptyList(),
    /** Tile whose sound is heard. */
    val audioSlot: Int = 0,
    /** Tile whose channel picker is open, -1 when closed. */
    val pickerSlot: Int = -1,
)

/**
 * Multi-screen: 2 or 4 live channels at once, one player per tile, sound from one tile only.
 * (Contract: implemented by the multi-screen work.)
 */
class MultiViewController(
    private val context: Context,
    private val http: HttpStack,
    private val channels: ChannelRepository,
    private val scope: CoroutineScope,
    private val excludedGroups: () -> Set<String>,
) {
    private val mutable = MutableStateFlow(MultiViewState())
    val state: StateFlow<MultiViewState> = mutable.asStateFlow()

    private val playerList = MutableStateFlow<List<Player?>>(emptyList())

    /** Player of each tile (same order as [MultiViewState.slots]), null for an empty tile. */
    val players: StateFlow<List<Player?>> = playerList.asStateFlow()

    /** Opens the view for [sourceId]; the first tile plays [firstChannelId] when not 0. */
    fun open(sourceId: Long, layout: Int, firstChannelId: Long) {
        mutable.value = MultiViewState(layout = layout, slots = List(layout) { MultiSlot(it) })
    }

    fun setLayout(layout: Int) = Unit

    fun assign(slot: Int, channelId: Long) = Unit

    fun clear(slot: Int) = Unit

    fun setAudio(slot: Int) = Unit

    fun openPicker(slot: Int) = Unit

    fun closePicker() = Unit

    /** The activity is visible again: players are recreated for the assigned channels. */
    fun onStart() = Unit

    /** The activity is hidden: every decoder is released. */
    fun onStop() = Unit

    /** The view is left: everything is released. */
    fun release() = Unit
}

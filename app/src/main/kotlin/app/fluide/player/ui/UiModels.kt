package app.fluide.player.ui

import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.Programme

enum class Screen { PLAYER, GUIDE, SOURCES }

/** Channel information shown by the zap banner. */
data class BannerInfo(
    val channelId: Long,
    val number: Int?,
    val name: String,
    val group: String?,
    val logo: String?,
    val programmes: List<Programme>,
    val position: Int,
    val total: Int,
)

/** Short message at the top of the screen. [arg] fills a %1$d placeholder when present. */
data class Toast(val resId: Int, val arg: Int? = null, val id: Long = System.nanoTime())

data class MainUiState(
    val loading: Boolean = true,
    val screen: Screen = Screen.PLAYER,
    val activeSourceId: Long = 0,
    val banner: BannerInfo? = null,
    val bannerVisible: Boolean = false,
    val numberEntry: String = "",
    val toast: Toast? = null,
)

/** Entry of the group column of the guide. [label] null: use the localized name of [filter]. */
data class GuideGroup(val filter: ChannelFilter, val label: String?, val count: Int)

data class GuideState(
    val groups: List<GuideGroup> = emptyList(),
    val selected: ChannelFilter = ChannelFilter.All,
    val query: String = "",
    /** Index of the row that should take focus when the guide opens (the channel playing). */
    val focusIndex: Int = 0,
    /** Changes whenever the list content changes, so the UI can scroll and refocus. */
    val version: Int = 0,
    /** False for search results: the focus must stay in the search field while typing. */
    val focusList: Boolean = true,
    val size: Int = 0,
)

/** What the activity should do with a key press. */
enum class KeyResult { HANDLED, IGNORED, EXIT }

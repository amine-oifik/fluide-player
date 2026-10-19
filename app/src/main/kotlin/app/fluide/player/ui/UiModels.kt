package app.fluide.player.ui

import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRow
import app.fluide.player.data.MediaFilter
import app.fluide.player.data.MediaSort
import app.fluide.player.data.Programme
import app.fluide.player.data.Section

enum class Screen { HOME, LIVE, PLAYER, MOVIES, SERIES, DETAILS, EPG, SOURCES, SETTINGS, CATEGORIES, SEARCH, MULTIVIEW, TVGUIDE, ACCOUNT }

/** What the full-screen player shows: a live channel (zapping), a video or a recording (seek bar). */
enum class PlayerMode { LIVE, VOD, CATCHUP }

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
data class Toast(val resId: Int, val arg: Int? = null, val text: String? = null, val id: Long = System.nanoTime())

/** The parental PIN is being asked; [changing] when the user is choosing a new PIN. */
data class PinRequest(val changing: Boolean = false, val error: Boolean = false, val id: Long = System.nanoTime())

/** End of an episode: the next one starts when the countdown ends (or on OK). */
data class UpNext(val title: String, val secondsLeft: Int)

data class MainUiState(
    val loading: Boolean = true,
    val screen: Screen = Screen.HOME,
    val activeSourceId: Long = 0,
    val banner: BannerInfo? = null,
    val bannerVisible: Boolean = false,
    val numberEntry: String = "",
    val toast: Toast? = null,
    val playerMode: PlayerMode = PlayerMode.LIVE,
    /** Title and subtitle of the video playing (movie, "S1 E2 - title", recorded programme). */
    val vodTitle: String? = null,
    val vodSubtitle: String? = null,
    /** Seek bar and buttons of the video player. */
    val controlsVisible: Boolean = false,
    /** Audio / subtitles / picture panel. */
    val optionsVisible: Boolean = false,
    val pin: PinRequest? = null,
    val exitDialog: Boolean = false,
    val upNext: UpNext? = null,
    /** True when the add-source form should open directly (first start). */
    val firstRun: Boolean = false,
    /** Picture-in-picture window (phones and tablets): only the video is drawn. */
    val pip: Boolean = false,
)

/** Entry of the group column of the guide. [label] null: use the localized name of [filter]. */
data class GuideGroup(val filter: ChannelFilter, val label: String?, val count: Int, val locked: Boolean = false)

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

/** Right panel of the Live TV screen: the focused channel and its programmes. */
data class LivePanel(val row: ChannelRow, val programmes: List<Programme>)

/** Programme guide of one channel (EPG listing and catch-up). */
data class EpgState(
    val channelId: Long = 0,
    val channelName: String = "",
    val logo: String? = null,
    val catchupDays: Int = 0,
    /** Start (epoch s, local midnight) of each selectable day, oldest first. */
    val days: List<Long> = emptyList(),
    val dayIndex: Int = 0,
    val programmes: List<Programme> = emptyList(),
    /** Programme on air now in [programmes], -1 if none (another day). */
    val nowIndex: Int = -1,
    val loading: Boolean = false,
    val version: Int = 0,
)

/** Entry of the category column of the movies / series screens. */
data class MediaGroup(val filter: MediaFilter, val label: String?, val count: Int, val locked: Boolean = false)

data class BrowserState(
    val section: Section,
    val groups: List<MediaGroup> = emptyList(),
    val selected: MediaFilter = MediaFilter.All,
    val sort: MediaSort = MediaSort.DEFAULT,
    val query: String = "",
    val size: Int = 0,
    val version: Int = 0,
)

/** One category of the "manage categories" screen. */
data class CategoryEntry(val name: String, val count: Int, val hidden: Boolean, val locked: Boolean, val adult: Boolean)

data class CategoriesState(val section: Section = Section.LIVE, val entries: List<CategoryEntry> = emptyList())

/** What the activity should do with a key press. */
enum class KeyResult { HANDLED, IGNORED, EXIT }

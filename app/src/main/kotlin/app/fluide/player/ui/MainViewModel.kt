package app.fluide.player.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import app.fluide.core.catchup.CatchupUrl
import app.fluide.core.m3u.StreamKind
import app.fluide.core.text.AdultFilter
import app.fluide.core.text.SearchNormalizer
import app.fluide.core.zap.ChannelNumberInput
import app.fluide.core.zap.PreloadLevel
import app.fluide.core.zap.PreloadPlanner
import app.fluide.core.zap.ZapGate
import app.fluide.player.FluideApp
import app.fluide.player.R
import app.fluide.player.SettingsValues
import app.fluide.player.data.AddResult
import app.fluide.player.data.CategoryMode
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.Episode
import app.fluide.player.data.GroupInfo
import app.fluide.player.data.ImportProgress
import app.fluide.player.data.MediaDetailsUi
import app.fluide.player.data.MediaFilter
import app.fluide.player.data.MediaRepository
import app.fluide.player.data.PlayableChannel
import app.fluide.player.data.Programme
import app.fluide.player.data.Season
import app.fluide.player.data.Section
import app.fluide.player.data.SourceInfo
import app.fluide.player.data.SourceType
import app.fluide.player.device.DeviceProfile
import app.fluide.player.playback.PlaybackController
import app.fluide.player.playback.PlaybackPhase
import app.fluide.player.playback.PlaybackStats
import app.fluide.player.playback.PlaybackUiState
import app.fluide.player.ui.media.MediaBrowser
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A selectable audio or subtitle track of the current stream. */
data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

/**
 * Screen logic: home, Live TV (zapping, preview, guide, catch-up), movies and series (browsing,
 * details, resume, next episode), playlists, settings and parental control. Playback itself is
 * delegated to [PlaybackController]: one player for the whole app.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as FluideApp).graph
    private val repository = graph.channels
    private val media = graph.media
    private val sourceManager = graph.sources
    private val settingsStore = graph.settings

    private val controller = PlaybackController(
        application,
        graph.http,
        graph.device,
        graph.settings,
        graph.health,
        graph.channels,
        graph.pressure,
        viewModelScope,
    )

    private val uiState = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = uiState.asStateFlow()

    private val guideState = MutableStateFlow(GuideState())
    val guide: StateFlow<GuideState> = guideState.asStateFlow()
    val pager = RowPager(repository, viewModelScope)

    private val panelState = MutableStateFlow<LivePanel?>(null)
    val livePanel: StateFlow<LivePanel?> = panelState.asStateFlow()

    private val epgState = MutableStateFlow(EpgState())
    val epg: StateFlow<EpgState> = epgState.asStateFlow()

    private val detailsState = MutableStateFlow<MediaDetailsUi?>(null)
    val details: StateFlow<MediaDetailsUi?> = detailsState.asStateFlow()

    private val categoriesState = MutableStateFlow(CategoriesState())
    val categories: StateFlow<CategoriesState> = categoriesState.asStateFlow()

    private val addError = MutableStateFlow<String?>(null)
    val sourceError: StateFlow<String?> = addError.asStateFlow()
    private val adding = MutableStateFlow(false)
    val addingSource: StateFlow<Boolean> = adding.asStateFlow()

    val movies = MediaBrowser(Section.MOVIES, media, viewModelScope)
    val series = MediaBrowser(Section.SERIES, media, viewModelScope)

    val playback: StateFlow<PlaybackUiState> = controller.state
    val player: StateFlow<Player?> = controller.player
    val settings: StateFlow<SettingsValues> = settingsStore.values
    val sources: StateFlow<List<SourceInfo>> = sourceManager.sources
    val importProgress: StateFlow<ImportProgress?> = sourceManager.progress
    val device: DeviceProfile get() = graph.device

    /** CH+/CH- walk through this list: the guide list the channel was picked from. */
    private var zapIds = LongArray(0)
    private var allIds = LongArray(0)
    private var guideIds = LongArray(0)
    private var currentIndex = -1
    private var pendingIndex = -1
    private var currentChannelId = 0L
    private var recallChannelId = 0L
    private var lastDirection = 1
    private var numberPrefixes: Set<String> = emptySet()

    private val zapGate = ZapGate()
    private val numberInput = ChannelNumberInput()
    private var zapJob: Job? = null
    private var bannerJob: Job? = null
    private var numberJob: Job? = null
    private var searchJob: Job? = null
    private var preloadJob: Job? = null
    private var panelJob: Job? = null
    private var controlsJob: Job? = null
    private var progressJob: Job? = null
    private var upNextJob: Job? = null

    // Parental control and category rules of the active source.
    private var parentalUnlocked = false
    private var pendingUnlock: (() -> Unit)? = null
    private var pinChangeStep = false
    private val hidden = HashMap<Section, Set<String>>()
    private val locked = HashMap<Section, Set<String>>()

    // Video playing (movie, episode, recording) and where the player returns to.
    private var vod: VodSession? = null
    private var detailsFrom = Screen.MOVIES
    private var seekStreak = 0
    private var lastSeekAtMs = 0L
    private val shortEpgTried = HashSet<Long>()

    private class VodSession(
        val sourceId: Long,
        val kind: Int,
        val key: String,
        val seriesKey: String?,
        val name: String,
        val poster: String?,
        val season: Int?,
        val episode: Int?,
        /** Episodes of the series in playing order (next episode), empty otherwise. */
        val playlist: List<Episode>,
    )

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { sourceManager.channelChanges.collect { onChannelsChanged(it) } }
        viewModelScope.launch {
            sourceManager.guideChanges.collect { id -> if (id == uiState.value.activeSourceId) pager.invalidate() }
        }
        viewModelScope.launch {
            controller.state.map { it.phase }.distinctUntilChanged().collect { phase ->
                if (phase == PlaybackPhase.ENDED) onVideoEnded()
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onStart() = controller.onStart()

    fun onStop() {
        saveVideoProgress()
        controller.onStop()
    }

    override fun onCleared() {
        saveVideoProgress()
        controller.release()
    }

    fun stats(): PlaybackStats? = controller.stats()

    private suspend fun start() {
        sourceManager.reload()
        val list = sourceManager.sources.value
        val saved = settingsStore.current.activeSourceId
        val active = list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
        if (active == null) {
            uiState.update { it.copy(loading = false, screen = Screen.SOURCES, firstRun = true) }
            return
        }
        activate(active, resume = settingsStore.current.resumeLastChannel)
        sourceManager.refreshIfStale(active)
        if (list.firstOrNull { it.id == active }?.type == SourceType.XTREAM) {
            viewModelScope.launch { sourceManager.refreshAccount(active) }
        }
    }

    private suspend fun activate(sourceId: Long, resume: Boolean) {
        settingsStore.update { it.copy(activeSourceId = sourceId) }
        uiState.update { it.copy(activeSourceId = sourceId) }
        loadRules(sourceId)
        val groups = loadLists(sourceId)
        showGuideList(ChannelFilter.All, allIds, groups)
        reloadMedia(sourceId, keepSelection = false)
        val last = settingsStore.current.lastChannelName
        val resumeId = if (resume && last != null) withContext(Dispatchers.IO) { repository.idByName(sourceId, last) } else null
        if (resumeId != null) {
            uiState.update { it.copy(loading = false, screen = Screen.PLAYER, playerMode = PlayerMode.LIVE) }
            playId(resumeId)
        } else {
            uiState.update { it.copy(loading = false, screen = if (it.screen == Screen.SOURCES && !it.firstRun) Screen.SOURCES else Screen.HOME, firstRun = false) }
        }
    }

    private fun reloadMedia(sourceId: Long, keepSelection: Boolean) {
        movies.load(sourceId, hidden[Section.MOVIES].orEmpty(), locked[Section.MOVIES].orEmpty(), keepSelection)
        series.load(sourceId, hidden[Section.SERIES].orEmpty(), locked[Section.SERIES].orEmpty(), keepSelection)
    }

    /** Hidden and locked categories of each section, from the stored rules and the parental filter. */
    private suspend fun loadRules(sourceId: Long) {
        val parental = parentalActive()
        withContext(Dispatchers.IO) {
            for (section in Section.entries) {
                val rules = media.rules(sourceId, section)
                val names = groupsOf(sourceId, section).mapNotNull { it.name }
                hidden[section] = rules.filterValues { it == CategoryMode.HIDDEN }.keys
                locked[section] = if (!parental) {
                    emptySet()
                } else {
                    names.filter { rules[it] == CategoryMode.LOCKED || (rules[it] == null && AdultFilter.isAdult(it)) }.toSet()
                }
            }
        }
    }

    private fun groupsOf(sourceId: Long, section: Section): List<GroupInfo> =
        if (section == Section.LIVE) repository.groups(sourceId) else media.groups(sourceId, section)

    private fun parentalActive(): Boolean = settingsStore.current.parentalEnabled && !parentalUnlocked

    private fun liveExcluded(): Set<String> = hidden[Section.LIVE].orEmpty() + locked[Section.LIVE].orEmpty()

    /** Loads the id list, groups and number prefixes of a source. Returns the guide groups. */
    private suspend fun loadLists(sourceId: Long): List<GuideGroup> {
        val excluded = liveExcluded()
        val data = withContext(Dispatchers.IO) {
            ListData(
                groups = repository.groups(sourceId),
                ids = repository.channelIds(sourceId, ChannelFilter.All, excluded),
                prefixes = repository.numberPrefixes(sourceId),
                catchup = repository.catchupCount(sourceId),
            )
        }
        allIds = data.ids
        zapIds = data.ids
        numberPrefixes = data.prefixes
        return guideGroups(data.groups, data.ids.size, data.catchup)
    }

    private class ListData(val groups: List<GroupInfo>, val ids: LongArray, val prefixes: Set<String>, val catchup: Int)

    private suspend fun onChannelsChanged(sourceId: Long) {
        val active = uiState.value.activeSourceId
        if (active == 0L || sourceManager.sources.value.none { it.id == active }) {
            if (sourceManager.sources.value.any { it.id == sourceId }) activate(sourceId, resume = false)
            return
        }
        if (sourceId != active) return
        // The catalogue was re-imported (new ids): reload, keep playing.
        loadRules(sourceId)
        val groups = loadLists(sourceId)
        val playingName = controller.currentChannel?.takeIf { !it.vod }?.name
        val playingId = if (playingName != null) withContext(Dispatchers.IO) { repository.idByName(sourceId, playingName) } else null
        currentChannelId = playingId ?: 0L
        currentIndex = if (playingId != null) zapIds.indexOf(playingId) else -1
        recallChannelId = 0L
        val previous = guideState.value.selected
        showGuideList(ChannelFilter.All, allIds, groups)
        if (previous != ChannelFilter.All && previous !is ChannelFilter.Search) applyFilter(previous)
        reloadMedia(sourceId, keepSelection = true)
    }

    // ------------------------------------------------------------------ navigation

    fun openHome() {
        leaveLive()
        uiState.update { it.copy(screen = Screen.HOME, controlsVisible = false, optionsVisible = false) }
    }

    /** Live TV: the last channel starts in the preview. */
    fun openLive(filter: ChannelFilter = ChannelFilter.All) {
        if (allIds.isEmpty() && filter == ChannelFilter.All) {
            toast(R.string.home_no_channels)
            return
        }
        uiState.update { it.copy(screen = Screen.LIVE, playerMode = PlayerMode.LIVE) }
        if (filter != guideState.value.selected) selectFilter(filter)
        if (currentChannelId == 0L && filter != ChannelFilter.Catchup) {
            val source = uiState.value.activeSourceId
            val last = settingsStore.current.lastChannelName
            viewModelScope.launch {
                val id = last?.let { withContext(Dispatchers.IO) { repository.idByName(source, it) } }
                if (id != null && id in allIds) {
                    zapIds = guideIds.takeIf { id in it } ?: allIds
                    playId(id)
                }
                openGuide()
            }
        } else {
            openGuide()
        }
    }

    fun openMovies() = openMedia(Screen.MOVIES, sources.value.firstOrNull { it.id == uiState.value.activeSourceId }?.movieCount ?: 0)

    fun openSeries() = openMedia(Screen.SERIES, sources.value.firstOrNull { it.id == uiState.value.activeSourceId }?.seriesCount ?: 0)

    private fun openMedia(screen: Screen, count: Int) {
        if (count == 0) {
            toast(if (screen == Screen.MOVIES) R.string.home_no_movies else R.string.home_no_series)
            return
        }
        leaveLive()
        uiState.update { it.copy(screen = screen) }
    }

    fun openSources() {
        leaveLive()
        addError.value = null
        uiState.update { it.copy(screen = Screen.SOURCES) }
    }

    fun openSettings() {
        leaveLive()
        uiState.update { it.copy(screen = Screen.SETTINGS) }
    }

    /** Stops the live preview when Live TV is left for another section. */
    private fun leaveLive() {
        val state = uiState.value
        if (state.screen == Screen.LIVE || (state.screen == Screen.PLAYER && state.playerMode == PlayerMode.LIVE) || state.screen == Screen.EPG) {
            controller.stop()
            currentChannelId = 0L
            currentIndex = -1
            hideBanner()
        }
    }

    /** Back button / gesture, from the activity's OnBackPressedDispatcher. */
    fun onBack(): KeyResult {
        val state = uiState.value
        when {
            state.pin != null -> {
                pendingUnlock = null
                pinChangeStep = false
                uiState.update { it.copy(pin = null) }
                return KeyResult.HANDLED
            }
            state.exitDialog -> {
                uiState.update { it.copy(exitDialog = false) }
                return KeyResult.HANDLED
            }
            state.optionsVisible -> {
                uiState.update { it.copy(optionsVisible = false) }
                return KeyResult.HANDLED
            }
            state.upNext != null -> {
                upNextJob?.cancel()
                uiState.update { it.copy(upNext = null) }
                closeVideo()
                return KeyResult.HANDLED
            }
        }
        when (state.screen) {
            Screen.HOME -> uiState.update { it.copy(exitDialog = true) }
            Screen.LIVE -> when {
                guideState.value.query.isNotEmpty() -> search("")
                guideState.value.selected != ChannelFilter.All -> selectFilter(ChannelFilter.All)
                else -> openHome()
            }
            Screen.PLAYER -> when (state.playerMode) {
                PlayerMode.LIVE -> when {
                    state.numberEntry.isNotEmpty() -> {
                        numberInput.clear()
                        numberJob?.cancel()
                        uiState.update { it.copy(numberEntry = "") }
                    }
                    state.bannerVisible -> hideBanner()
                    else -> openGuide()
                }
                PlayerMode.VOD, PlayerMode.CATCHUP -> closeVideo()
            }
            Screen.MOVIES -> if (movies.browser.value.query.isNotEmpty()) movies.search("") else openHome()
            Screen.SERIES -> if (series.browser.value.query.isNotEmpty()) series.search("") else openHome()
            Screen.DETAILS -> uiState.update { it.copy(screen = detailsFrom) }
            Screen.EPG -> openGuide()
            Screen.SOURCES -> if (sourceManager.sources.value.isEmpty()) {
                uiState.update { it.copy(exitDialog = true) }
            } else {
                openHome()
            }
            Screen.SETTINGS -> openHome()
            Screen.CATEGORIES -> {
                uiState.update { it.copy(screen = Screen.SETTINGS) }
                applyRules()
            }
        }
        return KeyResult.HANDLED
    }

    fun dismissExit() = uiState.update { it.copy(exitDialog = false) }

    // ------------------------------------------------------------------ zapping

    /** Instant feedback (banner), then the gate decides when the stream actually loads. */
    fun zap(delta: Int) {
        val ids = zapIds
        if (ids.isEmpty()) return
        val from = when {
            pendingIndex >= 0 -> pendingIndex
            currentIndex >= 0 -> currentIndex
            else -> 0
        }
        val target = Math.floorMod(from + delta, ids.size)
        pendingIndex = target
        lastDirection = if (delta >= 0) 1 else -1
        showBanner(target)
        val waitMs = zapGate.onPress(SystemClock.elapsedRealtime())
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            if (waitMs > 0) {
                warmIndex(target) // DNS while the user is still pressing
                delay(waitMs)
            }
            playIndex(target)
        }
    }

    private fun playId(id: Long) {
        var index = zapIds.indexOf(id)
        if (index < 0) {
            zapIds = allIds
            index = allIds.indexOf(id)
        }
        if (index >= 0) {
            playIndex(index)
        } else {
            // A channel of a locked category opened from its own list.
            zapIds = guideIds
            guideIds.indexOf(id).takeIf { it >= 0 }?.let(::playIndex)
        }
    }

    private fun playIndex(index: Int) {
        val id = zapIds.getOrNull(index) ?: return
        pendingIndex = -1
        if (id != currentChannelId && currentChannelId != 0L) recallChannelId = currentChannelId
        currentChannelId = id
        currentIndex = index
        if (uiState.value.screen == Screen.PLAYER) showBanner(index)
        vod = null
        viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) { repository.playable(id) }
            if (currentChannelId != id) return@launch // another zap happened meanwhile
            if (channel == null) {
                // URL unreadable (device key reset): re-import the source.
                toast(R.string.import_failed_reimport)
                sourceManager.refresh(uiState.value.activeSourceId)
                return@launch
            }
            controller.play(channel)
            settingsStore.update { it.copy(lastChannelName = channel.name) }
            withContext(Dispatchers.IO) { runCatching { repository.recordHistory(channel.sourceId, channel.name) } }
            planPreload(index, channel)
            ensureShortEpg(id)
        }
    }

    private fun recall() {
        val id = recallChannelId
        if (id != 0L) playId(id)
    }

    private fun warmIndex(index: Int) {
        val id = zapIds.getOrNull(index) ?: return
        viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) { repository.playable(id) } ?: return@launch
            controller.warm(listOf(channel to PreloadLevel.DNS))
        }
    }

    /** Neighbours in the zap direction, the recall channel...: never more than the device and the source allow. */
    private fun planPreload(index: Int, channel: PlayableChannel) {
        preloadJob?.cancel()
        val ids = zapIds
        val recall = ids.indexOf(recallChannelId).takeIf { it >= 0 }
        val plan = PreloadPlanner.plan(
            currentIndex = index,
            channelCount = ids.size,
            lastZapDirection = lastDirection,
            recallIndex = recall,
            focusedIndex = null,
            deviceMaxLevel = graph.device.budget.maxPreload,
            sourceAllowsExtraConnections = channel.maxConnections > 1,
        )
        if (plan.isEmpty()) return
        preloadJob = viewModelScope.launch {
            delay(PRELOAD_DELAY_MS) // the channel being started has priority on the network
            val targets = withContext(Dispatchers.IO) {
                plan.mapNotNull { target ->
                    ids.getOrNull(target.index)?.let(repository::playable)?.let { it to target.level }
                }
            }
            controller.warm(targets)
        }
    }

    // ------------------------------------------------------------------ banner, numbers, keys

    private fun showBanner(index: Int) {
        val id = zapIds.getOrNull(index) ?: return
        val total = zapIds.size
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                val nowSec = System.currentTimeMillis() / 1_000
                repository.row(id, nowSec)?.let { row ->
                    BannerInfo(row.id, row.number, row.name, row.group, row.logo, repository.nowNext(row.epgKey, nowSec), index + 1, total)
                }
            } ?: return@launch
            uiState.update { it.copy(banner = info, bannerVisible = true) }
            delay(BANNER_MS)
            uiState.update { it.copy(bannerVisible = false) }
        }
    }

    private fun hideBanner() {
        bannerJob?.cancel()
        uiState.update { it.copy(bannerVisible = false) }
    }

    private fun toggleInfo() {
        if (uiState.value.bannerVisible) hideBanner() else if (currentIndex >= 0) showBanner(currentIndex)
    }

    private fun onDigit(digit: Int) {
        val committed = numberInput.onDigit(digit, SystemClock.elapsedRealtime()) { prefix -> prefix in numberPrefixes }
        uiState.update { it.copy(numberEntry = numberInput.pending) }
        numberJob?.cancel()
        if (committed != null) {
            jumpToNumber(committed)
            return
        }
        if (numberInput.pending.isEmpty()) return
        numberJob = viewModelScope.launch {
            delay(NUMBER_COMMIT_MS)
            numberInput.commitIfDue(SystemClock.elapsedRealtime())?.let(::jumpToNumber)
        }
    }

    private fun jumpToNumber(number: Int) {
        uiState.update { it.copy(numberEntry = "") }
        val source = uiState.value.activeSourceId
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { repository.idByNumber(source, number) }
            if (id == null) toast(R.string.notice_no_channel_number, number) else playId(id)
        }
    }

    /**
     * Key presses reach the view model before Compose, but only the full-screen player uses them here
     * (zapping must not depend on which element has the focus). BACK is not a key event any more on
     * Android 16 (predictive back): see [onBack].
     */
    fun onKeyDown(keyCode: Int): KeyResult {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return onBack()
        val state = uiState.value
        if (state.loading || state.pin != null || state.exitDialog || state.optionsVisible) return KeyResult.IGNORED
        if (state.screen != Screen.PLAYER) return KeyResult.IGNORED
        return if (state.playerMode == PlayerMode.LIVE) liveKey(keyCode) else videoKey(keyCode)
    }

    private fun liveKey(keyCode: Int): KeyResult {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (playback.value.error != null) controller.retryNow() else openGuide()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> openGuide()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> toggleInfo()
            KeyEvent.KEYCODE_LAST_CHANNEL -> recall()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> openOptions()
            KeyEvent.KEYCODE_PROG_RED -> toggleStats()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> controller.togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (playback.value.phase == PlaybackPhase.PAUSED) controller.togglePause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (playback.value.phase != PlaybackPhase.PAUSED) controller.togglePause()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> onDigit(keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            else -> return KeyResult.IGNORED
        }
        return KeyResult.HANDLED
    }

    private fun videoKey(keyCode: Int): KeyResult {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> when {
                uiState.value.upNext != null -> playNextNow()
                playback.value.error != null -> controller.retryNow()
                else -> {
                    controller.togglePause()
                    showControls()
                }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (playback.value.phase == PlaybackPhase.PAUSED) controller.togglePause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (playback.value.phase != PlaybackPhase.PAUSED) controller.togglePause()
            KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1, SEEK_STEP_MS)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seek(+1, SEEK_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1, SEEK_FAST_MS)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(+1, SEEK_FAST_MS)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> toggleControls()
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> openOptions()
            KeyEvent.KEYCODE_MEDIA_NEXT -> nextEpisode()?.let { playEpisode(it, fromStart = true) }
            KeyEvent.KEYCODE_PROG_RED -> toggleStats()
            else -> return KeyResult.IGNORED
        }
        return KeyResult.HANDLED
    }

    /** Held arrow: the steps grow (10 s, 30 s, 60 s) so that a long film can be crossed quickly. */
    private fun seek(direction: Int, baseMs: Long) {
        val now = SystemClock.elapsedRealtime()
        seekStreak = if (now - lastSeekAtMs < SEEK_STREAK_MS) seekStreak + 1 else 0
        lastSeekAtMs = now
        val step = when {
            seekStreak > 12 -> baseMs * 6
            seekStreak > 4 -> baseMs * 3
            else -> baseMs
        }
        controller.seekBy(direction * step)
        showControls()
    }

    fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
        showControls()
    }

    fun showControls() {
        uiState.update { it.copy(controlsVisible = true) }
        controlsJob?.cancel()
        controlsJob = viewModelScope.launch {
            delay(CONTROLS_MS)
            if (playback.value.phase != PlaybackPhase.PAUSED) uiState.update { it.copy(controlsVisible = false) }
        }
    }

    private fun hideControls() {
        controlsJob?.cancel()
        uiState.update { it.copy(controlsVisible = false) }
    }

    fun toggleControls() = if (uiState.value.controlsVisible) hideControls() else showControls()

    fun retry() = controller.retryNow()

    fun togglePause() {
        controller.togglePause()
        if (uiState.value.playerMode != PlayerMode.LIVE) showControls()
    }

    fun position(): Long = controller.positionMs

    fun duration(): Long = controller.durationMs

    private fun toggleStats() = settingsStore.update { it.copy(showStats = !it.showStats) }

    private fun toast(resId: Int, arg: Int? = null, text: String? = null) {
        uiState.update { it.copy(toast = Toast(resId, arg, text)) }
    }

    fun consumeToast(toast: Toast) {
        uiState.update { if (it.toast?.id == toast.id) it.copy(toast = null) else it }
    }

    // ------------------------------------------------------------------ player options

    fun openOptions() = uiState.update { it.copy(optionsVisible = true, controlsVisible = false) }

    fun closeOptions() = uiState.update { it.copy(optionsVisible = false) }

    fun tracks(type: Int): List<TrackOption> {
        val p = player.value ?: return emptyList()
        val out = ArrayList<TrackOption>()
        p.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                out += TrackOption(groupIndex, trackIndex, trackLabel(format, out.size + 1), group.isTrackSelected(trackIndex))
            }
        }
        return out
    }

    /** [option] null with [type] text: subtitles off. */
    fun selectTrack(type: Int, option: TrackOption?) {
        val p = player.value ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.clearOverridesOfType(type).setTrackTypeDisabled(type, true)
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        p.trackSelectionParameters = builder.build()
    }

    fun subtitlesOff(): Boolean = player.value?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true

    private fun trackLabel(format: androidx.media3.common.Format, ordinal: Int): String {
        val parts = ArrayList<String>(3)
        format.label?.takeIf { it.isNotBlank() }?.let(parts::add)
        format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            if (name.isNotBlank() && parts.none { it.equals(name, ignoreCase = true) }) {
                parts += name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
        }
        if (format.channelCount > 0) {
            parts += when (format.channelCount) {
                1 -> "mono"
                2 -> "stéréo"
                6 -> "5.1"
                8 -> "7.1"
                else -> "${format.channelCount} canaux"
            }
        }
        (format.codecs ?: format.sampleMimeType?.substringAfter('/'))?.let { parts += it.uppercase(Locale.ROOT) }
        return if (parts.isEmpty()) "Piste $ordinal" else parts.joinToString(" · ")
    }

    /** URL of the stream playing, to open it in another player app. */
    fun currentUrl(): String? = controller.currentChannel?.url

    /** URL of the movie of the details page, or of [episode], for another player app. */
    fun externalUrl(episode: Episode?, onReady: (String) -> Unit) {
        val d = detailsState.value ?: return
        viewModelScope.launch {
            val url = withContext(Dispatchers.IO) {
                when {
                    episode?.url != null -> episode.url
                    episode != null -> sourceManager.login(d.sourceId)?.urls?.episode(episode.key.toLongOrNull() ?: return@withContext null, episode.extension)
                    else -> media.record(Section.MOVIES, d.id)?.url
                }
            }
            if (url != null) onReady(url) else toast(R.string.no_external_player)
        }
    }

    fun notifyNoExternalPlayer() = toast(R.string.no_external_player)

    // ------------------------------------------------------------------ guide (Live TV)

    fun openGuide() {
        hideBanner()
        val focus = guideIds.indexOf(currentChannelId).coerceAtLeast(0)
        guideState.update { it.copy(focusIndex = focus, version = it.version + 1, focusList = true) }
        uiState.update { it.copy(screen = Screen.LIVE, playerMode = PlayerMode.LIVE) }
    }

    fun closeOverlays() {
        if (currentChannelId != 0L) uiState.update { it.copy(screen = Screen.PLAYER, playerMode = PlayerMode.LIVE) }
    }

    fun selectFilter(filter: ChannelFilter) {
        if (filter is ChannelFilter.Group && filter.name != null && filter.name in locked[Section.LIVE].orEmpty()) {
            requirePin { applyFilter(filter) }
            return
        }
        applyFilter(filter)
    }

    private fun applyFilter(filter: ChannelFilter) {
        searchJob?.cancel()
        val source = uiState.value.activeSourceId
        val excluded = liveExcluded()
        viewModelScope.launch {
            val ids = if (filter == ChannelFilter.All) allIds else withContext(Dispatchers.IO) { repository.channelIds(source, filter, excluded) }
            showGuideList(filter, ids)
        }
    }

    fun search(query: String) {
        guideState.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            showGuideList(ChannelFilter.All, allIds, focusList = false)
            return
        }
        val source = uiState.value.activeSourceId
        val excluded = liveExcluded()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val filter = ChannelFilter.Search(query)
            val ids = withContext(Dispatchers.IO) { repository.channelIds(source, filter, excluded) }
            showGuideList(filter, ids)
        }
    }

    /**
     * OK on a channel of the list: a first press plays it in the preview, a second press on the channel
     * playing opens it full screen. In the catch-up list, it opens the channel's programme guide.
     */
    fun playFromGuide(index: Int) {
        val id = guideIds.getOrNull(index) ?: return
        val filter = guideState.value.selected
        if (filter == ChannelFilter.Catchup) {
            openEpg(id)
            return
        }
        if (id == currentChannelId && playback.value.channelId == id) {
            uiState.update { it.copy(screen = Screen.PLAYER, playerMode = PlayerMode.LIVE) }
            showBanner(currentIndex)
            return
        }
        // Zapping inside search results would be confusing: CH+/CH- then use the full list.
        zapIds = if (filter is ChannelFilter.Search) allIds else guideIds
        zapJob?.cancel()
        zapGate.reset()
        playId(id)
    }

    fun fullScreen() {
        if (currentChannelId == 0L) return
        uiState.update { it.copy(screen = Screen.PLAYER, playerMode = PlayerMode.LIVE) }
        showBanner(currentIndex)
    }

    /** A channel row took the focus: its programmes go to the right panel. */
    fun focusChannel(index: Int) {
        val id = guideIds.getOrNull(index) ?: return
        panelJob?.cancel()
        panelJob = viewModelScope.launch {
            delay(PANEL_DELAY_MS)
            loadPanel(id)
            if (ensureShortEpg(id)) loadPanel(id)
        }
    }

    private suspend fun loadPanel(id: Long) {
        val panel = withContext(Dispatchers.IO) {
            val nowSec = System.currentTimeMillis() / 1_000
            repository.row(id, nowSec)?.let { row ->
                LivePanel(row, repository.programmes(row.epgKey, nowSec, nowSec + PANEL_HOURS * 3_600).take(PANEL_PROGRAMMES))
            }
        }
        panelState.value = panel
    }

    /**
     * Xtream accounts without a full guide: fetches the next programmes of [channelId] once
     * (`get_short_epg`). True when new programmes were stored.
     */
    private suspend fun ensureShortEpg(channelId: Long): Boolean {
        val source = sourceManager.sources.value.firstOrNull { it.id == uiState.value.activeSourceId } ?: return false
        if (source.type != SourceType.XTREAM || !shortEpgTried.add(channelId)) return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val nowSec = System.currentTimeMillis() / 1_000
                val channel = repository.playable(channelId) ?: return@runCatching false
                val key = channel.epgKey ?: return@runCatching false
                if (repository.nowNext(key, nowSec).isNotEmpty()) return@runCatching false
                val streamId = channel.url.substringAfterLast('/').substringBefore('.').toLongOrNull() ?: return@runCatching false
                val login = sourceManager.login(source.id) ?: return@runCatching false
                val list = sourceManager.xtream.shortEpg(login, streamId, coroutineContext.job)
                repository.storeProgrammes(key, list.map { Programme(it.startSec, it.stopSec, it.title, null, it.description, null) })
                list.isNotEmpty()
            }.getOrDefault(false)
        }.also { stored -> if (stored) pager.invalidate() }
    }

    fun toggleFavorite(index: Int) {
        val id = guideIds.getOrNull(index) ?: return
        val source = uiState.value.activeSourceId
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                repository.row(id, 0)?.let { repository.toggleFavorite(source, it.name) }
            } ?: return@launch
            toast(if (added) R.string.notice_favorite_added else R.string.notice_favorite_removed)
            if (guideState.value.selected == ChannelFilter.Favorites) applyFilter(ChannelFilter.Favorites) else pager.invalidate()
        }
    }

    private fun showGuideList(
        filter: ChannelFilter,
        ids: LongArray,
        groups: List<GuideGroup>? = null,
        focusList: Boolean = filter !is ChannelFilter.Search,
    ) {
        guideIds = ids
        pager.setIds(ids)
        val focus = ids.indexOf(currentChannelId).coerceAtLeast(0)
        guideState.update {
            it.copy(
                groups = groups ?: it.groups,
                selected = filter,
                query = if (filter is ChannelFilter.Search) it.query else "",
                focusIndex = focus,
                version = it.version + 1,
                focusList = focusList,
                size = ids.size,
            )
        }
    }

    private fun guideGroups(groups: List<GroupInfo>, total: Int, catchup: Int): List<GuideGroup> = buildList {
        val hiddenLive = hidden[Section.LIVE].orEmpty()
        val lockedLive = locked[Section.LIVE].orEmpty()
        add(GuideGroup(ChannelFilter.All, null, total))
        add(GuideGroup(ChannelFilter.Favorites, null, -1))
        add(GuideGroup(ChannelFilter.Recent, null, -1))
        if (catchup > 0) add(GuideGroup(ChannelFilter.Catchup, null, catchup))
        for (group in groups) {
            val name = group.name
            if (name != null && name in hiddenLive) continue
            add(GuideGroup(ChannelFilter.Group(name), name, group.count, locked = name != null && name in lockedLive))
        }
    }

    // ------------------------------------------------------------------ programme guide and catch-up

    fun openEpg(channelId: Long) {
        viewModelScope.launch {
            ensureShortEpg(channelId)
            val nowSec = System.currentTimeMillis() / 1_000
            val row = withContext(Dispatchers.IO) { repository.row(channelId, nowSec) } ?: return@launch
            val today = dayStart(nowSec)
            val past = if (row.catchupDays > 0) row.catchupDays.coerceAtMost(MAX_CATCHUP_DAYS) else 1
            val days = (-past..EPG_FUTURE_DAYS).map { shiftDays(today, it) }
            epgState.value = EpgState(
                channelId = channelId,
                channelName = row.name,
                logo = row.logo,
                catchupDays = row.catchupDays,
                days = days,
                dayIndex = past,
                loading = true,
            )
            uiState.update { it.copy(screen = Screen.EPG) }
            loadEpgDay(past)
        }
    }

    fun selectEpgDay(index: Int) {
        if (index !in epgState.value.days.indices) return
        viewModelScope.launch { loadEpgDay(index) }
    }

    private suspend fun loadEpgDay(index: Int) {
        val state = epgState.value
        val dayStart = state.days.getOrNull(index) ?: return
        val dayEnd = state.days.getOrNull(index + 1) ?: shiftDays(dayStart, 1)
        val programmes = withContext(Dispatchers.IO) {
            val row = repository.row(state.channelId, 0)
            repository.programmes(row?.epgKey, dayStart, dayEnd)
        }
        val nowSec = System.currentTimeMillis() / 1_000
        epgState.update {
            it.copy(
                dayIndex = index,
                programmes = programmes,
                nowIndex = programmes.indexOfFirst { p -> p.startSec <= nowSec && p.stopSec > nowSec },
                loading = false,
                version = it.version + 1,
            )
        }
    }

    /** OK on a programme: the live channel when it is on air, its recording when it is in the past. */
    fun playProgramme(index: Int) {
        val state = epgState.value
        val programme = state.programmes.getOrNull(index) ?: return
        val nowSec = System.currentTimeMillis() / 1_000
        when {
            programme.startSec <= nowSec && programme.stopSec > nowSec -> {
                zapIds = allIds.takeIf { state.channelId in it } ?: longArrayOf(state.channelId)
                currentChannelId = 0L
                playId(state.channelId)
                uiState.update { it.copy(screen = Screen.PLAYER, playerMode = PlayerMode.LIVE) }
            }
            programme.stopSec <= nowSec && state.catchupDays > 0 &&
                programme.startSec >= nowSec - state.catchupDays * 86_400L -> playRecording(state, programme, nowSec)
            programme.startSec > nowSec -> toast(R.string.epg_future)
            else -> toast(R.string.epg_no_recording)
        }
    }

    private fun playRecording(state: EpgState, programme: Programme, nowSec: Long) {
        viewModelScope.launch {
            val channel = withContext(Dispatchers.IO) { repository.playable(state.channelId) } ?: return@launch
            val source = sourceManager.sources.value.firstOrNull { it.id == channel.sourceId }
            val zone = source?.serverTimezone?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
            val offset = zone.getOffset(programme.startSec * 1_000) / 1_000
            val url = CatchupUrl.build(
                channel.catchupMode ?: if (source?.type == SourceType.XTREAM) "xc" else null,
                channel.catchupSource,
                channel.url,
                programme.startSec,
                programme.stopSec,
                nowSec,
                offset,
            )
            if (url == null) {
                toast(R.string.epg_no_recording)
                return@launch
            }
            saveVideoProgress()
            controller.stop()
            currentChannelId = 0L
            currentIndex = -1
            vod = null
            val kind = StreamKind.infer(url).takeUnless { it == StreamKind.UNKNOWN } ?: StreamKind.MPEG_TS
            controller.play(channel.copy(id = RECORDING_ID, url = url, kind = kind, vod = true))
            val time = android.text.format.DateFormat.getTimeFormat(getApplication<Application>()).format(java.util.Date(programme.startSec * 1_000))
            uiState.update {
                it.copy(
                    screen = Screen.PLAYER,
                    playerMode = PlayerMode.CATCHUP,
                    vodTitle = programme.title,
                    vodSubtitle = "${channel.name} · $time",
                )
            }
            showControls()
        }
    }

    // ------------------------------------------------------------------ movies and series

    fun openMovieAt(index: Int) {
        val id = movies.pager.idAt(index) ?: return
        openDetails(Section.MOVIES, id)
    }

    fun openSeriesAt(index: Int) {
        val id = series.pager.idAt(index) ?: return
        openDetails(Section.SERIES, id)
    }

    fun selectMediaFilter(section: Section, filter: MediaFilter) {
        val browser = if (section == Section.SERIES) series else movies
        if (filter is MediaFilter.Group && filter.name != null && filter.name in locked[section].orEmpty()) {
            requirePin { browser.select(filter) }
            return
        }
        browser.select(filter)
    }

    private fun openDetails(section: Section, id: Long) {
        detailsFrom = if (section == Section.SERIES) Screen.SERIES else Screen.MOVIES
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { media.record(section, id) } ?: return@launch
            val source = sourceManager.sources.value.firstOrNull { it.id == record.sourceId }
            val xtream = source?.type == SourceType.XTREAM
            val (favorite, resume) = withContext(Dispatchers.IO) {
                media.isFavorite(record.sourceId, section, record.key) to
                    if (section == Section.MOVIES) media.progress(record.sourceId, MediaRepository.KIND_MOVIE, record.key) else null
            }
            detailsState.value = MediaDetailsUi(
                sourceId = record.sourceId,
                section = section,
                id = id,
                key = record.key,
                name = record.name,
                poster = record.poster,
                backdrop = null,
                plot = record.plot,
                genre = record.genre,
                director = null,
                cast = null,
                releaseDate = null,
                year = record.year,
                durationSec = null,
                rating = record.rating,
                trailer = null,
                country = null,
                group = record.group,
                favorite = favorite,
                resumeMs = resume?.takeUnless { it.finished }?.positionMs ?: 0,
                durationMs = resume?.durationMs ?: 0,
                seasons = emptyList(),
                continueEpisode = null,
                extension = record.extension,
                loading = xtream || section == Section.SERIES,
            )
            uiState.update { it.copy(screen = Screen.DETAILS) }
            if (section == Section.MOVIES) {
                if (xtream) loadMovieInfo(record.sourceId, id, record.key)
            } else {
                loadSeriesInfo(record.sourceId, id, record.key, xtream)
            }
        }
    }

    private suspend fun loadMovieInfo(sourceId: Long, id: Long, key: String) {
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val login = sourceManager.login(sourceId) ?: return@runCatching null
                sourceManager.xtream.vodInfo(login, key, coroutineContext.job)
            }.getOrNull()
        }
        detailsState.update { current ->
            if (current == null || current.id != id || current.section != Section.MOVIES) return@update current
            val vodInfo = info ?: return@update current.copy(loading = false)
            val d = vodInfo.details
            current.copy(
                name = current.name,
                poster = current.poster ?: d.poster,
                backdrop = d.backdrop ?: d.poster,
                plot = d.plot ?: current.plot,
                genre = d.genre ?: current.genre,
                director = d.director,
                cast = d.cast,
                releaseDate = d.releaseDate,
                year = current.year ?: d.releaseDate?.let(app.fluide.core.xtream.XtreamParser::yearOf),
                durationSec = d.durationSec,
                rating = current.rating ?: d.rating,
                trailer = d.trailer,
                country = d.country,
                extension = vodInfo.extension ?: current.extension,
                loading = false,
            )
        }
    }

    private suspend fun loadSeriesInfo(sourceId: Long, id: Long, key: String, xtream: Boolean) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val progress = media.episodeProgress(sourceId, key)
                val lastKey = media.lastEpisodeKey(sourceId, key)
                if (xtream) {
                    val login = sourceManager.login(sourceId) ?: return@runCatching null
                    val info = sourceManager.xtream.seriesInfo(login, key, coroutineContext.job)
                    val episodes = info.episodes.map { e ->
                        val watch = progress[e.id.toString()]
                        Episode(
                            key = e.id.toString(),
                            season = e.season,
                            number = e.episode,
                            title = e.title ?: "Épisode ${e.episode}",
                            plot = e.plot,
                            durationSec = e.durationSec,
                            image = e.image,
                            extension = e.extension,
                            url = null,
                            progress = watch?.fraction ?: -1f,
                            positionMs = watch?.takeUnless { it.finished }?.positionMs ?: 0,
                        )
                    }
                    val names = info.seasons.associate { it.number to it.name }
                    SeriesData(info.details, episodes, names, lastKey)
                } else {
                    val episodes = media.storedEpisodes(sourceId, key).map { e ->
                        val watch = progress[e.key]
                        e.copy(progress = watch?.fraction ?: -1f, positionMs = watch?.takeUnless { it.finished }?.positionMs ?: 0)
                    }
                    SeriesData(null, episodes, emptyMap(), lastKey)
                }
            }.getOrNull()
        }
        detailsState.update { current ->
            if (current == null || current.id != id || current.section != Section.SERIES) return@update current
            if (result == null) return@update current.copy(loading = false)
            val seasons = result.episodes.groupBy { it.season }.toSortedMap().map { (number, list) ->
                Season(number, result.seasonNames[number], list)
            }
            val ordered = seasons.flatMap { it.episodes }
            val last = ordered.indexOfFirst { it.key == result.lastKey }
            val continueWith = when {
                last < 0 -> ordered.firstOrNull()
                ordered[last].progress >= FINISHED_FRACTION -> ordered.getOrNull(last + 1) ?: ordered[last]
                else -> ordered[last]
            }
            val d = result.details
            current.copy(
                poster = current.poster ?: d?.poster,
                backdrop = d?.backdrop ?: current.poster,
                plot = d?.plot ?: current.plot,
                genre = d?.genre ?: current.genre,
                director = d?.director,
                cast = d?.cast,
                releaseDate = d?.releaseDate,
                durationSec = d?.durationSec,
                rating = current.rating ?: d?.rating,
                trailer = d?.trailer,
                country = d?.country,
                seasons = seasons,
                continueEpisode = continueWith,
                loading = false,
            )
        }
    }

    private class SeriesData(
        val details: app.fluide.core.xtream.MediaDetails?,
        val episodes: List<Episode>,
        val seasonNames: Map<Int, String?>,
        val lastKey: String?,
    )

    fun toggleDetailsFavorite() {
        val d = detailsState.value ?: return
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { media.toggleFavorite(d.sourceId, d.section, d.key) }
            detailsState.update { if (it?.id == d.id) it.copy(favorite = added) else it }
            toast(if (added) R.string.notice_favorite_added else R.string.notice_favorite_removed)
            (if (d.section == Section.SERIES) series else movies).invalidate()
        }
    }

    fun toggleMediaFavorite(section: Section, index: Int) {
        val browser = if (section == Section.SERIES) series else movies
        val item = browser.pager.itemAt(index) ?: return
        val source = uiState.value.activeSourceId
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { media.toggleFavorite(source, section, item.key) }
            toast(if (added) R.string.notice_favorite_added else R.string.notice_favorite_removed)
            browser.invalidate()
        }
    }

    /** Plays the movie of the details page, from its resume point unless [fromStart]. */
    fun playMovie(fromStart: Boolean) {
        val d = detailsState.value ?: return
        if (d.section != Section.MOVIES) return
        viewModelScope.launch {
            val record = withContext(Dispatchers.IO) { media.record(Section.MOVIES, d.id) } ?: return@launch
            val url = record.url ?: run {
                toast(R.string.import_failed_reimport)
                return@launch
            }
            startVideo(
                session = VodSession(d.sourceId, MediaRepository.KIND_MOVIE, d.key, null, d.name, d.poster, null, null, emptyList()),
                url = url,
                title = d.name,
                subtitle = listOfNotNull(d.year?.toString(), d.genre).joinToString(" · ").ifEmpty { null },
                startMs = if (fromStart) 0 else d.resumeMs,
                group = d.group,
            )
        }
    }

    fun playEpisode(episode: Episode, fromStart: Boolean = false) {
        val d = detailsState.value ?: return
        if (d.section != Section.SERIES) return
        viewModelScope.launch {
            val url = episode.url ?: withContext(Dispatchers.IO) {
                sourceManager.login(d.sourceId)?.urls?.episode(episode.key.toLongOrNull() ?: return@withContext null, episode.extension)
            }
            if (url == null) {
                toast(R.string.import_failed_reimport)
                return@launch
            }
            val playlist = d.seasons.flatMap { it.episodes }
            startVideo(
                session = VodSession(
                    d.sourceId, MediaRepository.KIND_EPISODE, episode.key, d.key, d.name, d.poster, episode.season, episode.number, playlist,
                ),
                url = url,
                title = d.name,
                subtitle = "S${episode.season} E${episode.number} · ${episode.title}",
                startMs = if (fromStart) 0 else episode.positionMs,
                group = d.group,
            )
        }
    }

    private fun startVideo(session: VodSession, url: String, title: String, subtitle: String?, startMs: Long, group: String?) {
        saveVideoProgress()
        upNextJob?.cancel()
        currentChannelId = 0L
        currentIndex = -1
        val source = sourceManager.sources.value.firstOrNull { it.id == session.sourceId }
        val kind = StreamKind.infer(url).takeUnless { it == StreamKind.UNKNOWN } ?: StreamKind.PROGRESSIVE
        val playable = PlayableChannel(
            id = VIDEO_ID_BASE - session.key.hashCode().toLong().let { if (it < 0) -it else it },
            sourceId = session.sourceId,
            number = null,
            name = title,
            nameNorm = SearchNormalizer.normalize(title),
            group = group,
            logo = session.poster,
            epgKey = null,
            url = url,
            kind = kind,
            userAgent = source?.userAgent,
            referrer = null,
            drmScheme = null,
            drmLicenseUrl = null,
            maxConnections = source?.maxConnections ?: 1,
            vod = true,
        )
        vod = session
        controller.play(playable, startMs)
        uiState.update {
            it.copy(
                screen = Screen.PLAYER,
                playerMode = PlayerMode.VOD,
                vodTitle = title,
                vodSubtitle = subtitle,
                upNext = null,
                optionsVisible = false,
            )
        }
        showControls()
        startProgressLoop()
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_MS)
                saveVideoProgress()
            }
        }
    }

    /** Stores the resume point of the movie or episode playing. */
    private fun saveVideoProgress() {
        val session = vod ?: return
        val position = controller.positionMs
        val duration = controller.durationMs
        if (position < MIN_PROGRESS_MS) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                media.saveProgress(
                    session.sourceId, session.kind, session.key, session.seriesKey, session.name, session.poster,
                    session.season, session.episode, position, duration,
                )
            }
        }
    }

    private fun nextEpisode(): Episode? {
        val session = vod ?: return null
        if (session.kind != MediaRepository.KIND_EPISODE) return null
        val index = session.playlist.indexOfFirst { it.key == session.key }
        return if (index >= 0) session.playlist.getOrNull(index + 1) else null
    }

    private fun onVideoEnded() {
        val state = uiState.value
        if (state.screen != Screen.PLAYER || state.playerMode == PlayerMode.LIVE) return
        saveVideoProgress()
        val next = nextEpisode()
        if (next == null || !settingsStore.current.autoNextEpisode) {
            closeVideo()
            return
        }
        upNextJob?.cancel()
        upNextJob = viewModelScope.launch {
            for (left in UP_NEXT_SECONDS downTo 1) {
                uiState.update { it.copy(upNext = UpNext("S${next.season} E${next.number} · ${next.title}", left)) }
                delay(1_000)
            }
            uiState.update { it.copy(upNext = null) }
            playEpisode(next, fromStart = true)
        }
    }

    fun playNextNow() {
        val next = nextEpisode() ?: return
        upNextJob?.cancel()
        uiState.update { it.copy(upNext = null) }
        playEpisode(next, fromStart = true)
    }

    /** Leaves the video player: back to the details page (or the programme guide for a recording). */
    private fun closeVideo() {
        saveVideoProgress()
        progressJob?.cancel()
        upNextJob?.cancel()
        val mode = uiState.value.playerMode
        controller.stop()
        vod = null
        val back = if (mode == PlayerMode.CATCHUP) Screen.EPG else if (detailsState.value != null) Screen.DETAILS else Screen.HOME
        uiState.update { it.copy(screen = back, controlsVisible = false, optionsVisible = false, upNext = null, playerMode = PlayerMode.LIVE) }
        val d = detailsState.value
        if (back == Screen.DETAILS && d != null) {
            // Refresh resume points and the episode to continue with.
            viewModelScope.launch {
                delay(300) // the last progress write runs on IO
                openDetailsRefresh(d)
            }
        }
    }

    private suspend fun openDetailsRefresh(d: MediaDetailsUi) {
        if (d.section == Section.MOVIES) {
            val resume = withContext(Dispatchers.IO) { media.progress(d.sourceId, MediaRepository.KIND_MOVIE, d.key) }
            detailsState.update {
                if (it?.id == d.id) it.copy(resumeMs = resume?.takeUnless { r -> r.finished }?.positionMs ?: 0, durationMs = resume?.durationMs ?: 0) else it
            }
            movies.invalidate()
        } else {
            val source = sourceManager.sources.value.firstOrNull { it.id == d.sourceId }
            loadSeriesInfo(d.sourceId, d.id, d.key, source?.type == SourceType.XTREAM)
            series.invalidate()
        }
    }

    // ------------------------------------------------------------------ parental control

    /** Runs [action] now if the parental control allows it, after the PIN otherwise. */
    fun requirePin(action: () -> Unit) {
        if (!settingsStore.current.parentalEnabled || parentalUnlocked) {
            action()
            return
        }
        pendingUnlock = action
        pinChangeStep = false
        uiState.update { it.copy(pin = PinRequest()) }
    }

    /** Settings that change the parental control always ask the current PIN. */
    fun requireCurrentPin(action: () -> Unit) {
        pendingUnlock = action
        pinChangeStep = false
        uiState.update { it.copy(pin = PinRequest()) }
    }

    fun changePin() = requireCurrentPin {
        pinChangeStep = true
        uiState.update { it.copy(pin = PinRequest(changing = true)) }
    }

    fun submitPin(pin: String) {
        if (pinChangeStep) {
            if (pin.length != 4 || !pin.all(Char::isDigit)) {
                uiState.update { it.copy(pin = PinRequest(changing = true, error = true)) }
                return
            }
            settingsStore.update { it.copy(parentalPin = pin) }
            pinChangeStep = false
            uiState.update { it.copy(pin = null) }
            toast(R.string.pin_changed)
            return
        }
        if (pin != settingsStore.current.parentalPin) {
            uiState.update { it.copy(pin = PinRequest(error = true)) }
            return
        }
        uiState.update { it.copy(pin = null) }
        val action = pendingUnlock
        pendingUnlock = null
        if (!parentalUnlocked) {
            parentalUnlocked = true
            applyRules()
        }
        action?.invoke()
    }

    fun cancelPin() {
        pendingUnlock = null
        pinChangeStep = false
        uiState.update { it.copy(pin = null) }
    }

    fun setParental(enabled: Boolean) = requireCurrentPin {
        settingsStore.update { it.copy(parentalEnabled = enabled) }
        parentalUnlocked = false
        applyRules()
    }

    /** Category rules or parental state changed: rebuild every list. */
    private fun applyRules() {
        val source = uiState.value.activeSourceId
        if (source == 0L) return
        viewModelScope.launch {
            loadRules(source)
            val groups = loadLists(source)
            val previous = guideState.value.selected
            showGuideList(ChannelFilter.All, allIds, groups)
            if (previous != ChannelFilter.All && previous !is ChannelFilter.Search) applyFilter(previous)
            reloadMedia(source, keepSelection = true)
        }
    }

    // ------------------------------------------------------------------ manage categories

    fun openCategories(section: Section) = requirePin {
        loadCategories(section)
        uiState.update { it.copy(screen = Screen.CATEGORIES) }
    }

    fun loadCategories(section: Section) {
        val source = uiState.value.activeSourceId
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                val rules = media.rules(source, section)
                groupsOf(source, section).mapNotNull { group ->
                    val name = group.name ?: return@mapNotNull null
                    val adult = AdultFilter.isAdult(name)
                    val rule = rules[name]
                    CategoryEntry(
                        name = name,
                        count = group.count,
                        hidden = rule == CategoryMode.HIDDEN,
                        locked = rule == CategoryMode.LOCKED || (rule == null && adult),
                        adult = adult,
                    )
                }
            }
            categoriesState.value = CategoriesState(section, entries)
        }
    }

    /** Cycles a category: visible -> locked -> hidden -> visible. */
    fun cycleCategory(name: String) {
        val state = categoriesState.value
        val entry = state.entries.firstOrNull { it.name == name } ?: return
        val source = uiState.value.activeSourceId
        val next = when {
            entry.hidden -> null
            entry.locked -> CategoryMode.HIDDEN
            else -> CategoryMode.LOCKED
        }
        viewModelScope.launch {
            // Adult categories are locked by default (no rule): they cycle between locked and hidden while
            // the parental control is on.
            withContext(Dispatchers.IO) { media.setRule(source, state.section, name, next) }
            loadCategories(state.section)
        }
    }

    // ------------------------------------------------------------------ sources and settings

    fun addUrlSource(name: String, url: String, epgUrl: String, userAgent: String, maxConnections: Int) {
        viewModelScope.launch {
            val label = name.ifBlank { getApplication<Application>().getString(R.string.source_default_name) }
            adding.value = true
            runCatching { sourceManager.addUrl(label, url, epgUrl, userAgent, maxConnections) }
            adding.value = false
        }
    }

    fun addXtreamSource(name: String, server: String, username: String, password: String, format: String) {
        viewModelScope.launch {
            adding.value = true
            addError.value = null
            val result = runCatching { sourceManager.addXtream(name, server, username, password, format) }
                .getOrElse { AddResult(null, it.message ?: it.javaClass.simpleName) }
            adding.value = false
            if (result.error != null) addError.value = result.error else toast(R.string.source_added)
        }
    }

    fun addFileSource(uri: Uri, name: String) {
        viewModelScope.launch {
            val label = name.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist" }
            runCatching { sourceManager.addFile(label, uri) }
        }
    }

    fun refreshSource(id: Long) = sourceManager.refresh(id)

    fun refreshActive() {
        val id = uiState.value.activeSourceId
        if (id != 0L) {
            sourceManager.refresh(id)
            toast(R.string.home_refreshing)
        }
    }

    fun activateSource(id: Long) {
        if (id == uiState.value.activeSourceId) return
        controller.stop()
        currentChannelId = 0L
        recallChannelId = 0L
        currentIndex = -1
        parentalUnlocked = false
        shortEpgTried.clear()
        detailsState.value = null
        viewModelScope.launch {
            activate(id, resume = false)
            uiState.update { it.copy(screen = Screen.HOME) }
        }
    }

    fun deleteSource(id: Long) {
        viewModelScope.launch {
            sourceManager.delete(id)
            if (id != uiState.value.activeSourceId) return@launch
            controller.stop()
            currentChannelId = 0L
            recallChannelId = 0L
            currentIndex = -1
            allIds = LongArray(0)
            zapIds = allIds
            detailsState.value = null
            val next = sourceManager.sources.value.firstOrNull()
            if (next != null) {
                activate(next.id, resume = false)
                uiState.update { it.copy(screen = Screen.SOURCES) }
            } else {
                settingsStore.update { it.copy(activeSourceId = 0, lastChannelName = null) }
                uiState.update { it.copy(activeSourceId = 0, screen = Screen.SOURCES) }
                showGuideList(ChannelFilter.All, allIds, emptyList())
            }
        }
    }

    fun renameSource(id: Long, name: String) {
        viewModelScope.launch { sourceManager.rename(id, name) }
    }

    fun setMaxConnections(id: Long, maxConnections: Int) {
        viewModelScope.launch { sourceManager.setMaxConnections(id, maxConnections) }
    }

    fun setStreamFormat(id: Long, format: String) {
        viewModelScope.launch {
            sourceManager.setStreamFormat(id, format)
            sourceManager.refresh(id, playlist = true, epg = false)
        }
    }

    fun updateSettings(transform: (SettingsValues) -> SettingsValues) {
        val before = settingsStore.current
        settingsStore.update(transform)
        val after = settingsStore.current
        val playerOptionsChanged = before.tunneling != after.tunneling ||
            before.audioPassthrough != after.audioPassthrough ||
            before.autoFrameRate != after.autoFrameRate
        if (playerOptionsChanged) controller.reapplySettings()
    }

    /** Forgets resume points and the recently watched channels of the active playlist. */
    fun clearHistory() {
        val source = uiState.value.activeSourceId
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                media.clearProgress(source)
                repository.clearHistory(source)
            }
            movies.invalidate()
            series.invalidate()
            toast(R.string.settings_history_cleared)
        }
    }

    /** Images cached on disk and in memory (posters, logos). */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache() {
        val loader = getApplication<Application>().imageLoader
        loader.memoryCache?.clear()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { loader.diskCache?.clear() } }
            toast(R.string.settings_cache_cleared)
        }
    }

    private companion object {
        const val BANNER_MS = 4_000L
        const val NUMBER_COMMIT_MS = 1_600L
        const val SEARCH_DEBOUNCE_MS = 250L
        const val PRELOAD_DELAY_MS = 2_000L
        const val PANEL_DELAY_MS = 150L
        const val PANEL_HOURS = 12L
        const val PANEL_PROGRAMMES = 4
        const val CONTROLS_MS = 4_000L
        const val SEEK_STEP_MS = 10_000L
        const val SEEK_FAST_MS = 30_000L
        const val SEEK_STREAK_MS = 700L
        const val PROGRESS_SAVE_MS = 15_000L
        const val MIN_PROGRESS_MS = 10_000L
        const val UP_NEXT_SECONDS = 8
        const val EPG_FUTURE_DAYS = 2
        const val MAX_CATCHUP_DAYS = 14
        const val FINISHED_FRACTION = 0.93f

        /** Ids given to videos and recordings: never equal to a channel id (those are positive). */
        const val VIDEO_ID_BASE = -1_000_000L
        const val RECORDING_ID = -1L

        /** Local midnight of the day containing [epochSec]. */
        fun dayStart(epochSec: Long): Long = Calendar.getInstance().run {
            timeInMillis = epochSec * 1_000
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis / 1_000
        }

        /** Local midnight [days] days after [dayStartSec] (handles DST changes). */
        fun shiftDays(dayStartSec: Long, days: Int): Long = Calendar.getInstance().run {
            timeInMillis = dayStartSec * 1_000
            add(Calendar.DAY_OF_YEAR, days)
            timeInMillis / 1_000
        }
    }
}

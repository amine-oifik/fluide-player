package app.fluide.player.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import app.fluide.core.zap.ChannelNumberInput
import app.fluide.core.zap.PreloadLevel
import app.fluide.core.zap.PreloadPlanner
import app.fluide.core.zap.ZapGate
import app.fluide.player.FluideApp
import app.fluide.player.R
import app.fluide.player.SettingsValues
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.GroupInfo
import app.fluide.player.data.ImportProgress
import app.fluide.player.data.PlayableChannel
import app.fluide.player.data.SourceInfo
import app.fluide.player.device.DeviceProfile
import app.fluide.player.playback.PlaybackController
import app.fluide.player.playback.PlaybackPhase
import app.fluide.player.playback.PlaybackStats
import app.fluide.player.playback.PlaybackUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen logic: zapping (gate, number entry, recall, preloading of neighbours), the channel guide,
 * sources and settings. Playback itself is delegated to [PlaybackController].
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = (application as FluideApp).graph
    private val repository = graph.channels
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
    private var lastBackAtMs = 0L

    private val zapGate = ZapGate()
    private val numberInput = ChannelNumberInput()
    private var zapJob: Job? = null
    private var bannerJob: Job? = null
    private var numberJob: Job? = null
    private var searchJob: Job? = null
    private var preloadJob: Job? = null

    init {
        viewModelScope.launch { start() }
        viewModelScope.launch { sourceManager.channelChanges.collect { onChannelsChanged(it) } }
        viewModelScope.launch {
            sourceManager.guideChanges.collect { id -> if (id == uiState.value.activeSourceId) pager.invalidate() }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onStart() = controller.onStart()

    fun onStop() = controller.onStop()

    override fun onCleared() {
        controller.release()
    }

    fun stats(): PlaybackStats? = controller.stats()

    private suspend fun start() {
        sourceManager.reload()
        val list = sourceManager.sources.value
        val saved = settingsStore.current.activeSourceId
        val active = list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
        if (active == null) {
            uiState.update { it.copy(loading = false, screen = Screen.SOURCES) }
            return
        }
        activate(active, resume = settingsStore.current.resumeLastChannel)
        sourceManager.refreshIfStale(active)
    }

    private suspend fun activate(sourceId: Long, resume: Boolean) {
        settingsStore.update { it.copy(activeSourceId = sourceId) }
        uiState.update { it.copy(activeSourceId = sourceId) }
        val groups = loadLists(sourceId)
        showGuideList(ChannelFilter.All, allIds, groups)
        if (allIds.isEmpty()) {
            uiState.update { it.copy(loading = false, screen = Screen.SOURCES) }
            return
        }
        val last = settingsStore.current.lastChannelName
        val resumeId = if (resume && last != null) withContext(Dispatchers.IO) { repository.idByName(sourceId, last) } else null
        if (resumeId != null) {
            uiState.update { it.copy(loading = false, screen = Screen.PLAYER) }
            playId(resumeId)
        } else {
            uiState.update { it.copy(loading = false, screen = Screen.GUIDE) }
        }
    }

    /** Loads the id list, groups and number prefixes of a source. Returns the guide groups. */
    private suspend fun loadLists(sourceId: Long): List<GuideGroup> {
        val (groups, ids, prefixes) = withContext(Dispatchers.IO) {
            Triple(
                repository.groups(sourceId),
                repository.channelIds(sourceId, ChannelFilter.All),
                repository.numberPrefixes(sourceId),
            )
        }
        allIds = ids
        zapIds = ids
        numberPrefixes = prefixes
        return guideGroups(groups, ids.size)
    }

    private suspend fun onChannelsChanged(sourceId: Long) {
        val active = uiState.value.activeSourceId
        if (active == 0L || (active == sourceId && allIds.isEmpty())) {
            activate(sourceId, resume = false)
            return
        }
        if (sourceId != active) return
        // The list was re-imported (new ids) or the guide was updated: reload, keep playing.
        val groups = loadLists(sourceId)
        val playingName = controller.currentChannel?.name
        val playingId = if (playingName != null) withContext(Dispatchers.IO) { repository.idByName(sourceId, playingName) } else null
        currentChannelId = playingId ?: 0L
        currentIndex = if (playingId != null) zapIds.indexOf(playingId) else -1
        recallChannelId = 0L
        val previous = guideState.value.selected
        showGuideList(ChannelFilter.All, allIds, groups)
        if (previous != ChannelFilter.All && previous !is ChannelFilter.Search) selectFilter(previous)
    }

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
        if (index >= 0) playIndex(index)
    }

    private fun playIndex(index: Int) {
        val id = zapIds.getOrNull(index) ?: return
        pendingIndex = -1
        if (id != currentChannelId && currentChannelId != 0L) recallChannelId = currentChannelId
        currentChannelId = id
        currentIndex = index
        showBanner(index)
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
            planPreload(index, channel)
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
     * Key presses reach the view model before Compose, but only the player screen uses them here.
     * BACK is not a key event any more on Android 16 (predictive back): see [onBack].
     */
    fun onKeyDown(keyCode: Int): KeyResult {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) return onBack()
        val state = uiState.value
        if (state.loading || state.screen != Screen.PLAYER) return KeyResult.IGNORED
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (playback.value.error != null) controller.retryNow() else openGuide()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_GUIDE -> openGuide()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> toggleInfo()
            KeyEvent.KEYCODE_LAST_CHANNEL -> recall()
            KeyEvent.KEYCODE_MENU -> toggleStats()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> controller.togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (playback.value.phase == PlaybackPhase.PAUSED) controller.togglePause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (playback.value.phase != PlaybackPhase.PAUSED) controller.togglePause()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> onDigit(keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            else -> return KeyResult.IGNORED
        }
        return KeyResult.HANDLED
    }

    /** Back button / gesture, from the activity's OnBackPressedDispatcher. */
    fun onBack(): KeyResult {
        val state = uiState.value
        return when (state.screen) {
            Screen.SOURCES -> if (allIds.isNotEmpty()) {
                uiState.update { it.copy(screen = if (currentChannelId != 0L) Screen.PLAYER else Screen.GUIDE) }
                KeyResult.HANDLED
            } else {
                confirmExit()
            }
            Screen.GUIDE -> when {
                guideState.value.query.isNotEmpty() -> {
                    search("")
                    KeyResult.HANDLED
                }
                currentChannelId != 0L -> {
                    closeOverlays()
                    KeyResult.HANDLED
                }
                else -> confirmExit()
            }
            Screen.PLAYER -> when {
                state.numberEntry.isNotEmpty() -> {
                    numberInput.clear()
                    numberJob?.cancel()
                    uiState.update { it.copy(numberEntry = "") }
                    KeyResult.HANDLED
                }
                state.bannerVisible -> {
                    hideBanner()
                    KeyResult.HANDLED
                }
                else -> confirmExit()
            }
        }
    }

    private fun confirmExit(): KeyResult {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackAtMs < EXIT_CONFIRM_MS) return KeyResult.EXIT
        lastBackAtMs = now
        toast(R.string.notice_exit)
        return KeyResult.HANDLED
    }

    fun retry() = controller.retryNow()

    fun togglePause() = controller.togglePause()

    private fun toggleStats() = settingsStore.update { it.copy(showStats = !it.showStats) }

    private fun toast(resId: Int, arg: Int? = null) {
        uiState.update { it.copy(toast = Toast(resId, arg)) }
    }

    fun consumeToast(toast: Toast) {
        uiState.update { if (it.toast?.id == toast.id) it.copy(toast = null) else it }
    }

    // ------------------------------------------------------------------ guide

    fun openGuide() {
        hideBanner()
        val focus = guideIds.indexOf(currentChannelId).coerceAtLeast(0)
        guideState.update { it.copy(focusIndex = focus, version = it.version + 1, focusList = true) }
        uiState.update { it.copy(screen = Screen.GUIDE) }
    }

    fun openSources() = uiState.update { it.copy(screen = Screen.SOURCES) }

    fun closeOverlays() {
        if (currentChannelId == 0L && allIds.isNotEmpty()) {
            uiState.update { it.copy(screen = Screen.GUIDE) }
        } else if (allIds.isNotEmpty()) {
            uiState.update { it.copy(screen = Screen.PLAYER) }
        }
    }

    fun selectFilter(filter: ChannelFilter) {
        searchJob?.cancel()
        val source = uiState.value.activeSourceId
        viewModelScope.launch {
            val ids = if (filter == ChannelFilter.All) allIds else withContext(Dispatchers.IO) { repository.channelIds(source, filter) }
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
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val filter = ChannelFilter.Search(query)
            val ids = withContext(Dispatchers.IO) { repository.channelIds(source, filter) }
            showGuideList(filter, ids)
        }
    }

    fun playFromGuide(index: Int) {
        val id = guideIds.getOrNull(index) ?: return
        val filter = guideState.value.selected
        // Zapping inside search results would be confusing: CH+/CH- then use the full list.
        zapIds = if (filter is ChannelFilter.Search) allIds else guideIds
        uiState.update { it.copy(screen = Screen.PLAYER) }
        zapJob?.cancel()
        zapGate.reset()
        playId(id)
    }

    fun toggleFavorite(index: Int) {
        val id = guideIds.getOrNull(index) ?: return
        val source = uiState.value.activeSourceId
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                repository.row(id, 0)?.let { repository.toggleFavorite(source, it.name) }
            } ?: return@launch
            toast(if (added) R.string.notice_favorite_added else R.string.notice_favorite_removed)
            if (guideState.value.selected == ChannelFilter.Favorites) selectFilter(ChannelFilter.Favorites) else pager.invalidate()
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

    private fun guideGroups(groups: List<GroupInfo>, total: Int): List<GuideGroup> = buildList {
        add(GuideGroup(ChannelFilter.All, null, total))
        add(GuideGroup(ChannelFilter.Favorites, null, -1))
        for (group in groups) add(GuideGroup(ChannelFilter.Group(group.name), group.name, group.count))
    }

    // ------------------------------------------------------------------ sources and settings

    fun addUrlSource(name: String, url: String, epgUrl: String, userAgent: String, maxConnections: Int) {
        viewModelScope.launch {
            val label = name.ifBlank { getApplication<Application>().getString(R.string.source_default_name) }
            runCatching { sourceManager.addUrl(label, url, epgUrl, userAgent, maxConnections) }
        }
    }

    fun addFileSource(uri: Uri, name: String) {
        viewModelScope.launch {
            val label = name.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "Playlist" }
            runCatching { sourceManager.addFile(label, uri) }
        }
    }

    fun refreshSource(id: Long) = sourceManager.refresh(id)

    fun activateSource(id: Long) {
        if (id == uiState.value.activeSourceId) return
        controller.stop()
        currentChannelId = 0L
        recallChannelId = 0L
        currentIndex = -1
        viewModelScope.launch { activate(id, resume = false) }
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
            val next = sourceManager.sources.value.firstOrNull()
            if (next != null) {
                activate(next.id, resume = false)
            } else {
                settingsStore.update { it.copy(activeSourceId = 0, lastChannelName = null) }
                uiState.update { it.copy(activeSourceId = 0, screen = Screen.SOURCES) }
                showGuideList(ChannelFilter.All, allIds, emptyList())
            }
        }
    }

    fun setMaxConnections(id: Long, maxConnections: Int) {
        viewModelScope.launch { sourceManager.setMaxConnections(id, maxConnections) }
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

    private companion object {
        const val BANNER_MS = 4_000L
        const val NUMBER_COMMIT_MS = 1_600L
        const val SEARCH_DEBOUNCE_MS = 250L
        const val PRELOAD_DELAY_MS = 2_000L
        const val EXIT_CONFIRM_MS = 2_000L
    }
}

package app.fluide.player.ui.multiview

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import app.fluide.core.device.DeviceTier
import app.fluide.core.m3u.StreamKind
import app.fluide.player.FluideApp
import app.fluide.player.data.ChannelFilter
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.PlayableChannel
import app.fluide.player.device.DeviceProfile
import app.fluide.player.net.HttpStack
import app.fluide.player.playback.MediaSourceBuilder
import app.fluide.player.playback.StreamRequest
import app.fluide.player.ui.RowPager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One tile of the multi-screen view. [channelId] 0: empty tile. */
data class MultiSlot(
    val index: Int,
    val channelId: Long = 0,
    val name: String? = null,
    val logo: String? = null,
    val loading: Boolean = false,
    val error: Boolean = false,
    val number: Int? = null,
    /** Programme on air (from the guide), null when unknown. */
    val nowTitle: String? = null,
    val nowStartSec: Long = 0,
    val nowStopSec: Long = 0,
    /** Automatic reconnection in progress: attempt number (1..[MultiViewController.MAX_ATTEMPTS]), 0 otherwise. */
    val attempt: Int = 0,
)

data class MultiViewState(
    /** 2 (side by side) or 4 (2 x 2). */
    val layout: Int = 4,
    val slots: List<MultiSlot> = emptyList(),
    /** Tile whose sound is heard. */
    val audioSlot: Int = 0,
    /** Tile whose channel picker is open, -1 when closed. */
    val pickerSlot: Int = -1,
    /** With 4 tiles: the first one large and the three others in a column ("1 + 3"). */
    val featured: Boolean = false,
    /** Every tile silent (the audio tile is kept). */
    val muted: Boolean = false,
)

/** Entry of the category column of the channel picker. [label] null: localized name of [filter]. */
data class MultiPickerGroup(val filter: ChannelFilter, val label: String?, val count: Int)

/** Channel picker: categories and the list of the selected one (rows in [MultiViewController.pickerRows]). */
data class MultiPickerState(
    val groups: List<MultiPickerGroup> = emptyList(),
    val selected: ChannelFilter = ChannelFilter.All,
    val size: Int = 0,
    /** Row to focus (the channel of the tile being changed), when [focusList]. */
    val focusIndex: Int = 0,
    /** True when the list should take the focus (picker opened), false after a category change. */
    val focusList: Boolean = true,
    /** Changes with every new list. */
    val version: Int = 0,
    val loading: Boolean = false,
)

/** A warning to show once. [count]: decoder instances or allowed connections. */
data class MultiViewNotice(val kind: Kind, val count: Int, val id: Long = System.nanoTime()) {
    enum class Kind {
        /** The device has fewer hardware video decoders than tiles. */
        DECODERS,

        /** The playlist allows fewer simultaneous connections than channels on screen. */
        CONNECTIONS,
    }
}

/**
 * Multi-screen: 2 or 4 live channels at once, one player per tile, sound from one tile only.
 *
 * Each tile has its own ExoPlayer, kept light: small buffers (the device byte budget is shared between
 * the tiles), a video size cap (720p with 4 tiles), PCM audio only (the volume of a passthrough track
 * cannot be lowered), no frame-rate switching and no audio focus. Decoder fallback lets a tile use a
 * software decoder when the hardware decoders are all taken. Errors and long stalls reconnect with a
 * growing delay, then the tile shows an error (OK retries). Every call happens on the main thread.
 */
@OptIn(UnstableApi::class)
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

    private val pickerState = MutableStateFlow(MultiPickerState())

    /** Categories and list of the channel picker. */
    val picker: StateFlow<MultiPickerState> = pickerState.asStateFlow()

    /** Rows of the picker list, loaded page by page as the list scrolls. */
    val pickerRows = RowPager(channels, scope)

    private val noticeList = MutableStateFlow<List<MultiViewNotice>>(emptyList())

    /** Warnings waiting to be shown (first one first); see [dismissNotice]. */
    val notices: StateFlow<List<MultiViewNotice>> = noticeList.asStateFlow()

    private class Tile(var index: Int) {
        var channelId = 0L
        var channel: PlayableChannel? = null
        var player: ExoPlayer? = null
        var listener: Player.Listener? = null
        var attempts = 0
        var loadJob: Job? = null
        var retryJob: Job? = null
        var stallJob: Job? = null

        /** The channel comes from the memory of a previous visit: its category is checked again (hidden, locked). */
        var fromMemory = false

        /** Stream family tried after a format error (HLS <-> MPEG-TS, once, like the main player). */
        var kindOverride: StreamKind? = null
        var formatFallbackTried = false

        /** The channel played at least once: a later parsing error is damaged data, not a wrong format. */
        var everReady = false

        /** Forgets the recovery state of the previous channel or attempt. */
        fun resetRecovery() {
            attempts = 0
            kindOverride = null
            formatFallbackTried = false
            everReady = false
        }
    }

    private val appContext: Context = context.applicationContext ?: context
    private val sources = MediaSourceBuilder(http, NoTransfers)
    private val tiles = Array(MAX_TILES) { Tile(it) }

    /** Last channel of each tile, per playlist: the view comes back as it was left. */
    private val remembered = HashMap<Long, LongArray>()
    private var sourceId = 0L
    private var opened = false
    private var visible = false
    private var device: DeviceProfile? = null
    private var decoderWarningShown = false
    private var connectionWarningShown = false

    /** Every channel of the playlist (CH+ / CH- inside a tile). */
    private var allIds = LongArray(0)
    private var pickerIds = LongArray(0)
    private var pickerSourceId = -1L
    private var pickerJob: Job? = null
    private var refreshJob: Job? = null

    private val slotCount: Int get() = mutable.value.slots.size

    // ------------------------------------------------------------------ contract

    /** Opens the view for [sourceId]; the first tile plays [firstChannelId] when not 0. */
    fun open(sourceId: Long, layout: Int, firstChannelId: Long) {
        release()
        this.sourceId = sourceId
        opened = true
        visible = true
        connectionWarningShown = false
        val tileCount = normalized(layout)
        val memory = remembered.getOrPut(sourceId) { LongArray(MAX_TILES) }
        if (firstChannelId != 0L) {
            // The channel already sits in another tile: the two tiles trade places.
            val duplicate = memory.indexOfFirst { it == firstChannelId }
            if (duplicate > 0) memory[duplicate] = memory[0]
            memory[0] = firstChannelId
        }
        for (tile in tiles) {
            tile.channelId = memory[tile.index]
            tile.channel = null
            tile.resetRecovery()
            // The channel asked for is trusted; the remembered ones may sit in a category locked since.
            tile.fromMemory = !(tile.index == 0 && firstChannelId != 0L)
        }
        mutable.value = MultiViewState(
            layout = tileCount,
            slots = List(tileCount) { MultiSlot(it, channelId = tiles[it].channelId, loading = tiles[it].channelId != 0L) },
            audioSlot = 0,
        )
        publishPlayers()
        for (i in 0 until tileCount) start(tiles[i])
        loadAllIds()
        loadDevice()
        startRefresh()
    }

    /** 2 tiles side by side or a 2 x 2 grid. */
    fun setLayout(layout: Int) = applyLayout(normalized(layout), featured = false)

    /** Four tiles, the first one large ("1 + 3"). */
    fun setFeaturedLayout() = applyLayout(MAX_TILES, featured = true)

    /** Plays [channelId] in [slot]. A channel already shown in another tile trades places with this one. */
    fun assign(slot: Int, channelId: Long) {
        if (!opened || slot !in 0 until slotCount || channelId == 0L) return
        val tile = tiles[slot]
        if (tile.channelId == channelId) {
            if (mutable.value.slots[slot].error) retry(slot)
            return
        }
        val other = (0 until slotCount).firstOrNull { it != slot && tiles[it].channelId == channelId }
        if (other != null) {
            swap(other, slot)
            setAudio(slot)
            return
        }
        tile.channelId = channelId
        tile.channel = null
        tile.fromMemory = false
        memorize(slot, channelId)
        replaceSlot(slot, MultiSlot(slot, channelId = channelId, loading = true))
        start(tile)
        // The audio tile is empty: the new channel takes the sound.
        val audio = mutable.value.audioSlot
        if (audio !in 0 until slotCount || tiles[audio].channelId == 0L) setAudio(slot)
    }

    fun clear(slot: Int) {
        if (!opened || slot !in 0 until slotCount) return
        val tile = tiles[slot]
        tile.loadJob?.cancel()
        releasePlayer(tile)
        tile.channelId = 0L
        tile.channel = null
        tile.attempts = 0
        memorize(slot, 0L)
        replaceSlot(slot, MultiSlot(slot))
        if (mutable.value.audioSlot == slot) {
            val next = (0 until slotCount).firstOrNull { tiles[it].channelId != 0L }
            if (next != null) setAudio(next)
        }
    }

    fun setAudio(slot: Int) {
        if (!opened || slot !in 0 until slotCount) return
        mutable.update { it.copy(audioSlot = slot, muted = false) }
        applyVolumes()
    }

    fun openPicker(slot: Int) {
        if (!opened || slot !in 0 until slotCount) return
        mutable.update { it.copy(pickerSlot = slot) }
        val current = pickerState.value
        if (pickerSourceId != sourceId || current.groups.isEmpty()) {
            loadPicker(ChannelFilter.All, focusList = true, withGroups = true)
        } else {
            val index = pickerIds.indexOf(tiles[slot].channelId).coerceAtLeast(0)
            pickerState.update { it.copy(focusIndex = index, focusList = true, version = it.version + 1) }
        }
    }

    fun closePicker() = mutable.update { it.copy(pickerSlot = -1) }

    /** The activity is visible again: players are recreated for the assigned channels. */
    fun onStart() {
        if (!opened || visible) return
        visible = true
        for (i in 0 until slotCount) {
            tiles[i].attempts = 0
            start(tiles[i])
        }
        startRefresh()
    }

    /** The activity is hidden: every decoder is released. */
    fun onStop() {
        if (!visible) return
        visible = false
        refreshJob?.cancel()
        for (tile in tiles) {
            tile.loadJob?.cancel()
            releasePlayer(tile)
        }
        mutable.update { s -> s.copy(slots = s.slots.map { if (it.channelId != 0L) it.copy(loading = true, error = false, attempt = 0) else it }) }
    }

    /** The view is left: everything is released. */
    fun release() {
        opened = false
        visible = false
        refreshJob?.cancel()
        pickerJob?.cancel()
        for (tile in tiles) {
            tile.loadJob?.cancel()
            releasePlayer(tile)
            tile.channelId = 0L
            tile.channel = null
            tile.attempts = 0
        }
        mutable.value = MultiViewState()
        playerList.value = emptyList()
        noticeList.value = emptyList()
        // Categories are read again at the next opening (hidden / locked ones may have changed).
        allIds = LongArray(0)
        pickerSourceId = -1L
        pickerIds = LongArray(0)
        pickerRows.setIds(pickerIds)
        pickerState.value = MultiPickerState()
    }

    // ------------------------------------------------------------------ more actions

    /** Reconnects a tile now (OK on a tile in error). */
    fun retry(slot: Int) {
        if (!opened || slot !in 0 until slotCount) return
        val tile = tiles[slot]
        if (tile.channelId == 0L) return
        tile.resetRecovery()
        start(tile)
    }

    /** Next ([delta] 1) or previous (-1) channel of the playlist in [slot], skipping the channels on screen. */
    fun zap(slot: Int, delta: Int) {
        if (!opened || slot !in 0 until slotCount) return
        val ids = allIds
        if (ids.isEmpty()) return
        val shown = (0 until slotCount).map { tiles[it].channelId }.toSet()
        val from = ids.indexOf(tiles[slot].channelId)
        var position = if (from < 0) (if (delta > 0) -1 else 0) else from
        repeat(minOf(ids.size, ZAP_SCAN_LIMIT)) {
            position = (position + delta).mod(ids.size)
            val id = ids[position]
            if (id !in shown) {
                assign(slot, id)
                return
            }
        }
    }

    /** Swaps the channels (and players: nothing reconnects) of two tiles. The sound follows its channel. */
    fun swap(a: Int, b: Int) {
        if (!opened || a == b || a !in 0 until slotCount || b !in 0 until slotCount) return
        val first = tiles[a]
        tiles[a] = tiles[b]
        tiles[b] = first
        tiles[a].index = a
        tiles[b].index = b
        remembered[sourceId]?.let { memory ->
            val kept = memory[a]
            memory[a] = memory[b]
            memory[b] = kept
        }
        mutable.update { s ->
            val slots = s.slots.toMutableList()
            val slotA = slots[a]
            slots[a] = slots[b].copy(index = a)
            slots[b] = slotA.copy(index = b)
            val audio = when (s.audioSlot) {
                a -> b
                b -> a
                else -> s.audioSlot
            }
            val picker = when (s.pickerSlot) {
                a -> b
                b -> a
                else -> s.pickerSlot
            }
            s.copy(slots = slots, audioSlot = audio, pickerSlot = picker)
        }
        publishPlayers()
        applyQuality()
        applyVolumes()
    }

    /** "1 + 3" layout: the channel of [slot] goes to the large tile and takes the sound. */
    fun promote(slot: Int) {
        if (!opened || slot !in 1 until slotCount) return
        swap(slot, 0)
        setAudio(0)
    }

    fun toggleMute() {
        if (!opened) return
        mutable.update { it.copy(muted = !it.muted) }
        applyVolumes()
    }

    /** Category of the picker. */
    fun selectPickerGroup(filter: ChannelFilter) {
        if (filter == pickerState.value.selected && pickerState.value.size > 0) return
        loadPicker(filter, focusList = false, withGroups = false)
    }

    /** Channel id of picker row [index], null while out of range. */
    fun pickerIdAt(index: Int): Long? = pickerIds.getOrNull(index)

    fun dismissNotice(notice: MultiViewNotice) = noticeList.update { list -> list.filterNot { it.id == notice.id } }

    // ------------------------------------------------------------------ layout, sound, quality

    private fun applyLayout(tileCount: Int, featured: Boolean) {
        val s = mutable.value
        if (!opened || (s.layout == tileCount && s.featured == featured)) return
        val previous = s.slots.size
        for (i in tileCount until MAX_TILES) {
            tiles[i].loadJob?.cancel()
            releasePlayer(tiles[i])
        }
        val memory = remembered[sourceId]
        val slots = List(tileCount) { i ->
            s.slots.getOrNull(i) ?: run {
                val tile = tiles[i]
                tile.channelId = memory?.get(i) ?: tile.channelId
                tile.attempts = 0
                MultiSlot(i, channelId = tile.channelId, loading = tile.channelId != 0L)
            }
        }
        mutable.value = s.copy(
            layout = tileCount,
            featured = featured,
            slots = slots,
            // The tile whose sound was heard is gone: the first one showing a channel takes it.
            audioSlot = if (s.audioSlot < tileCount) s.audioSlot else slots.indexOfFirst { it.channelId != 0L }.coerceAtLeast(0),
            pickerSlot = if (s.pickerSlot < tileCount) s.pickerSlot else -1,
        )
        publishPlayers()
        for (i in previous until tileCount) start(tiles[i])
        applyQuality()
        applyVolumes()
        checkDecoders()
    }

    private fun applyVolumes() {
        val s = mutable.value
        for (i in 0 until slotCount) tiles[i].player?.volume = if (!s.muted && i == s.audioSlot) 1f else 0f
    }

    private fun applyQuality() {
        for (i in 0 until slotCount) tiles[i].player?.let { applyQuality(i, it) }
    }

    /**
     * Video limits of a tile: 1080p for the 2-tile layout and the large tile of "1 + 3", 720p in the grid.
     * On a weak device (or more tiles than hardware decoders) the lowest variant of adaptive streams.
     */
    private fun applyQuality(index: Int, player: ExoPlayer) {
        val s = mutable.value
        val large = s.slots.size <= 2 || (s.featured && index == 0)
        val profile = device
        val weak = profile != null &&
            (profile.budget.tier == DeviceTier.A_LOW || profile.hardwareAvcInstances < s.slots.size)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(if (large) 1920 else 1280, if (large) 1080 else 720)
            .setForceLowestBitrate(weak && !large)
            // Captions would cover a small picture.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // ------------------------------------------------------------------ tiles

    /** Loads the channel of [tile] (when needed) and plays it while the view is visible. */
    private fun start(tile: Tile) {
        tile.loadJob?.cancel()
        tile.retryJob?.cancel()
        tile.stallJob?.cancel()
        val id = tile.channelId
        if (id == 0L) return
        val cached = tile.channel
        if (cached != null && cached.id == id) {
            updateSlot(tile) {
                it.copy(name = cached.name, logo = cached.logo, number = cached.number, loading = true, error = false)
            }
            if (visible) play(tile)
            return
        }
        // Another channel: the previous stream stops now (its connection is freed, its late errors
        // cannot reach the new channel); the last picture stays until the new one starts.
        tile.player?.stop()
        tile.resetRecovery()
        updateSlot(tile) { it.copy(loading = true, error = false, attempt = 0) }
        val excluded = if (tile.fromMemory) excludedGroups() else emptySet()
        tile.loadJob = scope.launch {
            val nowSec = System.currentTimeMillis() / 1_000
            val loaded = withContext(Dispatchers.IO) {
                runCatching { channels.playable(id) to channels.row(id, nowSec) }.getOrNull()
            }
            if (tile.channelId != id || !opened) return@launch
            val channel = loaded?.first
            val group = channel?.group
            if (channel == null || (group != null && group in excluded)) {
                // Gone from the playlist (refresh), unreadable, or remembered from a category hidden or
                // locked since: the tile is emptied rather than playing it without the PIN.
                clear(tile.index)
                return@launch
            }
            tile.fromMemory = false
            tile.channel = channel
            val row = loaded.second
            updateSlot(tile) {
                it.copy(
                    name = channel.name,
                    logo = channel.logo,
                    number = channel.number,
                    nowTitle = row?.nowTitle,
                    nowStartSec = row?.nowStartSec ?: 0,
                    nowStopSec = row?.nowStopSec ?: 0,
                )
            }
            checkConnections(channel)
            if (visible && tile.index < slotCount) play(tile)
        }
    }

    private fun play(tile: Tile) {
        val channel = tile.channel ?: return
        if (!visible || tile.index >= slotCount) return
        tile.retryJob?.cancel()
        tile.stallJob?.cancel()
        val player = tile.player ?: buildPlayer(tile)
        applyQuality(tile.index, player)
        val s = mutable.value
        player.volume = if (!s.muted && tile.index == s.audioSlot) 1f else 0f
        val request = StreamRequest(
            mediaId = channel.id.toString(),
            url = channel.url,
            kind = tile.kindOverride ?: channel.kind,
            userAgent = channel.userAgent,
            referrer = channel.referrer,
            drmScheme = channel.drmScheme,
            drmLicenseUrl = channel.drmLicenseUrl,
            liveTargetOffsetMs = C.TIME_UNSET,
            liveMinSpeed = LIVE_MIN_SPEED,
            liveMaxSpeed = LIVE_MAX_SPEED,
        )
        val source = runCatching { sources.create(request) }.getOrNull()
        if (source == null) {
            onFailure(tile, player)
            return
        }
        updateSlot(tile) { it.copy(loading = true, error = false, attempt = tile.attempts) }
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }

    private fun buildPlayer(tile: Tile): ExoPlayer {
        val tileCount = slotCount.coerceAtLeast(1)
        val budget = device?.budget?.targetBufferBytes ?: DEFAULT_BUDGET_BYTES
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, RESUME_BUFFER_MS)
            .setTargetBufferBytes((budget / tileCount).coerceIn(MIN_TILE_BYTES, MAX_TILE_BYTES))
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        val renderers = PcmRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player = ExoPlayer.Builder(appContext, renderers)
            .setLoadControl(loadControl)
            // No audio focus: several players asking for it would pause each other.
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            )
            // Four streams with different frame rates: the display mode never changes here.
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .setName("fluide-multi")
            .build()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = onState(tile, player, playbackState)

            override fun onPlayerError(error: PlaybackException) = onError(tile, player, error)
        }
        player.addListener(listener)
        tile.player = player
        tile.listener = listener
        publishPlayers()
        return player
    }

    private fun onState(tile: Tile, player: ExoPlayer, playbackState: Int) {
        if (tile.player !== player) return
        tile.stallJob?.cancel()
        when (playbackState) {
            Player.STATE_READY -> {
                tile.attempts = 0
                tile.everReady = true
                updateSlot(tile) { it.copy(loading = false, error = false, attempt = 0) }
            }
            Player.STATE_BUFFERING -> {
                updateSlot(tile) { it.copy(loading = true) }
                // A stream that stays stuck is reconnected like a failed one.
                tile.stallJob = scope.launch {
                    delay(STALL_TIMEOUT_MS)
                    if (tile.player === player && player.playbackState == Player.STATE_BUFFERING) onFailure(tile, player)
                }
            }
            // A live stream that "ends" was cut by the server.
            Player.STATE_ENDED -> onFailure(tile, player)
            else -> Unit
        }
    }

    /**
     * A URL without extension that is not what it looked like (HLS read as MPEG-TS or the reverse): the
     * other family is tried once, right away, as the main player does. Any other error reconnects.
     */
    private fun onError(tile: Tile, player: ExoPlayer, error: PlaybackException) {
        if (tile.player !== player) return
        val channel = tile.channel
        if (channel != null && !tile.formatFallbackTried && !tile.everReady && isFormatError(error)) {
            tile.formatFallbackTried = true
            tile.kindOverride = if ((tile.kindOverride ?: channel.kind) == StreamKind.HLS) StreamKind.MPEG_TS else StreamKind.HLS
            play(tile)
            return
        }
        onFailure(tile, player)
    }

    private fun isFormatError(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED

    /** Reconnects after 1.5, 3, 6 then 12 s; after that the tile shows an error and frees its decoder. */
    private fun onFailure(tile: Tile, player: ExoPlayer) {
        if (tile.player !== player) return
        tile.stallJob?.cancel()
        tile.retryJob?.cancel()
        if (tile.attempts >= MAX_ATTEMPTS) {
            releasePlayer(tile)
            updateSlot(tile) { it.copy(loading = false, error = true, attempt = 0) }
            return
        }
        val wait = RETRY_BASE_MS shl tile.attempts
        tile.attempts++
        updateSlot(tile) { it.copy(loading = true, error = false, attempt = tile.attempts) }
        tile.retryJob = scope.launch {
            delay(wait)
            if (opened && visible && tile.index < slotCount) play(tile)
        }
    }

    private fun releasePlayer(tile: Tile) {
        tile.retryJob?.cancel()
        tile.stallJob?.cancel()
        val player = tile.player ?: return
        tile.player = null
        tile.listener?.let { player.removeListener(it) }
        tile.listener = null
        publishPlayers()
        player.release()
    }

    private fun publishPlayers() {
        playerList.value = List(slotCount) { tiles[it].player }
    }

    private fun updateSlot(tile: Tile, transform: (MultiSlot) -> MultiSlot) {
        val index = tile.index
        mutable.update { s ->
            val slot = s.slots.getOrNull(index)
            if (slot == null || slot.channelId != tile.channelId) {
                s
            } else {
                s.copy(slots = s.slots.toMutableList().also { it[index] = transform(slot) })
            }
        }
    }

    private fun replaceSlot(index: Int, slot: MultiSlot) {
        mutable.update { s ->
            if (index !in s.slots.indices) s else s.copy(slots = s.slots.toMutableList().also { it[index] = slot })
        }
    }

    private fun memorize(slot: Int, channelId: Long) {
        remembered.getOrPut(sourceId) { LongArray(MAX_TILES) }[slot] = channelId
    }

    // ------------------------------------------------------------------ data

    private fun loadAllIds() {
        val source = sourceId
        val excluded = excludedGroups()
        scope.launch {
            val ids = withContext(Dispatchers.IO) {
                runCatching { channels.channelIds(source, ChannelFilter.All, excluded) }.getOrDefault(LongArray(0))
            }
            if (opened && sourceId == source) allIds = ids
        }
    }

    private fun loadDevice() {
        if (device != null) {
            checkDecoders()
            return
        }
        scope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { (appContext as? FluideApp)?.graph?.device }.getOrNull()
            } ?: return@launch
            device = profile
            if (!opened) return@launch
            applyQuality()
            checkDecoders()
        }
    }

    /** Programmes on air, refreshed every minute while the view is visible. */
    private fun startRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                val ids = (0 until slotCount).map { tiles[it].channelId }.filter { it != 0L }
                if (ids.isEmpty()) continue
                val nowSec = System.currentTimeMillis() / 1_000
                val rows = withContext(Dispatchers.IO) {
                    runCatching { channels.rows(ids.toLongArray(), nowSec) }.getOrDefault(emptyList())
                }.associateBy { it.id }
                mutable.update { s ->
                    s.copy(
                        slots = s.slots.map { slot ->
                            val row = rows[slot.channelId] ?: return@map slot
                            slot.copy(nowTitle = row.nowTitle, nowStartSec = row.nowStartSec, nowStopSec = row.nowStopSec)
                        },
                    )
                }
            }
        }
    }

    private fun loadPicker(filter: ChannelFilter, focusList: Boolean, withGroups: Boolean) {
        val source = sourceId
        val excluded = excludedGroups()
        pickerJob?.cancel()
        pickerState.update { it.copy(selected = filter, loading = true) }
        pickerJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val groups = if (withGroups) pickerGroups(source, excluded) else null
                    groups to channels.channelIds(source, filter, excluded)
                }.getOrNull()
            }
            if (!opened || sourceId != source) return@launch
            val ids = result?.second ?: LongArray(0)
            pickerSourceId = source
            pickerIds = ids
            pickerRows.setIds(ids)
            val slot = mutable.value.pickerSlot
            val target = if (slot in 0 until slotCount) tiles[slot].channelId else 0L
            pickerState.update {
                it.copy(
                    groups = result?.first ?: it.groups,
                    selected = filter,
                    size = ids.size,
                    focusIndex = if (focusList) ids.indexOf(target).coerceAtLeast(0) else 0,
                    focusList = focusList,
                    version = it.version + 1,
                    loading = false,
                )
            }
        }
    }

    /** All channels, favorites and recent ones (when not empty), then the visible categories. */
    private fun pickerGroups(source: Long, excluded: Set<String>): List<MultiPickerGroup> {
        val out = ArrayList<MultiPickerGroup>()
        out += MultiPickerGroup(ChannelFilter.All, null, channels.channelIds(source, ChannelFilter.All, excluded).size)
        val favorites = channels.channelIds(source, ChannelFilter.Favorites, excluded).size
        if (favorites > 0) out += MultiPickerGroup(ChannelFilter.Favorites, null, favorites)
        val recent = channels.channelIds(source, ChannelFilter.Recent, excluded).size
        if (recent > 0) out += MultiPickerGroup(ChannelFilter.Recent, null, recent)
        for (group in channels.groups(source)) {
            val name = group.name
            if (name != null && name in excluded) continue
            out += MultiPickerGroup(ChannelFilter.Group(name), name, group.count)
        }
        return out
    }

    // ------------------------------------------------------------------ warnings

    /** Fewer hardware decoders than tiles: allowed (software fallback), with a warning shown once. */
    private fun checkDecoders() {
        val profile = device ?: return
        if (!opened || decoderWarningShown || profile.hardwareAvcInstances >= slotCount) return
        decoderWarningShown = true
        post(MultiViewNotice(MultiViewNotice.Kind.DECODERS, profile.hardwareAvcInstances))
    }

    /** The playlist allows fewer connections than channels on screen (once per opening). */
    private fun checkConnections(channel: PlayableChannel) {
        if (connectionWarningShown) return
        val assigned = (0 until slotCount).count { tiles[it].channelId != 0L }
        if (channel.maxConnections in 1 until assigned) {
            connectionWarningShown = true
            post(MultiViewNotice(MultiViewNotice.Kind.CONNECTIONS, channel.maxConnections))
        }
    }

    private fun post(notice: MultiViewNotice) = noticeList.update { it + notice }

    private fun normalized(layout: Int) = if (layout <= 2) 2 else MAX_TILES

    /** PCM output only: the volume of a passthrough (AC-3, DTS...) track cannot be lowered. */
    private class PcmRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink? {
            @Suppress("DEPRECATION")
            return DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }
    }

    /** The tiles do not count bytes (no watchdog of their own beyond the stall timer). */
    private object NoTransfers : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    companion object {
        const val MAX_TILES = 4

        /** Automatic reconnections of a tile before it shows an error. */
        const val MAX_ATTEMPTS = 4

        private const val RETRY_BASE_MS = 1_500L
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val REFRESH_MS = 60_000L
        private const val ZAP_SCAN_LIMIT = 64

        private const val MIN_BUFFER_MS = 4_000
        private const val MAX_BUFFER_MS = 15_000
        private const val START_BUFFER_MS = 1_000
        private const val RESUME_BUFFER_MS = 2_000
        private const val DEFAULT_BUDGET_BYTES = 40 * 1024 * 1024
        private const val MIN_TILE_BYTES = 4 * 1024 * 1024
        private const val MAX_TILE_BYTES = 24 * 1024 * 1024

        private const val LIVE_MIN_SPEED = 0.97f
        private const val LIVE_MAX_SPEED = 1.03f
    }
}

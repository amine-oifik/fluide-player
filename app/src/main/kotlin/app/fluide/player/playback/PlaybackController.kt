package app.fluide.player.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import app.fluide.core.m3u.StreamKind
import app.fluide.core.playback.PlaybackSample
import app.fluide.core.playback.PlayerPhase
import app.fluide.core.playback.RecoveryAction
import app.fluide.core.playback.RecoveryDecision
import app.fluide.core.playback.RecoveryLadder
import app.fluide.core.playback.SourceHealthTracker
import app.fluide.core.playback.StabilityProfile
import app.fluide.core.playback.StallDetector
import app.fluide.core.playback.StallSignal
import app.fluide.core.zap.PreloadLevel
import app.fluide.player.Settings
import app.fluide.player.data.ChannelRepository
import app.fluide.player.data.PlayableChannel
import app.fluide.player.device.DeviceProfile
import app.fluide.player.net.HttpStack
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The anti-freeze engine, Android side. Drives one ExoPlayer with the pure logic of the core module:
 *
 * 1. A watchdog samples the player every 500 ms (position, rendered/dropped frames, bytes received,
 *    audio underruns) and feeds StallDetector, which recognises 6 kinds of trouble 1.5-4 s after they
 *    start, i.e. usually before the buffer runs dry.
 * 2. RecoveryLadder chooses the gentlest action that can work and escalates only if trouble comes back:
 *    re-prepare -> lower quality / safer profile -> backup URL or fresh decoder -> explicit "signal lost"
 *    with automatic retries (growing backoff, never a hammering loop).
 * 3. Every server gets a stability tier learned over time (SourceHealthTracker, persisted): unstable
 *    servers are played with more buffer, further from the live edge and starting at low quality.
 * 4. While the buffer is thin, playback slows to 0.93-0.97x (pitch preserved): the buffer refills
 *    instead of running dry. That is what makes most freezes never happen at all.
 * 5. Recoveries keep the last picture on screen and a spinner only appears after 600 ms, so the
 *    short ones go unnoticed.
 *
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val http: HttpStack,
    device: DeviceProfile,
    private val settings: Settings,
    private val healthStore: SourceHealthStore,
    private val channels: ChannelRepository,
    private val pressure: PlaybackPressure,
    private val scope: CoroutineScope,
) {
    private val health: SourceHealthTracker get() = healthStore.tracker
    private val deviceProfile = device
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context).setResetOnNetworkTypeChange(true).build()
    private val factory = PlayerFactory(context, device, bandwidthMeter)
    private val bytes = ByteCounter()
    private val sources = MediaSourceBuilder(http, bytes)
    private val frameRate = FrameRateEstimator(::onFrameRate)
    private val frames = FrameCounters()
    private val underruns = AtomicInteger()
    private val detector = StallDetector()
    private val ladder = RecoveryLadder()
    private val window = Timeline.Window()

    private val uiState = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = uiState.asStateFlow()

    private val playerState = MutableStateFlow<Player?>(null)

    /** The player to show in the PlayerView. Changes only when the stability tier needs a new instance. */
    val player: StateFlow<Player?> = playerState.asStateFlow()

    private var built: PlayerFactory.Built? = null
    private var session: Session? = null
    private var sessionCounter = 0L
    private var started = false
    private var watchdog: Job? = null
    private var pending: Job? = null
    private var tunnelingBroken = false
    private var forcePcm = false
    private var lastHealthSaveMs = 0L
    private var decoderName: String? = null

    /** Distance to the live edge each server's streams ask for, learned on STABLE sessions. */
    private val liveOffsets = HashMap<String, Long>()

    val currentChannel: PlayableChannel? get() = session?.channel

    /** Position in the current video (movie, episode, recording), 0 for live channels. */
    val positionMs: Long get() = built?.player?.takeIf { session?.channel?.vod == true }?.currentPosition ?: session?.lastPositionMs ?: 0

    val durationMs: Long get() = built?.player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: session?.lastDurationMs ?: 0

    // ------------------------------------------------------------------ public API

    /** The activity is visible: (re)create the player and resume the current channel. */
    fun onStart() {
        if (started) return
        started = true
        val s = session ?: return
        s.firstFrameAtMs = 0
        s.recovering = false
        ladder.reset()
        uiState.update { it.copy(phase = PlaybackPhase.STARTING, error = null, waitingSinceMs = SystemClock.elapsedRealtime()) }
        startSession(s, keepPosition = false)
    }

    /** The activity is hidden: release the decoder (other apps and the TV home screen need it). */
    fun onStop() {
        if (!started) return
        started = false
        session?.let { s ->
            val player = built?.player
            if (s.channel.vod && player != null) {
                s.lastPositionMs = player.currentPosition
                s.startPositionMs = player.currentPosition
            }
        }
        pending?.cancel()
        watchdog?.cancel()
        watchdog = null
        healthStore.save()
        releasePlayer()
        pressure.playing = false
        pressure.bufferedMs = Long.MAX_VALUE
    }

    fun release() {
        onStop()
        session = null
    }

    /** Player options changed (tunneling, passthrough, AFR): rebuild now rather than at the next zap. */
    fun reapplySettings() {
        val s = session ?: return
        if (started) restart(s)
    }

    /** Stops playback and forgets the channel (source switched or deleted). */
    fun stop() {
        pending?.cancel()
        session = null
        built?.player?.stop()
        uiState.value = PlaybackUiState()
        pressure.playing = false
    }

    /** Plays [channel]; a video starts at [startPositionMs] (resume point). */
    fun play(channel: PlayableChannel, startPositionMs: Long = 0) {
        pending?.cancel()
        val now = SystemClock.elapsedRealtime()
        val host = hostOf(channel.url)
        val s = Session(++sessionCounter, channel, host, health.profileFor(host, System.currentTimeMillis()), now)
        s.startPositionMs = startPositionMs.coerceAtLeast(0)
        s.lastPositionMs = s.startPositionMs
        session = s
        ladder.reset()
        frameRate.reset()
        uiState.value = PlaybackUiState(channelId = channel.id, phase = PlaybackPhase.STARTING, waitingSinceMs = now)
        if (started) startSession(s, keepPosition = false)
        // Videos have no backup channel.
        if (channel.vod) return
        // Backup URLs are looked up in the background, ready before they could ever be needed.
        scope.launch {
            val alternates = withContext(Dispatchers.IO) {
                runCatching { channels.alternates(channel) }.getOrDefault(emptyList())
            }
            if (session === s) s.alternates = alternates
        }
    }

    /** Seeks the current video; ignored on live channels. */
    fun seekTo(positionMs: Long) {
        val player = built?.player ?: return
        val s = session ?: return
        if (!s.channel.vod) return
        val duration = player.duration
        val target = if (duration != C.TIME_UNSET && duration > 0) positionMs.coerceIn(0, duration - 1_000) else positionMs.coerceAtLeast(0)
        player.seekTo(target)
        s.lastPositionMs = target
        if (uiState.value.phase == PlaybackPhase.ENDED) {
            player.play()
            uiState.update { it.copy(phase = PlaybackPhase.BUFFERING) }
        }
    }

    fun seekBy(deltaMs: Long) {
        val player = built?.player ?: return
        seekTo(player.currentPosition + deltaMs)
    }

    /** "OK" on an error screen. */
    fun retryNow() {
        val s = session ?: return
        pending?.cancel()
        ladder.reset()
        s.accessDeniedRetried = false
        s.stream = s.channel
        s.alternateIndex = 0
        if (s.channel.vod) s.startPositionMs = s.lastPositionMs
        uiState.update { it.copy(phase = PlaybackPhase.STARTING, error = null, waitingSinceMs = SystemClock.elapsedRealtime()) }
        if (started) startSession(s, keepPosition = false)
    }

    fun togglePause() {
        val player = built?.player ?: return
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        if (player.playWhenReady) {
            player.pause()
            s.pausedAtMs = now
            uiState.update { it.copy(phase = PlaybackPhase.PAUSED) }
        } else {
            // A live channel paused for long resumes at the live edge, not minutes behind.
            if (player.isCurrentMediaItemLive && now - s.pausedAtMs > LIVE_RESUME_LIMIT_MS) player.seekToDefaultPosition()
            player.play()
            uiState.update {
                it.copy(phase = if (player.playbackState == Player.STATE_READY) PlaybackPhase.PLAYING else PlaybackPhase.BUFFERING)
            }
        }
    }

    /**
     * Prepares neighbouring channels for an instant zap: DNS always, HLS/DASH playlists when the
     * source allows more than one connection (a second request may count as a second viewer).
     */
    fun warm(targets: List<Pair<PlayableChannel, PreloadLevel>>) {
        if (targets.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for ((channel, level) in targets) {
                val host = runCatching { Uri.parse(channel.url).host }.getOrNull() ?: continue
                http.dns.prefetch(host)
                val playlist = channel.kind == StreamKind.HLS || channel.kind == StreamKind.DASH
                if (level >= PreloadLevel.MANIFEST && playlist && channel.maxConnections > 1) {
                    http.manifestCache.prefetch(
                        http.playerClient,
                        channel.url,
                        channel.userAgent ?: http.defaultUserAgent,
                        channel.referrer,
                    )
                }
            }
        }
    }

    fun stats(): PlaybackStats? {
        val s = session ?: return null
        val b = built ?: return null
        val player = b.player
        val mediaManagedSpeed = isLiveSpeedManagedByMedia3(player)
        return PlaybackStats(
            video = player.videoFormat?.let(::describeVideo) ?: "-",
            audio = player.audioFormat?.let(::describeAudio) ?: "-",
            decoder = decoderName,
            bufferMs = player.totalBufferedDuration,
            liveOffsetMs = if (player.isCurrentMediaItemLive) player.currentLiveOffset else C.TIME_UNSET,
            speed = if (mediaManagedSpeed) b.speedControl.lastSpeed else player.playbackParameters.speed,
            bandwidthKbps = bandwidthMeter.bitrateEstimate / 1_000,
            droppedFrames = frames.dropped,
            tier = s.profile.tier,
            ladderLevel = ladder.level,
            startupMs = s.startupMs,
            rebuffers = s.rebuffers,
            rebufferMs = s.rebufferMs,
            recoveries = s.recoveries,
            host = s.hostKey,
            tunneling = b.config.tunneling,
            backupSource = s.stream !== s.channel,
        )
    }

    // ------------------------------------------------------------------ session lifecycle

    private class Session(
        val id: Long,
        val channel: PlayableChannel,
        var hostKey: String,
        var profile: StabilityProfile,
        val startedAtMs: Long,
    ) {
        /** The stream actually played: the channel itself or one of its [alternates]. */
        var stream: PlayableChannel = channel
        var kindOverride: StreamKind? = null
        var alternates: List<PlayableChannel>? = null
        var alternateIndex = 0
        var firstFrameAtMs = 0L
        var startupMs = 0L
        var isLive = true
        var pausedAtMs = 0L
        var rebufferStartMs = 0L
        var behindWindowAtMs = 0L
        var rebuffers = 0
        var rebufferMs = 0L
        var recoveries = 0

        /** True while one of our own recoveries re-prepares: that wait is not a "rebuffer" of the server. */
        var recovering = false
        var accessDeniedRetried = false
        var formatFallbackTried = false
        var decoderReset = false
        var forcedLowest = false
        var capAppliedAtMs = 0L
        var speed = 1f
        var requestedSpeed = 1f
        var speedSupported = true

        /** Videos: where to start (resume point), and the last position seen (recoveries restart there). */
        var startPositionMs = 0L
        var lastPositionMs = 0L
        var lastDurationMs = 0L
    }

    private fun startSession(s: Session, keepPosition: Boolean) {
        pending?.cancel()
        val previous = built?.player
        val resumeAtMs = when {
            keepPosition && previous != null && !previous.isCurrentMediaItemLive && previous.duration != C.TIME_UNSET ->
                previous.currentPosition
            // A recovery that released the decoder, or the app coming back: the video continues where it was.
            s.channel.vod && keepPosition && s.lastPositionMs > 0 -> s.lastPositionMs
            s.channel.vod && s.startPositionMs > 0 -> s.startPositionMs
            else -> C.TIME_UNSET
        }

        val player = ensurePlayer(s.profile)
        val b = built ?: return
        b.speedControl.policy = s.profile.speedPolicy
        s.speed = 1f
        s.requestedSpeed = 1f
        player.playbackParameters = PlaybackParameters.DEFAULT
        // Quality limits belong to a session: start from a clean state (optionally lowest first).
        s.forcedLowest = s.profile.startAtLowestQuality
        s.capAppliedAtMs = 0
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoBitrate(Int.MAX_VALUE)
            .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            .setForceLowestBitrate(s.forcedLowest)
            .build()

        val stream = s.stream
        val request = StreamRequest(
            mediaId = stream.id.toString(),
            url = stream.url,
            kind = s.kindOverride ?: stream.kind,
            userAgent = stream.userAgent,
            referrer = stream.referrer,
            drmScheme = stream.drmScheme,
            drmLicenseUrl = stream.drmLicenseUrl,
            liveTargetOffsetMs = liveTargetOffset(s),
            liveMinSpeed = s.profile.liveMinSpeed,
            liveMaxSpeed = s.profile.liveMaxSpeed,
        )
        val source = sources.create(request)
        if (resumeAtMs != C.TIME_UNSET) player.setMediaSource(source, resumeAtMs) else player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
        // Counters are per attempt: the detector compares each sample with the previous one.
        underruns.set(0)
        detector.reset()
        startWatchdog()
    }

    /** Restarts the current stream as one of our recoveries (last picture stays on screen). */
    private fun restart(s: Session) {
        s.recovering = true
        s.recoveries++
        startSession(s, keepPosition = true)
    }

    private fun scheduleRestart(s: Session, delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            if (session === s && started) restart(s)
        }
    }

    private fun ensurePlayer(profile: StabilityProfile): ExoPlayer {
        val current = settings.current
        val config = PlayerFactory.Config(
            tier = profile.tier,
            tunneling = current.tunneling && profile.allowTunneling && deviceProfile.tunnelingSupported && !tunnelingBroken,
            pcmOnly = forcePcm || !current.audioPassthrough,
            appManagesFrameRate = current.autoFrameRate,
        )
        built?.let { if (it.config == config) return it.player }
        releasePlayer()
        val b = factory.create(config, profile)
        b.player.addListener(listener)
        b.player.addAnalyticsListener(analytics)
        b.player.setVideoFrameMetadataListener(frameRate)
        built = b
        playerState.value = b.player
        return b.player
    }

    private fun releasePlayer() {
        val b = built ?: return
        built = null
        playerState.value = null
        b.player.removeListener(listener)
        b.player.removeAnalyticsListener(analytics)
        b.player.clearVideoFrameMetadataListener(frameRate)
        b.player.release()
        frames.reset()
        decoderName = null
    }

    private fun liveTargetOffset(s: Session): Long {
        val extra = s.profile.liveExtraOffsetMs
        if (extra <= 0) return C.TIME_UNSET
        return (liveOffsets[s.hostKey] ?: DEFAULT_LIVE_OFFSET_MS) + extra
    }

    // ------------------------------------------------------------------ watchdog

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                tick()
            }
        }
    }

    private fun tick() {
        val s = session ?: return
        val player = built?.player ?: return
        val now = SystemClock.elapsedRealtime()
        val buffered = player.totalBufferedDuration
        pressure.bufferedMs = buffered
        pressure.playing = player.isPlaying
        frames.sample(player)
        if (s.channel.vod && player.playbackState != Player.STATE_IDLE) {
            s.lastPositionMs = player.currentPosition
            player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { s.lastDurationMs = it }
        }

        val sample = PlaybackSample(
            nowMs = now,
            phase = when (player.playbackState) {
                Player.STATE_BUFFERING -> PlayerPhase.BUFFERING
                Player.STATE_READY -> PlayerPhase.READY
                Player.STATE_ENDED -> PlayerPhase.ENDED
                else -> PlayerPhase.IDLE
            },
            wantsToPlay = player.playWhenReady && player.playerError == null &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition,
            bufferedMs = buffered,
            renderedFrames = frames.rendered,
            droppedFrames = frames.dropped,
            bytesLoaded = bytes.total,
            audioUnderruns = underruns.get(),
        )
        val signals = detector.onSample(sample)
        if (signals.isEmpty()) {
            if (player.isPlaying) ladder.onHealthy(now)
        } else {
            // One action per tick, decided by the most serious signal.
            val signal = SIGNAL_PRIORITY.first { it in signals }
            execute(s, ladder.onSignal(signal, now))
        }
        governSpeed(s, player, buffered)
        relaxQuality(s, player, now)
        if (now - lastHealthSaveMs > HEALTH_SAVE_INTERVAL_MS) {
            lastHealthSaveMs = now
            healthStore.save()
        }
    }

    private fun execute(s: Session, decision: RecoveryDecision) {
        when (decision.action) {
            RecoveryAction.NONE -> Unit
            RecoveryAction.REPREPARE -> {
                when (decision.reason) {
                    // A silent network usually means a dead TCP connection: never reuse it.
                    StallSignal.NETWORK_SILENT -> http.evictIdleConnections()
                    // Picture frozen while audio runs: the video decoder is wedged, get a fresh one.
                    StallSignal.VIDEO_FROZEN -> releasePlayer()
                    else -> Unit
                }
                restart(s)
            }
            RecoveryAction.DEGRADE -> degrade(s)
            RecoveryAction.FAILOVER -> failover(s)
            RecoveryAction.GIVE_UP -> giveUp(s, decision.retryInMs)
        }
    }

    /** Lower quality and/or a safer profile (more buffer, further from the live edge). */
    private fun degrade(s: Session) {
        val player = built?.player
        val wallNow = System.currentTimeMillis()
        health.recordFailure(s.hostKey, wallNow)
        val profile = health.profileFor(s.hostKey, wallNow)
        val capped = player != null && capQuality(s, player)
        when {
            profile.tier != s.profile.tier -> {
                s.profile = profile
                restart(s)
            }
            // The track selector moves to a lighter variant by itself: nothing is interrupted.
            capped -> Unit
            else -> restart(s)
        }
    }

    /** Next backup URL of the same channel, or a completely fresh start (new decoder, new connections). */
    private fun failover(s: Session) {
        val alternates = s.alternates.orEmpty()
        if (s.alternateIndex < alternates.size) {
            val next = alternates[s.alternateIndex++]
            s.stream = next
            s.kindOverride = null
            s.formatFallbackTried = false
            s.hostKey = hostOf(next.url)
            s.profile = health.profileFor(s.hostKey, System.currentTimeMillis())
            uiState.update { it.copy(notice = Notice.BACKUP_SOURCE, noticeAtMs = SystemClock.elapsedRealtime()) }
        } else {
            releasePlayer()
            http.evictIdleConnections()
        }
        restart(s)
    }

    /** Explicit "signal lost" with automatic retries; the backoff grows, the server is never hammered. */
    private fun giveUp(s: Session, retryInMs: Long) {
        built?.player?.stop()
        health.recordFailure(s.hostKey, System.currentTimeMillis())
        val retryAt = SystemClock.elapsedRealtime() + retryInMs
        uiState.update {
            it.copy(phase = PlaybackPhase.ERROR, error = PlaybackError(ErrorKind.SIGNAL_LOST, retryAtMs = retryAt), waitingSinceMs = 0)
        }
        pending?.cancel()
        pending = scope.launch {
            delay(retryInMs)
            if (session !== s || !started) return@launch
            ladder.onRetryStarted()
            s.stream = s.channel
            s.alternateIndex = 0
            s.hostKey = hostOf(s.channel.url)
            uiState.update { it.copy(phase = PlaybackPhase.STARTING, error = null, waitingSinceMs = SystemClock.elapsedRealtime()) }
            restart(s)
        }
    }

    private fun fail(s: Session, kind: ErrorKind, httpCode: Int = 0) {
        pending?.cancel()
        built?.player?.stop()
        health.recordFailure(s.hostKey, System.currentTimeMillis())
        uiState.update { it.copy(phase = PlaybackPhase.ERROR, error = PlaybackError(kind, httpCode), waitingSinceMs = 0) }
    }

    // ------------------------------------------------------------------ speed and quality

    /**
     * Anti-stall speed for streams Media3 does not regulate itself (MPEG-TS over HTTP, HLS without
     * program date-time): slightly below 1.0x while the buffer is thin, back to 1.0x with hysteresis.
     */
    private fun governSpeed(s: Session, player: ExoPlayer, bufferedMs: Long) {
        val b = built ?: return
        val desired = when {
            isLiveSpeedManagedByMedia3(player) || !s.speedSupported || b.config.tunneling -> 1f
            player.playbackState != Player.STATE_READY || !player.playWhenReady -> s.speed
            else -> s.profile.speedPolicy.nextSpeed(s.speed, bufferedMs)
        }
        if (desired != s.speed) {
            s.speed = desired
            s.requestedSpeed = desired
            player.playbackParameters = PlaybackParameters(desired)
        }
    }

    /** Same condition as ExoPlayer's internal use of LivePlaybackSpeedControl. */
    private fun isLiveSpeedManagedByMedia3(player: ExoPlayer): Boolean {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        timeline.getWindow(player.currentMediaItemIndex, window)
        return window.isLive() && window.isDynamic && window.windowStartTimeMs != C.TIME_UNSET
    }

    /** Caps video below the current variant. False when the stream has a single video quality. */
    private fun capQuality(s: Session, player: ExoPlayer): Boolean {
        val videoTracks = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.sumOf { it.length }
        if (videoTracks < 2) return false
        val format = player.videoFormat ?: return false
        val bitrate = listOf(format.bitrate, format.peakBitrate, format.averageBitrate).firstOrNull { it > 0 }
        val parameters = player.trackSelectionParameters.buildUpon()
        when {
            bitrate != null -> parameters.setMaxVideoBitrate((bitrate * 0.7).toInt())
            format.height > 0 -> parameters.setMaxVideoSize(Int.MAX_VALUE, lowerHeight(format.height))
            else -> return false
        }
        player.trackSelectionParameters = parameters.build()
        s.capAppliedAtMs = SystemClock.elapsedRealtime()
        return true
    }

    /** Gives quality back once things are calm: the start-at-lowest after a few seconds, caps after 10 minutes. */
    private fun relaxQuality(s: Session, player: ExoPlayer, now: Long) {
        if (s.forcedLowest && s.firstFrameAtMs > 0 && now - s.firstFrameAtMs > LOWEST_QUALITY_HOLD_MS) {
            s.forcedLowest = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setForceLowestBitrate(false).build()
        }
        if (s.capAppliedAtMs > 0 && now - s.capAppliedAtMs > QUALITY_CAP_HOLD_MS && ladder.level == 0) {
            s.capAppliedAtMs = 0
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .build()
        }
    }

    // ------------------------------------------------------------------ player events

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = handleState(playbackState)

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onRenderedFirstFrame() {
            session?.let { markStarted(it, SystemClock.elapsedRealtime()) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            pressure.playing = isPlaying
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            val s = session ?: return
            // The audio path refused our speed (passthrough, tunneling): stop asking for it.
            if (s.requestedSpeed != 1f && playbackParameters.speed == 1f) {
                s.speedSupported = false
                s.speed = 1f
                s.requestedSpeed = 1f
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            val s = session ?: return
            val player = built?.player ?: return
            if (s.profile.liveExtraOffsetMs != 0L || timeline.isEmpty) return
            timeline.getWindow(player.currentMediaItemIndex, window)
            val target = window.liveConfiguration?.targetOffsetMs ?: return
            if (target != C.TIME_UNSET && target > 0) liveOffsets[s.hostKey] = target
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            underruns.incrementAndGet()
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            this@PlaybackController.decoderName = decoderName
        }
    }

    private fun handleState(state: Int) {
        val s = session ?: return
        val player = built?.player ?: return
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> {
                if (s.firstFrameAtMs > 0 && !s.recovering && s.rebufferStartMs == 0L) s.rebufferStartMs = now
                uiState.update {
                    it.copy(
                        phase = if (s.firstFrameAtMs == 0L) PlaybackPhase.STARTING else PlaybackPhase.BUFFERING,
                        waitingSinceMs = if (it.waitingSinceMs == 0L) now else it.waitingSinceMs,
                    )
                }
            }
            Player.STATE_READY -> {
                if (s.rebufferStartMs > 0) {
                    val duration = now - s.rebufferStartMs
                    s.rebufferStartMs = 0
                    s.rebuffers++
                    s.rebufferMs += duration
                    health.recordRebuffer(s.hostKey, duration, System.currentTimeMillis())
                }
                s.isLive = player.isCurrentMediaItemLive || player.duration == C.TIME_UNSET
                // Radio and other audio-only streams never render a first frame.
                if (player.videoFormat == null) markStarted(s, now)
                if (s.firstFrameAtMs > 0) s.recovering = false
                uiState.update {
                    it.copy(
                        phase = if (player.playWhenReady) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED,
                        waitingSinceMs = 0,
                        error = null,
                    )
                }
            }
            Player.STATE_ENDED -> {
                // Live channels never end: the server closed the connection (many do periodically).
                // Reopen through the ladder: immediate the first time, escalating with backoff if the
                // server keeps closing at once (no reconnection loop hammering it).
                if (s.isLive && !s.channel.vod) {
                    execute(s, ladder.onSignal(StallSignal.NETWORK_SILENT, now))
                } else {
                    s.lastPositionMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: player.currentPosition
                    s.lastDurationMs = s.lastPositionMs
                    s.startPositionMs = 0
                    uiState.update { it.copy(phase = PlaybackPhase.ENDED, waitingSinceMs = 0) }
                }
            }
            else -> Unit
        }
    }

    private fun markStarted(s: Session, now: Long) {
        if (s.firstFrameAtMs == 0L) {
            s.firstFrameAtMs = now
            if (s.startupMs == 0L) s.startupMs = now - s.startedAtMs
        }
        s.recovering = false
        uiState.update {
            it.copy(
                phase = if (built?.player?.playWhenReady == false) PlaybackPhase.PAUSED else PlaybackPhase.PLAYING,
                waitingSinceMs = 0,
                error = null,
            )
        }
    }

    private fun handleError(error: PlaybackException) {
        val s = session ?: return
        val player = built?.player ?: return
        val now = SystemClock.elapsedRealtime()
        val status = IptvLoadErrorPolicy.responseCode(error)
        when {
            error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                now - s.behindWindowAtMs > BEHIND_WINDOW_REPEAT_MS -> {
                // Fell out of the live window (long stall or pause): back to the live edge, silently.
                s.behindWindowAtMs = now
                health.recordSilentRecovery(s.hostKey, System.currentTimeMillis())
                s.recovering = true
                player.seekToDefaultPosition()
                player.prepare()
            }
            hasCause<StuckPlayerException>(error) ->
                execute(s, ladder.onSignal(StallSignal.BUFFERING_TOO_LONG, now))
            isFormatError(s, error) -> if (!s.formatFallbackTried) {
                // URL without extension that is not what it looked like: try the other family once.
                s.formatFallbackTried = true
                s.kindOverride = if ((s.kindOverride ?: s.stream.kind) == StreamKind.HLS) StreamKind.MPEG_TS else StreamKind.HLS
                restart(s)
            } else {
                fail(s, ErrorKind.FORMAT)
            }
            status == 401 || status == 403 -> if (!s.accessDeniedRetried) {
                // One fresh attempt (new tokens, previous connection released), then show the error.
                s.accessDeniedRetried = true
                scheduleRestart(s, ACCESS_DENIED_RETRY_MS)
            } else {
                fail(s, ErrorKind.ACCESS_DENIED, status)
            }
            status == 404 || status == 410 ->
                if (s.alternateIndex < s.alternates.orEmpty().size) failover(s) else fail(s, ErrorKind.NOT_FOUND, status)
            error.errorCode in DECODER_ERRORS -> when {
                built?.config?.tunneling == true -> {
                    tunnelingBroken = true
                    restart(s)
                }
                !s.decoderReset -> {
                    s.decoderReset = true
                    releasePlayer()
                    restart(s)
                }
                capQuality(s, player) -> restart(s)
                else -> fail(s, ErrorKind.DECODER)
            }
            error.errorCode in AUDIO_OUTPUT_ERRORS && !forcePcm -> {
                // HDMI chain refused the bitstream: decode to PCM from now on.
                forcePcm = true
                restart(s)
            }
            error.errorCode in DRM_ERRORS -> fail(s, ErrorKind.DRM)
            // Network, timeouts, server errors, corrupted data: the ladder decides.
            else -> execute(s, ladder.onSignal(StallSignal.NETWORK_SILENT, now))
        }
    }

    private fun isFormatError(s: Session, error: PlaybackException): Boolean {
        if (s.firstFrameAtMs > 0) return false // data damaged mid-stream is not a format problem
        return hasCause<UnrecognizedInputFormatException>(error) ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
    }

    private fun onFrameRate(fps: Float) {
        uiState.update { it.copy(contentFrameRate = fps) }
    }

    // ------------------------------------------------------------------ helpers

    private inline fun <reified T : Throwable> hasCause(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 10) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase() ?: "local"

    private fun describeVideo(format: Format): String {
        val fps = if (format.frameRate > 0) format.frameRate else uiState.value.contentFrameRate
        val parts = ArrayList<String>(4)
        if (format.width > 0) parts += "${format.width}x${format.height}"
        if (fps > 0) parts += "%.3f".format(fps).trimEnd('0').trimEnd('.') + " i/s"
        (format.codecs ?: format.sampleMimeType)?.let { parts += it }
        val bitrate = listOf(format.bitrate, format.peakBitrate, format.averageBitrate).firstOrNull { it > 0 }
        if (bitrate != null) parts += "%.1f Mb/s".format(bitrate / 1_000_000f)
        return parts.joinToString(" · ")
    }

    private fun describeAudio(format: Format): String {
        val parts = ArrayList<String>(3)
        (format.codecs ?: format.sampleMimeType)?.let { parts += it }
        if (format.channelCount > 0) parts += "${format.channelCount} canaux"
        if (format.sampleRate > 0) parts += "${format.sampleRate} Hz"
        return parts.joinToString(" · ")
    }

    private fun lowerHeight(height: Int): Int = when {
        height > 1_080 -> 1_080
        height > 720 -> 720
        height > 576 -> 576
        else -> 480
    }

    /** Counts every byte received for the current player; a flat count while buffering = dead network. */
    private class ByteCounter : TransferListener {
        private val counter = AtomicLong()
        val total: Long get() = counter.get()

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            counter.addAndGet(bytesTransferred.toLong())
        }
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    /**
     * Rendered/dropped frame totals that survive decoder re-creation (each new decoder starts its own
     * DecoderCounters from zero).
     */
    private class FrameCounters {
        private var counters: DecoderCounters? = null
        private var baseRendered = 0
        private var baseDropped = 0L
        private var lastRendered = 0
        private var lastDropped = 0L
        private var seenVideo = false

        fun sample(player: ExoPlayer) {
            val current = player.videoDecoderCounters
            if (current !== counters) {
                baseRendered += lastRendered
                baseDropped += lastDropped
                lastRendered = 0
                lastDropped = 0
                counters = current
            }
            if (current != null) {
                current.ensureUpdated()
                lastRendered = current.renderedOutputBufferCount
                lastDropped = current.droppedBufferCount.toLong()
                seenVideo = true
            }
        }

        /** -1 while no video decoder ever ran (audio-only stream): disables the frozen-picture check. */
        val rendered: Int get() = if (!seenVideo) -1 else baseRendered + lastRendered
        val dropped: Long get() = baseDropped + lastDropped

        fun reset() {
            counters = null
            baseRendered = 0
            baseDropped = 0
            lastRendered = 0
            lastDropped = 0
            seenVideo = false
        }
    }

    private companion object {
        const val WATCHDOG_INTERVAL_MS = 500L
        const val HEALTH_SAVE_INTERVAL_MS = 5 * 60_000L
        const val ACCESS_DENIED_RETRY_MS = 1_500L
        const val BEHIND_WINDOW_REPEAT_MS = 30_000L
        const val LIVE_RESUME_LIMIT_MS = 30_000L
        const val LOWEST_QUALITY_HOLD_MS = 8_000L
        const val QUALITY_CAP_HOLD_MS = 10 * 60_000L

        /** Used with extra offsets before the stream's own target offset is known. */
        const val DEFAULT_LIVE_OFFSET_MS = 10_000L

        val SIGNAL_PRIORITY = listOf(
            StallSignal.VIDEO_FROZEN,
            StallSignal.POSITION_FROZEN,
            StallSignal.NETWORK_SILENT,
            StallSignal.BUFFERING_TOO_LONG,
            StallSignal.AUDIO_STARVING,
            StallSignal.FRAME_DROPPING,
        )
        val DECODER_ERRORS = 4001..4006
        val AUDIO_OUTPUT_ERRORS = 5001..5004
        val DRM_ERRORS = 6000..6008
    }
}

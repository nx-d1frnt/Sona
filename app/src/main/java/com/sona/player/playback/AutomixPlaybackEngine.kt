package com.sona.player.playback

import android.content.Context
import android.media.AudioAttributes as FrameworkAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import com.sona.player.automix.LoudnessNormalizer
import com.sona.player.automix.TransitionEnvelopes
import com.sona.player.automix.TransitionPlan
import com.sona.player.automix.TransitionPlanner
import com.sona.player.automix.MusicalCueSelector
import com.sona.player.automix.AudioAnalysisEngine
import com.sona.player.automix.AutomixEqAudioProcessor
import com.sona.player.automix.AutomixMode
import com.sona.player.automix.AutomixQueueSelector
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.AutomixTransitionTool
import com.sona.player.automix.SavedTransition
import com.sona.player.automix.ShuffleMode
import com.sona.player.automix.TrackAnalysis
import com.sona.player.data.model.Song
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dual-Deck ExoPlayer engine powering Sona's intelligent background Automix:
 * - Hybrid background PCM analysis (BPM, energy, cue-in, mix-out) with local disk cache
 * - Background transition-aware smart queue chaining
 * - Equal-Power Crossfade (cos/sin curves) + subtle pitch-preserving tempo sync (up to ±6%)
 */
class AutomixPlaybackEngine private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: AutomixPlaybackEngine? = null

        fun getInstance(context: Context): AutomixPlaybackEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutomixPlaybackEngine(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val analysisEngine = AudioAnalysisEngine(context)
    suspend fun releaseAnalysisResources() = analysisEngine.releaseVocalDetector()
    suspend fun flushPendingAnalysisWrites() = analysisEngine.flushPendingWrites()

    fun needsLibraryAnalysis(songId: Long) = analysisEngine.needsAnalysis(songId)

    private val stateThrottleJob = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private val lastStatePushMs = java.util.concurrent.atomic.AtomicLong(0L)

    fun notifyLibraryAnalysisProgress(forceImmediate: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (forceImmediate || now - lastStatePushMs.get() >= 500L) {
            lastStatePushMs.set(now)
            stateThrottleJob.getAndSet(null)?.cancel()
            val state = _playerState.value
            _playerState.value = state.copy(automix = state.automix.copy(
                currentAnalysis = state.currentSong?.let { analysisEngine.getCached(it.id) },
                nextMatchedAnalysis = playlist.getOrNull(state.currentIndex + 1)?.let { analysisEngine.getCached(it.id) },
                analyzedTracks = analysisEngine.getAllCached()
            ))
        } else if (stateThrottleJob.get()?.isActive != true) {
            val job = scope.launch {
                delay(500L)
                lastStatePushMs.set(android.os.SystemClock.elapsedRealtime())
                val state = _playerState.value
                _playerState.value = state.copy(automix = state.automix.copy(
                    currentAnalysis = state.currentSong?.let { analysisEngine.getCached(it.id) },
                    nextMatchedAnalysis = playlist.getOrNull(state.currentIndex + 1)?.let { analysisEngine.getCached(it.id) },
                    analyzedTracks = analysisEngine.getAllCached()
                ))
            }
            stateThrottleJob.set(job)
        }
    }

    suspend fun analyzeLibrarySong(song: Song, force: Boolean) {
        analysisEngine.analyzeSong(song, force)
        notifyLibraryAnalysisProgress(forceImmediate = false)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val media3AudioAttrs = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

    // Dedicated real-time Biquad DJ EQ processors for each deck
    private val eqProcessorA = AutomixEqAudioProcessor()
    private val eqProcessorB = AutomixEqAudioProcessor()

    // Two ExoPlayer decks with custom EQ AudioProcessor and unified AudioFocus management
    private val deckA: ExoPlayer = buildDeckWithEq(context, eqProcessorA)
    private val deckB: ExoPlayer = buildDeckWithEq(context, eqProcessorB)

    @OptIn(UnstableApi::class)
    private fun buildDeckWithEq(context: Context, eqProcessor: AutomixEqAudioProcessor): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(eqProcessor))
                    .setEnableFloatOutput(enableFloatOutput)
                    // Sonic changes tempo while keeping pitch at 1.0.
                    .setEnableAudioTrackPlaybackParams(false)
                    .build()
            }
        }
        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(media3AudioAttrs, false)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun getEqForDeck(deck: ExoPlayer): AutomixEqAudioProcessor {
        return if (deck === deckA) eqProcessorA else eqProcessorB
    }

    private fun applyTrackNormalization(deck: ExoPlayer, songId: Long?, immediate: Boolean = false) {
        val gain = if (_playerState.value.automix.isEnabled) LoudnessNormalizer.gain(songId?.let { analysisEngine.getCached(it) }) else 1f
        getEqForDeck(deck).setNormalizationGain(gain, if (immediate) 0L else 1500L)
    }

    private fun restoreEchoQueue() {
        val deck = echoTailDeck ?: return
        echoTailDeck = null
        if (deck.mediaItemCount < playlist.size) {
            deck.addMediaItems(buildMediaItems(playlist.drop(deck.mediaItemCount)))
        }
    }

    var activeDeck: ExoPlayer = deckA
        private set
    private var inactiveDeck: ExoPlayer = deckB
    private var transitionIncomingDeck: ExoPlayer? = null
    private var echoTailDeck: ExoPlayer? = null

    private val sessionDeckA: Player = buildSessionPlayer(deckA)
    private val sessionDeckB: Player = buildSessionPlayer(deckB)
    val sessionPlayer: Player
        get() = if (activeDeck === deckA) sessionDeckA else sessionDeckB

    private fun buildSessionPlayer(deck: Player): Player = AutomixSessionPlayer(
        deck = deck,
        onPlay = ::play,
        onPause = ::pauseInternal,
        onStop = {
            pauseInternal()
            activeDeck.stop()
        },
        onSeek = ::seekTo,
        onSeekToItem = { index, position ->
            lastManualSeekTimeMs = System.currentTimeMillis()
            cancelActiveCrossfade()
            activeDeck.seekTo(index, position)
            listeningGuard.onManualSeek()
        },
        onNext = ::skipToNext,
        onPrevious = ::skipToPrevious
    )

    private var mediaSession: MediaSession? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val playlist = mutableListOf<Song>()
    private val originalQueueOrder = mutableListOf<Song>()
    private val savedTransitions = mutableMapOf<String, SavedTransition>()
    private val userLockedNextForSongId = mutableSetOf<Long>()

    private var progressTrackerJob: Job? = null
    private var analysisJob: Job? = null
    private var crossfadeJob: Job? = null

    private var isPrebufferedForIndex: Int = -1
    private var activeTransitionTargetIndex: Int = -1
    private var duckMultiplier: Float = 1.0f
    private var hasAudioFocus: Boolean = false
    private val listeningGuard = com.sona.player.automix.TrackListeningGuard()
    private var lastManualSeekTimeMs: Long = 0L

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseInternal()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pauseInternal()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckMultiplier = 0.25f
                if (crossfadeJob?.isActive != true) {
                    activeDeck.volume = duckMultiplier
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                duckMultiplier = 1.0f
                if (crossfadeJob?.isActive != true) {
                    activeDeck.volume = 1.0f
                }
            }
        }
    }

    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            FrameworkAudioAttributes.Builder()
                .setUsage(FrameworkAudioAttributes.USAGE_MEDIA)
                .setContentType(FrameworkAudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

    init {
        attachDeckListener(deckA)
        attachDeckListener(deckB)
    }

    fun attachMediaSession(session: MediaSession) {
        this.mediaSession = session
        session.player = sessionPlayer
    }

    fun detachMediaSession(session: MediaSession) {
        if (this.mediaSession === session) {
            this.mediaSession = null
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val res = audioManager.requestAudioFocus(audioFocusRequest)
        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun attachDeckListener(deck: ExoPlayer) {
        deck.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    pauseInternal()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (deck !== activeDeck) return
                listeningGuard.tick(android.os.SystemClock.elapsedRealtime(), isPlaying,
                    deck.currentPosition, deck.playbackParameters.speed)
                _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    startTrackingProgress()
                } else if (crossfadeJob?.isActive != true) {
                    stopTrackingProgress()
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo, reason: Int) {
                if (deck === activeDeck) listeningGuard.discontinuity()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (deck !== activeDeck) {
                    // Prevent inactive/outgoing deck from auto-advancing and playing the next track during a crossfade
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        deck.pause()
                        deck.volume = 0f
                    }
                    return
                }
                if (crossfadeJob?.isActive == true) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        deck.pause()
                        deck.volume = 0f
                    }
                    return // Handled by crossfade engine
                }

                listeningGuard.enter(deck.currentPosition)
                val currentIdx = deck.currentMediaItemIndex
                val song = playlist.getOrNull(currentIdx)
                applyTrackNormalization(deck, song?.id)
                val duration = deck.duration.takeIf { it > 0 } ?: (song?.durationMs ?: 0L)

                isPrebufferedForIndex = -1

                val upcomingSong = playlist.getOrNull(currentIdx + 1)
                val upcomingAnalysis = upcomingSong?.let { analysisEngine.getCached(it.id) }

                _playerState.value = _playerState.value.copy(
                    currentSong = song,
                    currentIndex = currentIdx,
                    durationMs = duration,
                    currentPositionMs = deck.currentPosition.coerceAtLeast(0L),
                    automix = _playerState.value.automix.copy(
                        entryPositionMs = listeningGuard.entryPositionMs,
                        currentAnalysis = song?.let { analysisEngine.getCached(it.id) },
                        transitionTargetSong = null,
                        nextMatchedSong = upcomingSong,
                        nextMatchedAnalysis = upcomingAnalysis,
                        isTransitioning = false,
                        transitionProgress = 0f
                    )
                )

                if (song != null) {
                    scope.launch(Dispatchers.IO) {
                        com.sona.player.data.database.UserLibraryDatabaseHelper.getInstance(context).recordPlay(song.id)
                    }
                    triggerAnalysisAndSmartQueue(currentIdx)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (deck !== activeDeck) return
                if (playbackState == Player.STATE_ENDED && deck.playWhenReady && crossfadeJob?.isActive != true) {
                    scope.launch {
                        if (deck === activeDeck && deck.playbackState == Player.STATE_ENDED &&
                            deck.playWhenReady && crossfadeJob?.isActive != true) advanceAfterEnd()
                    }
                }
                if (playbackState == Player.STATE_READY) {
                    val duration = deck.duration.takeIf { it > 0 }
                        ?: (_playerState.value.currentSong?.durationMs ?: 0L)
                    _playerState.value = _playerState.value.copy(durationMs = duration)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("SonaAutomix", "Deck playback error active=${deck === activeDeck}", error)
                if (deck === activeDeck && deck.playWhenReady && crossfadeJob?.isActive != true) {
                    scope.launch {
                        if (deck === activeDeck && deck.playerError != null && deck.playWhenReady &&
                            crossfadeJob?.isActive != true) advanceAfterEnd()
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (deck !== activeDeck) return
                _playerState.value = _playerState.value.copy(isShuffleEnabled = shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (deck !== activeDeck) return
                _playerState.value = _playerState.value.copy(repeatMode = repeatMode)
            }
        })
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        cancelActiveCrossfade()

        originalQueueOrder.clear()
        originalQueueOrder.addAll(songs)

        playlist.clear()
        playlist.addAll(songs)
        clearSavedTransitions()
        userLockedNextForSongId.clear()

        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        if (_playerState.value.shuffleMode == ShuffleMode.RANDOM && safeIndex + 1 < playlist.size) {
            val upcomingShuffled = playlist.subList(safeIndex + 1, playlist.size).shuffled()
            for (k in upcomingShuffled.indices) {
                playlist[safeIndex + 1 + k] = upcomingShuffled[k]
            }
        }

        val mediaItems = buildMediaItems(playlist)

        val canPlay = requestAudioFocus()

        activeDeck.shuffleModeEnabled = false
        inactiveDeck.shuffleModeEnabled = false
        activeDeck.volume = 1.0f * duckMultiplier
        activeDeck.playbackParameters = PlaybackParameters.DEFAULT
        applyTrackNormalization(activeDeck, playlist[safeIndex].id, immediate = true)
        activeDeck.setMediaItems(mediaItems, safeIndex, 0L)
        activeDeck.prepare()
        activeDeck.playWhenReady = canPlay

        val initialSong = playlist[safeIndex]
        val cachedAnalysis = analysisEngine.getCached(initialSong.id)
        rebuildSavedTransitions(safeIndex)

        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            currentIndex = safeIndex,
            currentSong = initialSong,
            durationMs = initialSong.durationMs,
            currentPositionMs = 0L,
            automix = _playerState.value.automix.copy(
                currentAnalysis = cachedAnalysis,
                transitionTargetSong = null,
                transitionFromSongId = null,
                nextMatchedSong = playlist.getOrNull(safeIndex + 1),
                nextMatchedAnalysis = playlist.getOrNull(safeIndex + 1)?.let { analysisEngine.getCached(it.id) },
                isTransitioning = false,
                transitionProgress = 0f,
                activeTempoRatio = 1.0f,
                analyzedTracks = analysisEngine.getAllCached(),
                savedTransitions = savedTransitions.toMap()
            )
        )

        triggerAnalysisAndSmartQueue(safeIndex)
        scope.launch(Dispatchers.IO) {
            com.sona.player.data.database.UserLibraryDatabaseHelper.getInstance(context).recordPlay(initialSong.id)
        }
    }

    fun togglePlayPause() {
        if (activeDeck.isPlaying) {
            pauseInternal()
        } else {
            play()
        }
    }

    private fun play() {
        if (requestAudioFocus()) {
            if (activeDeck.playbackState == Player.STATE_IDLE) activeDeck.prepare()
            activeDeck.play()
        }
    }

    private fun pauseInternal() {
        if (crossfadeJob?.isActive == true) {
            cancelActiveCrossfade()
        }
        activeDeck.pause()
        inactiveDeck.pause()
    }

    fun seekTo(positionMs: Long) {
        lastManualSeekTimeMs = System.currentTimeMillis()
        if (crossfadeJob?.isActive == true) {
            cancelActiveCrossfade()
        }
        activeDeck.seekTo(positionMs)
        listeningGuard.onManualSeek()
        _playerState.value = _playerState.value.copy(
            currentPositionMs = activeDeck.currentPosition.coerceAtLeast(0L)
        )
    }

    fun skipToNext() {
        if (crossfadeJob?.isActive == true) {
            // If user taps Next during an Automix transition, jump immediately to the incoming track
            completeCrossfadeImmediately()
            return
        }
        val curIdx = _playerState.value.currentIndex
        val nextIdx = curIdx + 1
        val nextSong = playlist.getOrNull(nextIdx)
        if (_playerState.value.automix.isEnabled && activeDeck.isPlaying && nextSong != null) {
            // Preserve the DJ Automix transition even when manually skipping to the next track!
            startCrossfadeTransition(nextIdx, nextSong, customDurationMs = 5500L)
            return
        }
        if (activeDeck.hasNextMediaItem()) {
            activeDeck.seekToNextMediaItem()
        }
    }

    /**
     * Jumps or smoothly DJ-transitions to a specific track index from the Queue Menu.
     */
    fun skipToQueueIndex(targetIndex: Int) {
        if (targetIndex !in playlist.indices) return
        val curIdx = _playerState.value.currentIndex
        if (targetIndex == curIdx) return

        if (crossfadeJob?.isActive == true) {
            cancelActiveCrossfade()
        }

        val targetSong = playlist[targetIndex]
        if (_playerState.value.automix.isEnabled && activeDeck.isPlaying) {
            // Perform a smooth 5.5s DJ EQ Automix transition directly into the selected queue track
            startCrossfadeTransition(targetIndex, targetSong, customDurationMs = 5500L)
        } else {
            activeDeck.seekToDefaultPosition(targetIndex)
            play()
        }
    }

    /**
     * Moves a track within the queue (e.g. Up / Down / Play Next) while preserving and recalculating DJ transitions.
     */
    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in playlist.indices || toIndex !in playlist.indices || fromIndex == toIndex) return
        val curIdx = _playerState.value.currentIndex
        if (fromIndex == curIdx || toIndex <= curIdx) return // Keep played history & active item stable
        if (crossfadeJob?.isActive == true) return

        isPrebufferedForIndex = -1
        val movedSong = playlist.removeAt(fromIndex)
        playlist.add(toIndex, movedSong)
        if (activeDeck.mediaItemCount > fromIndex && activeDeck.mediaItemCount > toIndex) {
            activeDeck.moveMediaItem(fromIndex, toIndex)
        }

        // Lock the transition for the current song if the user explicitly placed a song at curIdx + 1
        playlist.getOrNull(curIdx)?.let { curSong ->
            if (toIndex == curIdx + 1 || fromIndex == curIdx + 1) {
                userLockedNextForSongId.add(curSong.id)
                isPrebufferedForIndex = -1
            }
        }
        playlist.getOrNull(toIndex - 1)?.let { prevSong ->
            userLockedNextForSongId.add(prevSong.id)
        }

        rebuildSavedTransitions(curIdx)
        val nextSong = playlist.getOrNull(curIdx + 1)
        val nextAnalysis = nextSong?.let { analysisEngine.getCached(it.id) }
        val topTransition = if (nextSong != null) {
            savedTransitions[SavedTransition.key(playlist[curIdx].id, nextSong.id)]
        } else null

        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            automix = _playerState.value.automix.copy(
                nextMatchedSong = nextSong,
                nextMatchedAnalysis = nextAnalysis,
                matchScorePercent = topTransition?.matchScorePercent ?: _playerState.value.automix.matchScorePercent,
                savedTransitions = savedTransitions.toMap()
            )
        )
    }

    /**
     * Removes a track from the upcoming queue and updates adjacent DJ transition links.
     */
    fun removeFromQueue(index: Int) {
        val curIdx = _playerState.value.currentIndex
        if (index !in playlist.indices || index == curIdx) return
        if (crossfadeJob?.isActive == true) return

        isPrebufferedForIndex = -1
        playlist.removeAt(index)
        if (activeDeck.mediaItemCount > index) {
            activeDeck.removeMediaItem(index)
        }

        val newCurIdx = if (index < curIdx) curIdx - 1 else curIdx
        if (index == curIdx + 1) {
            isPrebufferedForIndex = -1
        }

        rebuildSavedTransitions(newCurIdx)
        val nextSong = playlist.getOrNull(newCurIdx + 1)
        val nextAnalysis = nextSong?.let { analysisEngine.getCached(it.id) }

        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            currentIndex = newCurIdx,
            automix = _playerState.value.automix.copy(
                nextMatchedSong = nextSong,
                nextMatchedAnalysis = nextAnalysis,
                savedTransitions = savedTransitions.toMap()
            )
        )
    }

    /**
     * Inserts a track immediately after the currently playing track in the queue.
     */
    fun playNext(song: Song) {
        if (playlist.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        val curIdx = _playerState.value.currentIndex
        val insertIndex = (curIdx + 1).coerceAtMost(playlist.size)
        playlist.add(insertIndex, song)
        originalQueueOrder.add(song)
        if (activeDeck.mediaItemCount >= insertIndex) {
            val mediaItem = buildMediaItems(listOf(song)).first()
            activeDeck.addMediaItem(insertIndex, mediaItem)
        }
        playlist.getOrNull(curIdx)?.let { curSong ->
            userLockedNextForSongId.add(curSong.id)
        }
        isPrebufferedForIndex = -1
        rebuildSavedTransitions(curIdx)
        val nextSong = playlist.getOrNull(curIdx + 1)
        val nextAnalysis = nextSong?.let { analysisEngine.getCached(it.id) }
        val topTransition = if (nextSong != null && curIdx in playlist.indices) {
            savedTransitions[SavedTransition.key(playlist[curIdx].id, nextSong.id)]
        } else null

        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            automix = _playerState.value.automix.copy(
                nextMatchedSong = nextSong,
                nextMatchedAnalysis = nextAnalysis,
                matchScorePercent = topTransition?.matchScorePercent ?: _playerState.value.automix.matchScorePercent,
                savedTransitions = savedTransitions.toMap()
            )
        )
    }

    /**
     * Appends a track to the end of the active playback queue.
     */
    fun addToQueue(song: Song) {
        if (playlist.isEmpty()) {
            playQueue(listOf(song), 0)
            return
        }
        playlist.add(song)
        originalQueueOrder.add(song)
        val mediaItem = buildMediaItems(listOf(song)).first()
        activeDeck.addMediaItem(mediaItem)

        val curIdx = _playerState.value.currentIndex
        rebuildSavedTransitions(curIdx)
        val nextSong = playlist.getOrNull(curIdx + 1)
        val nextAnalysis = nextSong?.let { analysisEngine.getCached(it.id) }

        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            automix = _playerState.value.automix.copy(
                nextMatchedSong = nextSong,
                nextMatchedAnalysis = nextAnalysis,
                savedTransitions = savedTransitions.toMap()
            )
        )
    }

    fun skipToPrevious() {
        if (crossfadeJob?.isActive == true) {
            cancelActiveCrossfade()
        }
        if (activeDeck.currentPosition > 4000L) {
            activeDeck.seekTo(0L)
        } else if (activeDeck.hasPreviousMediaItem()) {
            activeDeck.seekToPreviousMediaItem()
        } else {
            activeDeck.seekTo(0L)
        }
    }

    /**
     * Cycles Queue Shuffle Mode:
     * OFF (Sequential) -> RANDOM (Random Shuffle) -> SMART (Harmonic BPM & Energy DJ Shuffle) -> OFF
     */
    fun toggleShuffle() {
        val currentShuffle = _playerState.value.shuffleMode
        val nextShuffle = when (currentShuffle) {
            ShuffleMode.OFF -> ShuffleMode.RANDOM
            ShuffleMode.RANDOM -> ShuffleMode.SMART
            ShuffleMode.SMART -> ShuffleMode.OFF
        }

        activeDeck.shuffleModeEnabled = false
        inactiveDeck.shuffleModeEnabled = false

        val curIdx = _playerState.value.currentIndex
        if (crossfadeJob?.isActive != true && curIdx in playlist.indices && curIdx + 1 < playlist.size) {
            when (nextShuffle) {
                ShuffleMode.RANDOM -> {
                    userLockedNextForSongId.clear()
                    val upcomingShuffled = playlist.subList(curIdx + 1, playlist.size).shuffled()
                    for (k in upcomingShuffled.indices) {
                        playlist[curIdx + 1 + k] = upcomingShuffled[k]
                    }
                    syncUpcomingDeckMediaItems(curIdx)
                }
                ShuffleMode.OFF -> {
                    userLockedNextForSongId.clear()
                    val alreadyPlayedOrCurrentIds = playlist.subList(0, curIdx + 1).map { it.id }.toHashSet()
                    val restoredUpcoming = originalQueueOrder.filter { it.id !in alreadyPlayedOrCurrentIds }
                    if (restoredUpcoming.size == playlist.size - (curIdx + 1)) {
                        for (k in restoredUpcoming.indices) {
                            playlist[curIdx + 1 + k] = restoredUpcoming[k]
                        }
                        syncUpcomingDeckMediaItems(curIdx)
                    }
                }
                ShuffleMode.SMART -> {
                    // Smart DJ Shuffle will build a transition-aware chain through the upcoming queue
                    userLockedNextForSongId.remove(playlist[curIdx].id)
                }
            }
        }

        isPrebufferedForIndex = -1
        rebuildSavedTransitions(curIdx.coerceAtLeast(0))
        val nextSong = playlist.getOrNull(curIdx + 1)
        val nextAnalysis = nextSong?.let { analysisEngine.getCached(it.id) }

        _playerState.value = _playerState.value.copy(
            isShuffleEnabled = (nextShuffle != ShuffleMode.OFF),
            shuffleMode = nextShuffle,
            currentQueue = playlist.toList(),
            automix = _playerState.value.automix.copy(
                nextMatchedSong = nextSong,
                nextMatchedAnalysis = nextAnalysis,
                savedTransitions = savedTransitions.toMap()
            )
        )

        if (curIdx in playlist.indices) {
            triggerAnalysisAndSmartQueue(curIdx)
        }
    }

    private fun syncUpcomingDeckMediaItems(curIdx: Int) {
        if (curIdx + 1 >= playlist.size) return
        val upcomingItems = buildMediaItems(playlist.subList(curIdx + 1, playlist.size))
        if (activeDeck.mediaItemCount > curIdx + 1) {
            activeDeck.replaceMediaItems(curIdx + 1, activeDeck.mediaItemCount, upcomingItems)
        }
    }

    fun toggleRepeatMode() {
        val nextMode = when (activeDeck.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        activeDeck.repeatMode = nextMode
        inactiveDeck.repeatMode = nextMode
    }

    /**
     * Cycles Automix mode:
     * AUTO_DJ (Auto Mid/Outro) -> CLASSIC_OUTRO (Outro Mix) -> QUICK_MID_MIX (Mid-Track DJ Set) -> OFF -> AUTO_DJ
     */
    fun toggleAutomix() {
        val currentAutomix = _playerState.value.automix
        val nextMode = when (currentAutomix.mode) {
            AutomixMode.AUTO_DJ -> AutomixMode.CLASSIC_OUTRO
            AutomixMode.CLASSIC_OUTRO -> AutomixMode.QUICK_MID_MIX
            AutomixMode.QUICK_MID_MIX -> AutomixMode.OFF
            AutomixMode.OFF -> AutomixMode.AUTO_DJ
        }
        val newEnabled = (nextMode != AutomixMode.OFF)
        if (!newEnabled && crossfadeJob?.isActive == true) {
            cancelActiveCrossfade()
        }
        isPrebufferedForIndex = -1
        val curIdx = _playerState.value.currentIndex.coerceAtLeast(0)
        _playerState.value = _playerState.value.copy(
            automix = _playerState.value.automix.copy(
                isEnabled = newEnabled,
                mode = nextMode
            )
        )
        rebuildSavedTransitions(curIdx)
        _playerState.value = _playerState.value.copy(
            automix = _playerState.value.automix.copy(
                savedTransitions = savedTransitions.toMap()
            )
        )
        if (newEnabled && curIdx in playlist.indices) {
            triggerAnalysisAndSmartQueue(curIdx)
        }
    }

    /** Analyze a stable snapshot so reordering cannot skip candidates. SMART continues through the queue. */
    private fun triggerAnalysisAndSmartQueue(currentIndex: Int) {
        analysisJob?.cancel()
        val currentSong = playlist.getOrNull(currentIndex) ?: return
        val smart = _playerState.value.shuffleMode == ShuffleMode.SMART
        val candidates = playlist.drop(currentIndex + 1).let { if (smart) it else it.take(12) }
        analysisJob = scope.launch {
            fun stillCurrent() = _playerState.value.currentIndex == currentIndex &&
                playlist.getOrNull(currentIndex)?.id == currentSong.id
            suspend fun awaitPlayback() {
                while (crossfadeJob?.isActive == true && isActive) delay(250)
            }
            try {
                awaitPlayback()
                if (!stillCurrent()) return@launch
                _playerState.value = _playerState.value.copy(
                    automix = _playerState.value.automix.copy(isAnalyzing = true))
                val currentAnalysis = analysisEngine.analyzeSong(currentSong)
                if (!stillCurrent()) return@launch
                _playerState.value = _playerState.value.copy(
                    automix = _playerState.value.automix.copy(currentAnalysis = currentAnalysis))
                refreshAnalyzedQueue(currentIndex)
                for (candidate in candidates) {
                    if (!isActive || !stillCurrent()) return@launch
                    if (!analysisEngine.needsAnalysis(candidate.id)) continue
                    awaitPlayback()
                    if (playlist.none { it.id == candidate.id }) continue
                    analysisEngine.analyzeSong(candidate)
                    if (!stillCurrent()) return@launch
                    refreshAnalyzedQueue(currentIndex)
                    delay(200)
                }
            } finally {
                if (stillCurrent() && analysisJob == coroutineContext[Job]) _playerState.value = _playerState.value.copy(
                    automix = _playerState.value.automix.copy(isAnalyzing = false))
            }
        }
    }

    private suspend fun refreshAnalyzedQueue(currentIndex: Int) {
        if (_playerState.value.shuffleMode == ShuffleMode.SMART && crossfadeJob?.isActive != true) {
            applySmartQueueChain(currentIndex)
        }
        rebuildSavedTransitions(currentIndex)
        val current = playlist.getOrNull(currentIndex)
        val next = playlist.getOrNull(currentIndex + 1)
        val transition = if (current != null && next != null)
            savedTransitions[SavedTransition.key(current.id, next.id)] else null
        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            automix = _playerState.value.automix.copy(
                nextMatchedSong = next,
                nextMatchedAnalysis = next?.let { analysisEngine.getCached(it.id) },
                matchScorePercent = transition?.matchScorePercent ?: 0,
                analyzedTracks = analysisEngine.getAllCached(),
                savedTransitions = savedTransitions.toMap()
            )
        )
    }

    /** Inputs a saved transition was planned from; analyses are immutable, so identity means "unchanged". */
    private data class TransitionSource(val mode: AutomixMode, val from: TrackAnalysis, val to: TrackAnalysis,
                                        val entryPositionMs: Long = 0L)
    private class PendingTransition(val index: Int, val key: String, val fromId: Long, val toId: Long,
                                    val source: TransitionSource, val completed: Boolean)
    private val transitionSources = mutableMapOf<String, TransitionSource>()
    private var transitionRebuildJob: Job? = null

    /**
     * Refreshes queue transition previews. Only pairs whose analyses or mode changed are re-planned,
     * and planning (vocal/beat cue searches) runs on [Dispatchers.Default] so the main thread never
     * blocks on a long queue. Results are merged back on the main thread nearest-first, in chunks.
     */
    private fun rebuildSavedTransitions(currentIndex: Int) {
        val activeMode = _playerState.value.automix.mode
        val pending = ArrayList<PendingTransition>()
        for (i in 0 until (playlist.size - 1)) {
            val songA = playlist[i]
            val songB = playlist[i + 1]
            val analysisA = analysisEngine.getCached(songA.id) ?: continue
            val analysisB = analysisEngine.getCached(songB.id) ?: continue
            val key = SavedTransition.key(songA.id, songB.id)
            val existing = savedTransitions[key]
            val completed = (i < currentIndex) || (existing?.isCompleted == true)
            if (existing != null && completed) {
                // Preserve the actual plan and its rating once this pair has played.
                if (!existing.isCompleted) savedTransitions[key] = existing.copy(isCompleted = true)
                continue
            }
            val source = TransitionSource(activeMode, analysisA, analysisB,
                if (i == currentIndex) listeningGuard.entryPositionMs else 0L)
            if (existing != null && transitionSources[key]?.matches(source) == true) {
                if (existing.isCompleted != completed) savedTransitions[key] = existing.copy(isCompleted = completed)
            } else {
                pending += PendingTransition(i, key, songA.id, songB.id, source, completed)
            }
        }
        if (pending.isEmpty()) return
        // Upcoming pairs first (the next transition matters most), then history.
        pending.sortBy { if (it.index >= currentIndex) it.index - currentIndex else playlist.size + (currentIndex - it.index) }

        transitionRebuildJob?.cancel()
        transitionRebuildJob = scope.launch {
            for (chunk in pending.chunked(6)) {
                val built = withContext(Dispatchers.Default) {
                    chunk.map { p ->
                        p to AutomixQueueSelector.buildSavedTransition(
                            fromSongId = p.fromId,
                            toSongId = p.toId,
                            fromAnalysis = p.source.from,
                            toAnalysis = p.source.to,
                            mode = p.source.mode,
                            isCompleted = p.completed,
                            entryPositionMs = p.source.entryPositionMs
                        )
                    }
                }
                for ((p, transition) in built) {
                    // A live crossfade (or a newer rebuild) may already have filled this pair.
                    if (transitionSources[p.key]?.matches(p.source) == true) continue
                    val completed = p.completed || savedTransitions[p.key]?.isCompleted == true
                    savedTransitions[p.key] = transition.copy(isCompleted = completed)
                    transitionSources[p.key] = p.source
                }
                publishSavedTransitions()
            }
        }
    }

    private fun TransitionSource.matches(other: TransitionSource) =
        mode == other.mode && from === other.from && to === other.to && entryPositionMs == other.entryPositionMs

    private fun publishSavedTransitions() {
        val state = _playerState.value
        val current = playlist.getOrNull(state.currentIndex)
        val next = playlist.getOrNull(state.currentIndex + 1)
        val nextScore = if (current != null && next != null && !state.automix.isTransitioning)
            savedTransitions[SavedTransition.key(current.id, next.id)]?.matchScorePercent else null
        _playerState.value = state.copy(automix = state.automix.copy(
            matchScorePercent = nextScore ?: state.automix.matchScorePercent,
            savedTransitions = savedTransitions.toMap()
        ))
    }

    private fun clearSavedTransitions() {
        transitionRebuildJob?.cancel()
        transitionRebuildJob = null
        savedTransitions.clear()
        transitionSources.clear()
    }

    private suspend fun applySmartQueueChain(currentIndex: Int) {
        val snapshot = playlist.toList()
        val analyses = analysisEngine.getAllCached().toMap()
        val locks = userLockedNextForSongId.toSet()
        val mode = _playerState.value.automix.mode
        val entry = listeningGuard.entryPositionMs
        val ordered = withContext(Dispatchers.Default) {
            AutomixQueueSelector.buildSmartQueue(snapshot, currentIndex, {
                coroutineContext.ensureActive()
                analyses[it]
            }, locks, mode, entry)
        }
        // Planning yields: discard results if playback or a manual queue edit changed the inputs.
        if (_playerState.value.shuffleMode != ShuffleMode.SMART ||
            _playerState.value.currentIndex != currentIndex || crossfadeJob?.isActive == true ||
            playlist != snapshot || userLockedNextForSongId != locks ||
            _playerState.value.automix.mode != mode || listeningGuard.entryPositionMs != entry) return
        if (ordered != snapshot) {
            playlist.clear()
            playlist.addAll(ordered)
            syncUpcomingDeckMediaItems(currentIndex)
            isPrebufferedForIndex = -1
        }
    }

    private fun startTrackingProgress() {
        stopTrackingProgress()
        progressTrackerJob = scope.launch {
            while (isActive) {
                if (crossfadeJob?.isActive != true) {
                    applyTrackNormalization(activeDeck, _playerState.value.currentSong?.id)
                }
                listeningGuard.tick(android.os.SystemClock.elapsedRealtime(), activeDeck.isPlaying,
                    activeDeck.currentPosition, activeDeck.playbackParameters.speed)
                val pos = activeDeck.currentPosition.coerceAtLeast(0L)
                val dur = if (activeDeck.duration > 0) {
                    activeDeck.duration
                } else {
                    _playerState.value.durationMs
                }

                _playerState.value = _playerState.value.copy(
                    currentPositionMs = pos,
                    automix = _playerState.value.automix.copy(playedMs = listeningGuard.playedMs, manualSeek = listeningGuard.manualSeek),
                    durationMs = dur
                )

                checkAutomixTrigger(pos, dur)
                delay(200)
            }
        }
    }

    private fun stopTrackingProgress() {
        progressTrackerJob?.cancel()
        progressTrackerJob = null
    }

    private fun checkAutomixTrigger(positionMs: Long, durationMs: Long) {
        val state = _playerState.value
        val automix = state.automix
        if (!automix.isEnabled || !state.isPlaying || crossfadeJob?.isActive == true) return
        if (activeDeck.repeatMode == Player.REPEAT_MODE_ONE) return

        // Give the user a 2.5s grace period after a manual seek before triggering Automix
        if (System.currentTimeMillis() - lastManualSeekTimeMs < 2500L) return

        val nextIndex = state.currentIndex + 1
        val nextSong = playlist.getOrNull(nextIndex) ?: return

        val curAnalysis = automix.currentAnalysis?.takeIf { it.songId == state.currentSong?.id }
            ?: state.currentSong?.let { analysisEngine.getCached(it.id) }
        val nextAnalysis = analysisEngine.getCached(nextSong.id)
            ?: automix.nextMatchedAnalysis?.takeIf { it.songId == nextSong.id }
        val plan = if (curAnalysis != null && nextAnalysis != null) TransitionPlanner.plan(curAnalysis, nextAnalysis, automix.mode, entryPositionMs = listeningGuard.entryPositionMs) else null
        val resolvedMode = curAnalysis?.resolveEffectiveMode(automix.mode, nextAnalysis) ?: automix.mode
        val mixOutMs = plan?.mixOutMs ?: curAnalysis?.effectiveMixOutMs(automix.mode, nextAnalysis)
            ?: if (resolvedMode == AutomixMode.QUICK_MID_MIX) {
                (durationMs * 0.56f).toLong().coerceAtLeast(15_000L)
            } else {
                (durationMs - 14000L).coerceAtLeast((durationMs * 0.72f).toLong())
            }

        // Do not block a short/seeked track's outro indefinitely, while preserving
        // the 45-second requirement for automatic middle exits.
        if (!listeningGuard.allowsAutomaticTransition(durationMs - positionMs)) return

        if (durationMs < 20_000L) return // Skip Automix for very short audio clips

        // 1. Pre-buffer the inactive deck 7 seconds before the Automix Zone starts
        if (positionMs >= (mixOutMs - 7000L)) {
            prebufferInactiveDeck(nextIndex)
        }

        // 2. Trigger the DJ Automix (High-Pass Blend -> Bass Swap -> Low-Pass Filter Sweep) when entering the Automix Zone
        if (positionMs >= mixOutMs - 300L && positionMs < durationMs - 2500L) {
            startCrossfadeTransition(nextIndex, nextSong)
        }
    }

    private var prebufferedSongId: Long? = null
    private var prebufferedPlan: TransitionPlan? = null

    private fun prebufferInactiveDeck(nextIndex: Int) {
        if (nextIndex !in playlist.indices) return
        val nextSong = playlist[nextIndex]
        val nextAnalysis = analysisEngine.getCached(nextSong.id)
        val curAnalysis = _playerState.value.automix.currentAnalysis
        val curEnergy = curAnalysis?.energy ?: 0.6f
        val mode = _playerState.value.automix.mode
        val plan = if (curAnalysis != null && nextAnalysis != null) TransitionPlanner.plan(curAnalysis, nextAnalysis, mode, entryPositionMs = listeningGuard.entryPositionMs) else null
        if (isPrebufferedForIndex == nextIndex && prebufferedSongId == nextSong.id && prebufferedPlan == plan) return
        val cueInMs = plan?.cueInMs ?: nextAnalysis?.effectiveCueInMs(mode, curEnergy, curAnalysis) ?: 0L

        val items = buildMediaItems(playlist)
        inactiveDeck.pause()
        inactiveDeck.volume = 0f
        inactiveDeck.playbackParameters = PlaybackParameters.DEFAULT
        getEqForDeck(inactiveDeck).resetEq()
        getEqForDeck(inactiveDeck).setNormalizationGain(plan?.incomingGain ?: LoudnessNormalizer.gain(nextAnalysis), 0L)
        inactiveDeck.setMediaItems(items, nextIndex, cueInMs)
        inactiveDeck.prepare()
        isPrebufferedForIndex = nextIndex
        prebufferedSongId = nextSong.id
        prebufferedPlan = plan
    }

    private fun startCrossfadeTransition(
        nextIndex: Int,
        nextSong: Song,
        customDurationMs: Long? = null
    ) {
        if (crossfadeJob?.isActive == true) return

        prebufferInactiveDeck(nextIndex)

        val outgoingDeck = activeDeck
        val incomingDeck = inactiveDeck
        val outgoingEq = getEqForDeck(outgoingDeck)
        val incomingEq = getEqForDeck(incomingDeck)

        val outgoingSong = _playerState.value.currentSong
        val curAnalysis = _playerState.value.automix.currentAnalysis?.takeIf { it.songId == outgoingSong?.id }
        val nextAnalysis = analysisEngine.getCached(nextSong.id)
            ?: _playerState.value.automix.nextMatchedAnalysis?.takeIf { it.songId == nextSong.id }
        // Release the background decoder and stop inference before running two playback decks.
        analysisJob?.cancel()
        analysisJob = null

        // Ensure incomingDeck is positioned at the smart entry point (in case analysis finished after prebuffer)
        val activeMode = _playerState.value.automix.mode
        val curEnergy = curAnalysis?.energy ?: 0.6f
        val plan = if (curAnalysis != null && nextAnalysis != null) {
            TransitionPlanner.plan(curAnalysis, nextAnalysis, activeMode, customDurationMs,
                if (customDurationMs != null) outgoingDeck.currentPosition else null, listeningGuard.entryPositionMs)
        } else TransitionPlan(
            tool = AutomixTransitionTool.SILKY_WARM_ISOLATOR,
            mixOutMs = outgoingDeck.currentPosition,
            cueInMs = nextAnalysis?.effectiveCueInMs(activeMode, curEnergy, curAnalysis) ?: 0L,
            durationMs = customDurationMs ?: 10_500L,
            outgoingGain = LoudnessNormalizer.gain(curAnalysis), incomingGain = LoudnessNormalizer.gain(nextAnalysis)
        )
        val targetCueInMs = plan.cueInMs
        if (kotlin.math.abs(incomingDeck.currentPosition - targetCueInMs) > 80L) {
            incomingDeck.seekTo(nextIndex, targetCueInMs)
        }
        val crossfadeDurationMs = plan.durationMs
        activeTransitionTargetIndex = nextIndex
        transitionIncomingDeck = incomingDeck
        android.util.Log.i("SonaAutomix", "transition from=${outgoingSong?.id} to=${nextSong.id} mode=$activeMode out=${outgoingDeck.currentPosition} cue=${plan.cueInMs} tool=${plan.tool} heard=${listeningGuard.playedMs} manual=${customDurationMs != null}")
        val selectedTool = plan.tool
        val outTempoRatio = plan.outgoingTempoRatio
        val inTempoRatio = plan.incomingTempoRatio

        // Lock this transition pair in savedTransitions so it is preserved
        if (outgoingSong != null && curAnalysis != null && nextAnalysis != null) {
            val key = SavedTransition.key(outgoingSong.id, nextSong.id)
            savedTransitions[key] = AutomixQueueSelector.buildSavedTransition(
                fromSongId = outgoingSong.id,
                toSongId = nextSong.id,
                fromAnalysis = curAnalysis,
                toAnalysis = nextAnalysis,
                mode = activeMode,
                isCompleted = false
            ).copy(
                transitionTool = selectedTool,
                transitionStyle = selectedTool.displayName,
                overlapDurationMs = plan.durationMs,
                cueInMs = plan.cueInMs,
                quality = plan.quality,
                tempoSyncPercent = ((plan.incomingTempoRatio - 1f) * 100f).roundToInt()
            )
            // Mark as up to date so a background rebuild doesn't replace the live plan's details.
            transitionSources[key] = TransitionSource(activeMode, curAnalysis, nextAnalysis, listeningGuard.entryPositionMs)
            userLockedNextForSongId.add(outgoingSong.id)
        }

        // Ensure transitionTargetSong & nextMatchedSong are explicitly set before transition starts
        _playerState.value = _playerState.value.copy(
            automix = _playerState.value.automix.copy(
                isTransitioning = true,
                isAnalyzing = false,
                transitionProgress = 0f,
                transitionTargetSong = nextSong,
                transitionFromSongId = outgoingSong?.id,
                activeTransitionTool = selectedTool,
                nextMatchedSong = nextSong,
                nextMatchedAnalysis = nextAnalysis,
                activeTempoRatio = inTempoRatio,
                analyzedTracks = analysisEngine.getAllCached(),
                savedTransitions = savedTransitions.toMap()
            )
        )

        // Prevent native auto-advance from racing the deck swap. Let the outgoing
        // item actually end, rather than pausing it before an unready incoming deck.
        echoTailDeck = outgoingDeck
        val firstUpcoming = outgoingDeck.currentMediaItemIndex + 1
        if (firstUpcoming < outgoingDeck.mediaItemCount) outgoingDeck.removeMediaItems(firstUpcoming, outgoingDeck.mediaItemCount)

        crossfadeJob = scope.launch {
            // Preparation/seek can still be pending, especially after a manual Next.
            // Keep the outgoing deck audible until the incoming decoder is ready.
            val ready = withTimeoutOrNull(8_000L) {
                while (incomingDeck.playbackState != Player.STATE_READY) {
                    if (incomingDeck.playerError != null || incomingDeck.playbackState == Player.STATE_ENDED) {
                        return@withTimeoutOrNull false
                    }
                    delay(25L)
                }
                true
            } == true
            if (!ready) {
                lastManualSeekTimeMs = System.currentTimeMillis()
                recoverFailedCrossfade()
                return@launch
            }

            outgoingEq.configureEcho(plan.echoDelayMs)
            outgoingEq.setNormalizationGain(plan.outgoingGain)
            incomingEq.setNormalizationGain(plan.incomingGain, 0L)
            outgoingEq.updateOutgoingEq(0f, selectedTool)
            incomingEq.updateIncomingEq(0f, selectedTool)
            // Keep one common tempo throughout the overlap. Changing Sonic parameters
            // every animation tick would repeatedly drain/flush the audio pipeline.
            outgoingDeck.playbackParameters = PlaybackParameters(outTempoRatio, 1f)
            incomingDeck.playbackParameters = PlaybackParameters(inTempoRatio, 1f)
            incomingDeck.volume = 0f
            // Start near a measured outgoing beat, rather than on an arbitrary 200ms UI tick.
            // This is source-time alignment; Android's two AudioTracks are not sample-clock locked.
            val plannedMixOut = if (customDurationMs == null) {
                plan.mixOutMs
            } else outgoingDeck.currentPosition
            val beatStart = if (curAnalysis != null && nextAnalysis != null &&
                MusicalCueSelector.isMeasuredBeat(nextAnalysis, targetCueInMs)
            ) MusicalCueSelector.nextBeatForStart(curAnalysis, outgoingDeck.currentPosition,
                maxOf(plannedMixOut, outgoingDeck.currentPosition + 100L)) else null
            val scheduledStart = beatStart ?: plannedMixOut
            if (scheduledStart > outgoingDeck.currentPosition) {
                withTimeoutOrNull(1800L) {
                    while (outgoingDeck.isPlaying && outgoingDeck.currentPosition < scheduledStart) delay(8L)
                }
            }
            incomingDeck.play()

            // Progress follows the wall clock, so main-thread jitter and delay() overshoot don't
            // stretch the fade. Time spent waiting on a buffering incoming deck is excluded.
            val stepMs = 50L
            var hasSwappedActiveDeck = false
            var elapsedMs = 0L
            var lastTickMs = android.os.SystemClock.elapsedRealtime()

            while (true) {
                if (!isActive) return@launch
                elapsedMs += android.os.SystemClock.elapsedRealtime() - lastTickMs
                // Buffering must not advance the fade or silently promote an unready deck.
                val playing = withTimeoutOrNull(8_000L) {
                    while (!incomingDeck.isPlaying) {
                            if (incomingDeck.playerError != null || incomingDeck.playbackState == Player.STATE_ENDED) {
                            return@withTimeoutOrNull false
                        }
                        delay(25L)
                    }
                    true
                } == true
                lastTickMs = android.os.SystemClock.elapsedRealtime()
                if (!playing) {
                    lastManualSeekTimeMs = System.currentTimeMillis()
                    recoverFailedCrossfade()
                    return@launch
                }
                val t = (elapsedMs.toFloat() / crossfadeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)


                val activeUiTempo = if (t < 0.50f) outTempoRatio else inTempoRatio

                // EQ envelopes change continuously without reconfiguring tempo processing.
                outgoingEq.updateOutgoingEq(t, selectedTool)
                incomingEq.updateIncomingEq(t, selectedTool)

                val (outGain, inGain) = TransitionEnvelopes.gains(selectedTool, t, plan.handoffFraction)

                if (outgoingDeck.isPlaying) {
                    outgoingDeck.volume = (outGain * duckMultiplier).coerceIn(0f, 1f)
                }
                incomingDeck.volume = (inGain * duckMultiplier).coerceIn(0f, 1f)

                // Right on the DJ Bass Swap (t >= 0.50f), swap active deck to incoming track AND
                // advance nextMatchedSong to (nextIndex + 1) so the banner never duplicates currentSong!
                if (t >= plan.handoffFraction && !hasSwappedActiveDeck) {
                    hasSwappedActiveDeck = true
                    activateDeck(incomingDeck, outgoingDeck)

                    val upcomingAfterNext = playlist.getOrNull(nextIndex + 1)
                    val upcomingAfterNextAnalysis = upcomingAfterNext?.let { analysisEngine.getCached(it.id) }
                    val newDuration = activeDeck.duration.takeIf { it > 0 } ?: nextSong.durationMs

                    _playerState.value = _playerState.value.copy(
                        currentSong = nextSong,
                        currentIndex = nextIndex,
                        durationMs = newDuration,
                        currentPositionMs = activeDeck.currentPosition.coerceAtLeast(0L),
                        automix = _playerState.value.automix.copy(
                            currentAnalysis = nextAnalysis,
                            transitionTargetSong = nextSong,
                            transitionFromSongId = outgoingSong?.id,
                            activeTransitionTool = selectedTool,
                            nextMatchedSong = upcomingAfterNext,
                            nextMatchedAnalysis = upcomingAfterNextAnalysis,
                            isTransitioning = true,
                            transitionProgress = t,
                            activeTempoRatio = activeUiTempo
                        )
                    )
                } else {
                    _playerState.value = _playerState.value.copy(
                        automix = _playerState.value.automix.copy(
                            isTransitioning = true,
                            transitionProgress = t,
                            transitionTargetSong = nextSong,
                            transitionFromSongId = outgoingSong?.id,
                            activeTransitionTool = selectedTool,
                            activeTempoRatio = activeUiTempo
                        )
                    )
                }

                if (t >= 1f) break
                delay(stepMs)
            }

            crossfadeJob = null
            finishCrossfade(incomingDeck, outgoingDeck, nextIndex, nextSong)
        }
    }

    private fun advanceAfterEnd() {
        val nextIndex = _playerState.value.currentIndex + 1
        if (nextIndex !in playlist.indices || activeDeck.repeatMode == Player.REPEAT_MODE_ONE) return
        android.util.Log.w("SonaAutomix", "Fallback advance after EOF index=$nextIndex")
        activeDeck.setMediaItems(buildMediaItems(playlist), nextIndex, 0L)
        activeDeck.prepare()
        play()
    }

    private fun recoverFailedCrossfade() {
        val resume = activeDeck.playWhenReady
        val ended = activeDeck.playbackState == Player.STATE_ENDED
        android.util.Log.w("SonaAutomix", "Crossfade preparation/playback failed; restore queue ended=$ended")
        cancelActiveCrossfade()
        if (resume) {
            if ((ended && activeDeck.playbackState == Player.STATE_ENDED) || activeDeck.playerError != null) advanceAfterEnd()
            else play()
        }
    }

    private fun activateDeck(incoming: ExoPlayer, outgoing: ExoPlayer) {
        if (activeDeck !== incoming) {
            listeningGuard.enter(incoming.currentPosition)
            _playerState.value = _playerState.value.copy(automix = _playerState.value.automix.copy(
                entryPositionMs = listeningGuard.entryPositionMs))
        }
        activeDeck = incoming
        inactiveDeck = outgoing
        mediaSession?.player = sessionPlayer
        _playerState.value = _playerState.value.copy(isPlaying = incoming.isPlaying)
        // The incoming deck may already be playing, so no new listener event is guaranteed.
        if (incoming.isPlaying) startTrackingProgress()
    }

    private fun finishCrossfade(
        incoming: ExoPlayer,
        outgoing: ExoPlayer,
        targetIndex: Int,
        targetSong: Song
    ) {
        val fromId = _playerState.value.automix.transitionFromSongId
        outgoing.pause()
        outgoing.volume = duckMultiplier
        outgoing.playbackParameters = PlaybackParameters.DEFAULT
        restoreEchoQueue()
        eqProcessorA.resetEq()
        eqProcessorB.resetEq()
        incoming.volume = duckMultiplier
        incoming.playbackParameters = PlaybackParameters.DEFAULT
        applyTrackNormalization(incoming, targetSong.id)
        activateDeck(incoming, outgoing)
        isPrebufferedForIndex = -1
        activeTransitionTargetIndex = -1
        transitionIncomingDeck = null

        fromId?.let { id ->
            val key = SavedTransition.key(id, targetSong.id)
            savedTransitions[key]?.let { savedTransitions[key] = it.copy(isCompleted = true) }
        }
        rebuildSavedTransitions(targetIndex)
        val upcoming = playlist.getOrNull(targetIndex + 1)
        _playerState.value = _playerState.value.copy(
            currentSong = targetSong,
            currentIndex = targetIndex,
            durationMs = incoming.duration.takeIf { it > 0 } ?: targetSong.durationMs,
            currentPositionMs = incoming.currentPosition.coerceAtLeast(0L),
            automix = _playerState.value.automix.copy(
                currentAnalysis = analysisEngine.getCached(targetSong.id),
                transitionTargetSong = null,
                transitionFromSongId = null,
                nextMatchedSong = upcoming,
                nextMatchedAnalysis = upcoming?.let { analysisEngine.getCached(it.id) },
                isTransitioning = false,
                transitionProgress = 0f,
                activeTempoRatio = 1f,
                analyzedTracks = analysisEngine.getAllCached(),
                savedTransitions = savedTransitions.toMap()
            )
        )
        triggerAnalysisAndSmartQueue(targetIndex)
        scope.launch(Dispatchers.IO) {
            com.sona.player.data.database.UserLibraryDatabaseHelper.getInstance(context).recordPlay(targetSong.id)
        }
    }

    private fun completeCrossfadeImmediately() {
        // Deck roles change halfway through the fade. Keep the actual incoming deck,
        // even if it is buffering or has already become active.
        val incoming = transitionIncomingDeck ?: return
        val targetIndex = activeTransitionTargetIndex
        val targetSong = playlist.getOrNull(targetIndex) ?: return
        val outgoing = if (incoming === deckA) deckB else deckA
        crossfadeJob?.cancel()
        crossfadeJob = null
        finishCrossfade(incoming, outgoing, targetIndex, targetSong)
        play()
    }

    private fun cancelActiveCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        isPrebufferedForIndex = -1
        activeTransitionTargetIndex = -1
        transitionIncomingDeck = null

        eqProcessorA.resetEq()
        eqProcessorB.resetEq()

        inactiveDeck.pause()
        restoreEchoQueue()
        applyTrackNormalization(activeDeck, _playerState.value.currentSong?.id)
        inactiveDeck.volume = 1.0f * duckMultiplier
        inactiveDeck.playbackParameters = PlaybackParameters.DEFAULT

        activeDeck.volume = 1.0f * duckMultiplier
        activeDeck.playbackParameters = PlaybackParameters.DEFAULT

        val curIdx = _playerState.value.currentIndex
        val upcomingSong = playlist.getOrNull(curIdx + 1)
        val upcomingAnalysis = upcomingSong?.let { analysisEngine.getCached(it.id) }

        _playerState.value = _playerState.value.copy(
            automix = _playerState.value.automix.copy(
                isTransitioning = false,
                transitionProgress = 0f,
                transitionTargetSong = null,
                transitionFromSongId = null,
                nextMatchedSong = upcomingSong,
                nextMatchedAnalysis = upcomingAnalysis,
                activeTempoRatio = 1.0f
            )
        )
    }

    private fun buildMediaItems(songs: List<Song>): List<MediaItem> {
        return songs.map { song ->
            val metadata = MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .setArtworkUri(song.albumArtUri)
                .build()

            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(song.contentUri)
                .setMediaMetadata(metadata)
                .build()
        }
    }
}

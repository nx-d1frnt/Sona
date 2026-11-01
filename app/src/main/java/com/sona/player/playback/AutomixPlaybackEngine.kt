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
import com.sona.player.automix.AudioAnalysisEngine
import com.sona.player.automix.AutomixEqAudioProcessor
import com.sona.player.automix.AutomixMode
import com.sona.player.automix.AutomixQueueSelector
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.AutomixTransitionTool
import com.sona.player.automix.SavedTransition
import com.sona.player.automix.TrackAnalysis
import com.sona.player.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dual-Deck ExoPlayer engine powering Sona's intelligent background Automix:
 * - Hybrid background PCM analysis (BPM, energy, cue-in, mix-out) with local disk cache
 * - Dynamic "one-step-ahead" smart queue matching by BPM & energy continuity
 * - Equal-Power Crossfade (cos/sin curves) + subtle pitch-preserving tempo sync (±4%)
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
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
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

    var activeDeck: ExoPlayer = deckA
        private set
    private var inactiveDeck: ExoPlayer = deckB

    private var mediaSession: MediaSession? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val playlist = mutableListOf<Song>()
    private val savedTransitions = mutableMapOf<String, SavedTransition>()
    private val userLockedNextForSongId = mutableSetOf<Long>()

    private var progressTrackerJob: Job? = null
    private var analysisJob: Job? = null
    private var crossfadeJob: Job? = null

    private var isPrebufferedForIndex: Int = -1
    private var activeTransitionTargetIndex: Int = -1
    private var currentMatchResult: AutomixQueueSelector.MatchResult? = null
    private var duckMultiplier: Float = 1.0f
    private var hasAudioFocus: Boolean = false
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
        session.player = activeDeck
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (deck !== activeDeck) return
                _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
                if (isPlaying) {
                    startTrackingProgress()
                } else if (crossfadeJob?.isActive != true) {
                    stopTrackingProgress()
                }
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

                val currentIdx = deck.currentMediaItemIndex
                val song = playlist.getOrNull(currentIdx)
                val duration = deck.duration.takeIf { it > 0 } ?: (song?.durationMs ?: 0L)

                isPrebufferedForIndex = -1
                currentMatchResult = null

                val upcomingSong = playlist.getOrNull(currentIdx + 1)
                val upcomingAnalysis = upcomingSong?.let { analysisEngine.getCached(it.id) }

                _playerState.value = _playerState.value.copy(
                    currentSong = song,
                    currentIndex = currentIdx,
                    durationMs = duration,
                    currentPositionMs = deck.currentPosition.coerceAtLeast(0L),
                    automix = _playerState.value.automix.copy(
                        currentAnalysis = song?.let { analysisEngine.getCached(it.id) },
                        transitionTargetSong = null,
                        nextMatchedSong = upcomingSong,
                        nextMatchedAnalysis = upcomingAnalysis,
                        isTransitioning = false,
                        transitionProgress = 0f
                    )
                )

                if (song != null) {
                    triggerAnalysisAndSmartQueue(currentIdx)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (deck !== activeDeck) return
                if (playbackState == Player.STATE_READY) {
                    val duration = deck.duration.takeIf { it > 0 }
                        ?: (_playerState.value.currentSong?.durationMs ?: 0L)
                    _playerState.value = _playerState.value.copy(durationMs = duration)
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

        playlist.clear()
        playlist.addAll(songs)
        savedTransitions.clear()
        userLockedNextForSongId.clear()

        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        val mediaItems = buildMediaItems(playlist)

        requestAudioFocus()

        activeDeck.volume = 1.0f * duckMultiplier
        activeDeck.playbackParameters = PlaybackParameters.DEFAULT
        activeDeck.setMediaItems(mediaItems, safeIndex, 0L)
        activeDeck.prepare()
        activeDeck.play()

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
    }

    fun togglePlayPause() {
        if (activeDeck.isPlaying) {
            pauseInternal()
        } else {
            if (requestAudioFocus()) {
                activeDeck.play()
            }
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
        _playerState.value = _playerState.value.copy(currentPositionMs = positionMs)
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
            requestAudioFocus()
            activeDeck.seekToDefaultPosition(targetIndex)
            activeDeck.play()
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

    fun toggleShuffle() {
        val nextShuffle = !activeDeck.shuffleModeEnabled
        activeDeck.shuffleModeEnabled = nextShuffle
        inactiveDeck.shuffleModeEnabled = nextShuffle
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
     * Cycles Automix mode: CLASSIC_OUTRO (Outro Mix) -> QUICK_MID_MIX (Mid-Track DJ Set) -> OFF -> CLASSIC_OUTRO
     */
    fun toggleAutomix() {
        val currentAutomix = _playerState.value.automix
        val nextMode = when (currentAutomix.mode) {
            AutomixMode.CLASSIC_OUTRO -> AutomixMode.QUICK_MID_MIX
            AutomixMode.QUICK_MID_MIX -> AutomixMode.OFF
            AutomixMode.OFF -> AutomixMode.CLASSIC_OUTRO
        }
        val newEnabled = (nextMode != AutomixMode.OFF)
        if (!newEnabled && crossfadeJob?.isActive == true) {
            cancelActiveCrossfade()
        }
        isPrebufferedForIndex = -1
        _playerState.value = _playerState.value.copy(
            automix = currentAutomix.copy(
                isEnabled = newEnabled,
                mode = nextMode
            )
        )
        if (newEnabled) {
            val idx = _playerState.value.currentIndex
            if (idx in playlist.indices) {
                triggerAnalysisAndSmartQueue(idx)
            }
        }
    }

    /**
     * Analyzes the current track and upcoming candidates in the background,
     * then selects the best harmonic BPM/Energy match and moves it "one step ahead" (to currentIndex + 1).
     */
    private fun triggerAnalysisAndSmartQueue(currentIndex: Int) {
        analysisJob?.cancel()
        val currentSong = playlist.getOrNull(currentIndex) ?: return

        analysisJob = scope.launch {
            // Wait if an active crossfade is running so hardware MediaCodec is 100% dedicated to dual-deck playback
            while (crossfadeJob?.isActive == true && isActive) {
                delay(250)
            }

            _playerState.value = _playerState.value.copy(
                automix = _playerState.value.automix.copy(isAnalyzing = true)
            )

            // 1. Analyze current song first so the UI gets its exact BPM & Automix Zone right away
            val curAnalysis = analysisEngine.analyzeSong(currentSong)
            rebuildSavedTransitions(currentIndex)
            _playerState.value = _playerState.value.copy(
                automix = _playerState.value.automix.copy(
                    currentAnalysis = curAnalysis,
                    analyzedTracks = analysisEngine.getAllCached(),
                    savedTransitions = savedTransitions.toMap()
                )
            )

            // 2. Analyze the next 5 candidate tracks in the queue with gentle pacing
            val maxLookahead = (currentIndex + 5).coerceAtMost(playlist.lastIndex)
            for (i in (currentIndex + 1)..maxLookahead) {
                if (!isActive) return@launch
                while (crossfadeJob?.isActive == true && isActive) {
                    delay(250)
                }
                val candidate = playlist.getOrNull(i) ?: continue
                analysisEngine.analyzeSong(candidate)
                // Update smart match incrementally as candidates finish analyzing
                if (_playerState.value.automix.isEnabled && crossfadeJob?.isActive != true) {
                    applyOneStepAheadMatch(currentIndex, curAnalysis)
                } else {
                    rebuildSavedTransitions(currentIndex)
                    _playerState.value = _playerState.value.copy(
                        automix = _playerState.value.automix.copy(
                            analyzedTracks = analysisEngine.getAllCached(),
                            savedTransitions = savedTransitions.toMap()
                        )
                    )
                }
                delay(120)
            }

            rebuildSavedTransitions(currentIndex)
            _playerState.value = _playerState.value.copy(
                automix = _playerState.value.automix.copy(
                    isAnalyzing = false,
                    analyzedTracks = analysisEngine.getAllCached(),
                    savedTransitions = savedTransitions.toMap()
                )
            )

            // 3. Continue indexing a few further tracks in the queue at low priority
            val furtherLimit = (currentIndex + 10).coerceAtMost(playlist.lastIndex)
            for (i in (maxLookahead + 1)..furtherLimit) {
                if (!isActive) return@launch
                while (crossfadeJob?.isActive == true && isActive) {
                    delay(300)
                }
                val candidate = playlist.getOrNull(i) ?: continue
                analysisEngine.analyzeSong(candidate)
                rebuildSavedTransitions(currentIndex)
                _playerState.value = _playerState.value.copy(
                    automix = _playerState.value.automix.copy(
                        analyzedTracks = analysisEngine.getAllCached(),
                        savedTransitions = savedTransitions.toMap()
                    )
                )
                delay(250)
            }
        }
    }

    private fun rebuildSavedTransitions(currentIndex: Int) {
        for (i in 0 until (playlist.size - 1)) {
            val songA = playlist[i]
            val songB = playlist[i + 1]
            val analysisA = analysisEngine.getCached(songA.id) ?: continue
            val analysisB = analysisEngine.getCached(songB.id) ?: continue
            val key = SavedTransition.key(songA.id, songB.id)
            val existing = savedTransitions[key]
            val completed = (i < currentIndex) || (existing?.isCompleted == true)
            savedTransitions[key] = AutomixQueueSelector.buildSavedTransition(
                fromSongId = songA.id,
                toSongId = songB.id,
                fromAnalysis = analysisA,
                toAnalysis = analysisB,
                isCompleted = completed
            )
        }
    }

    private fun applyOneStepAheadMatch(currentIndex: Int, currentAnalysis: TrackAnalysis) {
        val currentSong = playlist.getOrNull(currentIndex) ?: return
        if (currentIndex + 1 >= playlist.size) return

        // If the user or a previous chain lock already locked the next track for currentSong, preserve it!
        if (userLockedNextForSongId.contains(currentSong.id)) {
            rebuildSavedTransitions(currentIndex)
            val lockedNext = playlist.getOrNull(currentIndex + 1)
            val lockedAnalysis = lockedNext?.let { analysisEngine.getCached(it.id) }
            val lockedTransition = if (lockedNext != null) {
                savedTransitions[SavedTransition.key(currentSong.id, lockedNext.id)]
            } else null
            _playerState.value = _playerState.value.copy(
                currentQueue = playlist.toList(),
                automix = _playerState.value.automix.copy(
                    nextMatchedSong = lockedNext,
                    nextMatchedAnalysis = lockedAnalysis,
                    matchScorePercent = lockedTransition?.matchScorePercent ?: 90,
                    analyzedTracks = analysisEngine.getAllCached(),
                    savedTransitions = savedTransitions.toMap()
                )
            )
            return
        }

        val match = AutomixQueueSelector.findBestNextTrack(
            currentSong = currentSong,
            currentAnalysis = currentAnalysis,
            queue = playlist,
            currentIndex = currentIndex,
            analysisProvider = { id -> analysisEngine.getCached(id) }
        ) ?: return

        currentMatchResult = match

        // Move the best matched track into slot (currentIndex + 1) both in playlist and in activeDeck
        val targetNextSlot = currentIndex + 1
        if (match.candidateIndexInQueue != targetNextSlot &&
            match.candidateIndexInQueue in playlist.indices
        ) {
            val matchedSong = playlist.removeAt(match.candidateIndexInQueue)
            playlist.add(targetNextSlot, matchedSong)
            if (activeDeck.mediaItemCount > match.candidateIndexInQueue) {
                activeDeck.moveMediaItem(match.candidateIndexInQueue, targetNextSlot)
            }
            isPrebufferedForIndex = -1
        }

        rebuildSavedTransitions(currentIndex)

        // Only update nextMatchedSong if match.song is NOT the current song
        if (match.song.id != currentSong.id) {
            _playerState.value = _playerState.value.copy(
                currentQueue = playlist.toList(),
                automix = _playerState.value.automix.copy(
                    nextMatchedSong = match.song,
                    nextMatchedAnalysis = match.analysis,
                    matchScorePercent = match.matchScorePercent,
                    analyzedTracks = analysisEngine.getAllCached(),
                    savedTransitions = savedTransitions.toMap()
                )
            )
        }
    }

    private fun startTrackingProgress() {
        stopTrackingProgress()
        progressTrackerJob = scope.launch {
            while (isActive) {
                val pos = activeDeck.currentPosition.coerceAtLeast(0L)
                val dur = if (activeDeck.duration > 0) {
                    activeDeck.duration
                } else {
                    _playerState.value.durationMs
                }

                _playerState.value = _playerState.value.copy(
                    currentPositionMs = pos,
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

        val curAnalysis = automix.currentAnalysis
        val mixOutMs = curAnalysis?.effectiveMixOutMs(automix.mode)
            ?: if (automix.mode == AutomixMode.QUICK_MID_MIX) {
                (durationMs * 0.50f).toLong().coerceAtLeast(15_000L)
            } else {
                (durationMs - 14000L).coerceAtLeast((durationMs * 0.70f).toLong())
            }

        if (durationMs < 20_000L) return // Skip Automix for very short audio clips

        // 1. Pre-buffer the inactive deck 7 seconds before the Automix Zone starts
        if (positionMs >= (mixOutMs - 7000L) && isPrebufferedForIndex != nextIndex) {
            prebufferInactiveDeck(nextIndex)
        }

        // 2. Trigger the DJ Automix (High-Pass Blend -> Bass Swap -> Low-Pass Filter Sweep) when entering the Automix Zone
        if (positionMs >= mixOutMs && positionMs < durationMs - 2500L) {
            val customDur = if (automix.mode == AutomixMode.QUICK_MID_MIX) 8500L else null
            startCrossfadeTransition(nextIndex, nextSong, customDurationMs = customDur)
        }
    }

    private fun prebufferInactiveDeck(nextIndex: Int) {
        if (nextIndex !in playlist.indices) return
        val nextSong = playlist[nextIndex]
        val nextAnalysis = analysisEngine.getCached(nextSong.id)
        val cueInMs = nextAnalysis?.cueInMs ?: 0L

        val items = buildMediaItems(playlist)
        inactiveDeck.pause()
        inactiveDeck.volume = 0f
        inactiveDeck.playbackParameters = PlaybackParameters.DEFAULT
        getEqForDeck(inactiveDeck).resetEq()
        inactiveDeck.setMediaItems(items, nextIndex, cueInMs)
        inactiveDeck.prepare()
        isPrebufferedForIndex = nextIndex
    }

    private fun startCrossfadeTransition(
        nextIndex: Int,
        nextSong: Song,
        customDurationMs: Long? = null
    ) {
        if (crossfadeJob?.isActive == true) return

        if (isPrebufferedForIndex != nextIndex) {
            prebufferInactiveDeck(nextIndex)
        }

        val outgoingDeck = activeDeck
        val incomingDeck = inactiveDeck
        val outgoingEq = getEqForDeck(outgoingDeck)
        val incomingEq = getEqForDeck(incomingDeck)

        val outgoingSong = _playerState.value.currentSong
        val curAnalysis = _playerState.value.automix.currentAnalysis
        val nextAnalysis = analysisEngine.getCached(nextSong.id)
            ?: _playerState.value.automix.nextMatchedAnalysis

        val crossfadeDurationMs = customDurationMs
            ?: (curAnalysis?.crossfadeDurationMs ?: 10_500L).coerceIn(8500L, 13_000L)

        activeTransitionTargetIndex = nextIndex

        // Automatically select the optimal DJ Transition Toolset & BPM Tempo Sync Ratios
        val selectedTool = if (curAnalysis != null && nextAnalysis != null) {
            AutomixQueueSelector.selectOptimalTransitionTool(curAnalysis, nextAnalysis)
        } else {
            AutomixTransitionTool.BASS_SWAP_DROP
        }

        val (outTempoRatio, inTempoRatio) = if (curAnalysis != null && nextAnalysis != null) {
            AutomixQueueSelector.computeTempoSyncRatios(curAnalysis.bpm, nextAnalysis.bpm)
        } else {
            1.0f to 1.0f
        }

        // Lock this transition pair in savedTransitions so it is preserved
        if (outgoingSong != null && curAnalysis != null && nextAnalysis != null) {
            val key = SavedTransition.key(outgoingSong.id, nextSong.id)
            savedTransitions[key] = AutomixQueueSelector.buildSavedTransition(
                fromSongId = outgoingSong.id,
                toSongId = nextSong.id,
                fromAnalysis = curAnalysis,
                toAnalysis = nextAnalysis,
                isCompleted = false
            )
            userLockedNextForSongId.add(outgoingSong.id)
        }

        // Ensure transitionTargetSong & nextMatchedSong are explicitly set before transition starts
        _playerState.value = _playerState.value.copy(
            automix = _playerState.value.automix.copy(
                isTransitioning = true,
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

        crossfadeJob = scope.launch {
            // Initialize DJ DSP Engine (EQ + Filter + Echo + In-Processor Tempo Sync)
            outgoingEq.updateOutgoingEq(0f, selectedTool, 1.0f)
            incomingEq.updateIncomingEq(0f, selectedTool, inTempoRatio)

            // Keep ExoPlayer PlaybackParameters at DEFAULT (1.0x) so SonicAudioProcessor never stalls;
            // real-time BPM tempo sync is performed directly inside AutomixEqAudioProcessor!
            outgoingDeck.playbackParameters = PlaybackParameters.DEFAULT
            incomingDeck.playbackParameters = PlaybackParameters.DEFAULT
            incomingDeck.volume = 0f
            incomingDeck.play()

            val stepMs = 50L
            val totalSteps = (crossfadeDurationMs / stepMs).toInt().coerceAtLeast(20)
            var hasSwappedActiveDeck = false

            for (step in 1..totalSteps) {
                if (!isActive) break
                val t = (step.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)

                // Guard: pause outgoing deck right before EOF so it never auto-advances to the next track
                val outDur = outgoingDeck.duration
                if (outgoingDeck.isPlaying && outDur > 0L && outgoingDeck.currentPosition >= outDur - 250L) {
                    outgoingDeck.pause()
                    outgoingDeck.volume = 0f
                }

                // 1. Smooth In-Processor BPM Tempo Sync Ramping:
                // Outgoing deck glides from 1.0x -> outTempoRatio during first half;
                // Incoming deck enters locked at inTempoRatio and glides smoothly to 1.0x natural speed after Bass Swap!
                val outSpeed = if (t <= 0.50f) {
                    1.0f + (outTempoRatio - 1.0f) * (t / 0.50f)
                } else {
                    outTempoRatio
                }
                val inSpeed = if (t <= 0.48f) {
                    inTempoRatio
                } else {
                    val glideFrac = ((t - 0.48f) / 0.48f).coerceIn(0f, 1f)
                    inTempoRatio + (1.0f - inTempoRatio) * glideFrac
                }
                val activeUiTempo = if (t < 0.50f) outSpeed else inSpeed

                // 2. Update real-time DJ DSP (Isolator + LPF/HPF Sweep + Stereo Dub Echo + Tempo Resampler)
                outgoingEq.updateOutgoingEq(t, selectedTool, outSpeed)
                incomingEq.updateIncomingEq(t, selectedTool, inSpeed)

                // 3. Tool-Specific DJ Volume Envelopes:
                val outGain = when (selectedTool) {
                    AutomixTransitionTool.HIGH_PASS_RISER_SLAM -> when {
                        t < 0.48f -> 1.0f - (t / 0.48f) * 0.15f
                        else -> {
                            val tailFrac = ((t - 0.48f) / 0.52f).coerceIn(0f, 1f)
                            0.85f * (1f - tailFrac) * (1f - tailFrac) // Fast tension release as Echo tail rings out
                        }
                    }
                    AutomixTransitionTool.SILKY_WARM_ISOLATOR -> cos(t * PI * 0.5).toFloat()
                    else -> when {
                        t < 0.45f -> 1.0f - (t / 0.45f) * 0.12f // Holds 100%..88% while driving the bass
                        else -> {
                            val tailFrac = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
                            0.88f * cos(tailFrac * PI * 0.5).toFloat() // Fades out while LPF/Echo sweeps
                        }
                    }
                }

                val inGain = when (selectedTool) {
                    AutomixTransitionTool.HIGH_PASS_RISER_SLAM -> when {
                        t < 0.45f -> (t / 0.45f) * 0.72f
                        t < 0.52f -> 0.72f + ((t - 0.45f) / 0.07f) * 0.28f // Slam Drop to 100%!
                        else -> 1.0f
                    }
                    AutomixTransitionTool.SILKY_WARM_ISOLATOR -> sin(t * PI * 0.5).toFloat()
                    else -> when {
                        t < 0.44f -> sin((t / 0.44f) * PI * 0.5).toFloat() * 0.84f
                        t < 0.54f -> 0.84f + ((t - 0.44f) / 0.10f) * 0.16f
                        else -> 1.0f
                    }
                }

                if (outgoingDeck.isPlaying) {
                    outgoingDeck.volume = (outGain * duckMultiplier).coerceIn(0f, 1f)
                }
                incomingDeck.volume = (inGain * duckMultiplier).coerceIn(0f, 1f)

                // Right on the DJ Bass Swap (t >= 0.50f), swap active deck to incoming track AND
                // advance nextMatchedSong to (nextIndex + 1) so the banner never duplicates currentSong!
                if (t >= 0.50f && !hasSwappedActiveDeck) {
                    hasSwappedActiveDeck = true
                    activeDeck = incomingDeck
                    inactiveDeck = outgoingDeck
                    mediaSession?.player = activeDeck

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

                delay(stepMs)
            }

            if (!hasSwappedActiveDeck) {
                activeDeck = incomingDeck
                inactiveDeck = outgoingDeck
                mediaSession?.player = activeDeck
            }

            // Stop and reset the old outgoing deck & both EQ processors
            outgoingDeck.pause()
            outgoingDeck.volume = 1.0f * duckMultiplier
            outgoingDeck.playbackParameters = PlaybackParameters.DEFAULT
            outgoingEq.resetEq()
            incomingEq.resetEq()

            // Restore incoming (now active) deck to 100% volume and 1.0x natural speed
            activeDeck.volume = 1.0f * duckMultiplier
            activeDeck.playbackParameters = PlaybackParameters.DEFAULT
            isPrebufferedForIndex = -1
            activeTransitionTargetIndex = -1

            // Mark the completed transition in savedTransitions
            if (outgoingSong != null) {
                val key = SavedTransition.key(outgoingSong.id, nextSong.id)
                savedTransitions[key]?.let { existing ->
                    savedTransitions[key] = existing.copy(isCompleted = true)
                }
            }
            rebuildSavedTransitions(nextIndex)

            val upcomingSong = playlist.getOrNull(nextIndex + 1)
            val upcomingAnalysis = upcomingSong?.let { analysisEngine.getCached(it.id) }

            _playerState.value = _playerState.value.copy(
                currentSong = nextSong,
                currentIndex = nextIndex,
                durationMs = activeDeck.duration.takeIf { it > 0 } ?: nextSong.durationMs,
                currentPositionMs = activeDeck.currentPosition.coerceAtLeast(0L),
                automix = _playerState.value.automix.copy(
                    currentAnalysis = nextAnalysis,
                    transitionTargetSong = null,
                    transitionFromSongId = null,
                    nextMatchedSong = upcomingSong,
                    nextMatchedAnalysis = upcomingAnalysis,
                    isTransitioning = false,
                    transitionProgress = 0f,
                    activeTempoRatio = 1.0f,
                    analyzedTracks = analysisEngine.getAllCached(),
                    savedTransitions = savedTransitions.toMap()
                )
            )

            // Schedule lookahead analysis & smart queue selection for the newly active track
            triggerAnalysisAndSmartQueue(nextIndex)
        }
    }

    private fun completeCrossfadeImmediately() {
        crossfadeJob?.cancel()
        crossfadeJob = null

        eqProcessorA.resetEq()
        eqProcessorB.resetEq()

        val nextIdx = if (activeTransitionTargetIndex in playlist.indices) {
            activeTransitionTargetIndex
        } else {
            _playerState.value.currentIndex + 1
        }
        activeTransitionTargetIndex = -1
        val nextSong = playlist.getOrNull(nextIdx)

        // If inactiveDeck was already playing the incoming song, swap to it immediately
        if (inactiveDeck.isPlaying && nextSong != null) {
            val oldActive = activeDeck
            activeDeck = inactiveDeck
            inactiveDeck = oldActive
            mediaSession?.player = activeDeck

            oldActive.pause()
            oldActive.volume = 1.0f * duckMultiplier
            oldActive.playbackParameters = PlaybackParameters.DEFAULT

            activeDeck.volume = 1.0f * duckMultiplier
            activeDeck.playbackParameters = PlaybackParameters.DEFAULT
            isPrebufferedForIndex = -1

            rebuildSavedTransitions(nextIdx)
            val nextAnalysis = analysisEngine.getCached(nextSong.id)
            val upcomingSong = playlist.getOrNull(nextIdx + 1)
            val upcomingAnalysis = upcomingSong?.let { analysisEngine.getCached(it.id) }

            _playerState.value = _playerState.value.copy(
                currentSong = nextSong,
                currentIndex = nextIdx,
                durationMs = activeDeck.duration.takeIf { it > 0 } ?: nextSong.durationMs,
                currentPositionMs = activeDeck.currentPosition.coerceAtLeast(0L),
                automix = _playerState.value.automix.copy(
                    currentAnalysis = nextAnalysis,
                    transitionTargetSong = null,
                    transitionFromSongId = null,
                    nextMatchedSong = upcomingSong,
                    nextMatchedAnalysis = upcomingAnalysis,
                    isTransitioning = false,
                    transitionProgress = 0f,
                    activeTempoRatio = 1.0f,
                    analyzedTracks = analysisEngine.getAllCached(),
                    savedTransitions = savedTransitions.toMap()
                )
            )
            triggerAnalysisAndSmartQueue(nextIdx)
        } else {
            cancelActiveCrossfade()
            if (activeDeck.hasNextMediaItem()) {
                activeDeck.seekToNextMediaItem()
            }
        }
    }

    private fun cancelActiveCrossfade() {
        crossfadeJob?.cancel()
        crossfadeJob = null
        isPrebufferedForIndex = -1
        activeTransitionTargetIndex = -1

        eqProcessorA.resetEq()
        eqProcessorB.resetEq()

        inactiveDeck.pause()
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

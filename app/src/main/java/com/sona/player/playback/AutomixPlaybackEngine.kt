package com.sona.player.playback

import android.content.Context
import android.media.AudioAttributes as FrameworkAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.sona.player.automix.AudioAnalysisEngine
import com.sona.player.automix.AutomixQueueSelector
import com.sona.player.automix.AutomixTransitionState
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

    // Two ExoPlayer decks with handleAudioFocus = false; unified AudioFocus is managed below
    private val deckA: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(media3AudioAttrs, false)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val deckB: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(media3AudioAttrs, false)
        .setHandleAudioBecomingNoisy(true)
        .build()

    var activeDeck: ExoPlayer = deckA
        private set
    private var inactiveDeck: ExoPlayer = deckB

    private var mediaSession: MediaSession? = null

    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val playlist = mutableListOf<Song>()
    private var progressTrackerJob: Job? = null
    private var analysisJob: Job? = null
    private var crossfadeJob: Job? = null

    private var isPrebufferedForIndex: Int = -1
    private var currentMatchResult: AutomixQueueSelector.MatchResult? = null
    private var duckMultiplier: Float = 1.0f
    private var hasAudioFocus: Boolean = false

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
                if (deck !== activeDeck) return
                if (crossfadeJob?.isActive == true) return // Handled by crossfade engine

                val currentIdx = deck.currentMediaItemIndex
                val song = playlist.getOrNull(currentIdx)
                val duration = deck.duration.takeIf { it > 0 } ?: (song?.durationMs ?: 0L)

                isPrebufferedForIndex = -1
                currentMatchResult = null

                _playerState.value = _playerState.value.copy(
                    currentSong = song,
                    currentIndex = currentIdx,
                    durationMs = duration,
                    currentPositionMs = deck.currentPosition.coerceAtLeast(0L)
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

        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            currentIndex = safeIndex,
            currentSong = initialSong,
            durationMs = initialSong.durationMs,
            currentPositionMs = 0L,
            automix = _playerState.value.automix.copy(
                currentAnalysis = cachedAnalysis,
                nextMatchedSong = playlist.getOrNull(safeIndex + 1),
                nextMatchedAnalysis = playlist.getOrNull(safeIndex + 1)?.let { analysisEngine.getCached(it.id) },
                isTransitioning = false,
                transitionProgress = 0f,
                activeTempoRatio = 1.0f
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
        if (activeDeck.hasNextMediaItem()) {
            activeDeck.seekToNextMediaItem()
        }
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

    fun toggleAutomix() {
        val currentAutomix = _playerState.value.automix
        val newEnabled = !currentAutomix.isEnabled
        if (!newEnabled && crossfadeJob?.isActive == true) {
            cancelActiveCrossfade()
        }
        _playerState.value = _playerState.value.copy(
            automix = currentAutomix.copy(isEnabled = newEnabled)
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
            _playerState.value = _playerState.value.copy(
                automix = _playerState.value.automix.copy(isAnalyzing = true)
            )

            // 1. Analyze current song first so the UI gets its exact BPM & Automix Zone right away
            val curAnalysis = analysisEngine.analyzeSong(currentSong)
            _playerState.value = _playerState.value.copy(
                automix = _playerState.value.automix.copy(currentAnalysis = curAnalysis)
            )

            // 2. Analyze the next 8 candidate tracks in the queue
            val maxLookahead = (currentIndex + 8).coerceAtMost(playlist.lastIndex)
            for (i in (currentIndex + 1)..maxLookahead) {
                if (!isActive) return@launch
                val candidate = playlist.getOrNull(i) ?: continue
                analysisEngine.analyzeSong(candidate)
                // Update smart match incrementally as candidates finish analyzing
                if (_playerState.value.automix.isEnabled && crossfadeJob?.isActive != true) {
                    applyOneStepAheadMatch(currentIndex, curAnalysis)
                }
            }

            _playerState.value = _playerState.value.copy(
                automix = _playerState.value.automix.copy(isAnalyzing = false)
            )

            // 3. Continue indexing further tracks in the queue at low priority
            val furtherLimit = (currentIndex + 20).coerceAtMost(playlist.lastIndex)
            for (i in (maxLookahead + 1)..furtherLimit) {
                if (!isActive) return@launch
                val candidate = playlist.getOrNull(i) ?: continue
                analysisEngine.analyzeSong(candidate)
            }
        }
    }

    private fun applyOneStepAheadMatch(currentIndex: Int, currentAnalysis: TrackAnalysis) {
        val currentSong = playlist.getOrNull(currentIndex) ?: return
        if (currentIndex + 1 >= playlist.size) return

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

        _playerState.value = _playerState.value.copy(
            currentQueue = playlist.toList(),
            automix = _playerState.value.automix.copy(
                nextMatchedSong = match.song,
                nextMatchedAnalysis = match.analysis,
                matchScorePercent = match.matchScorePercent
            )
        )
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

        val nextIndex = state.currentIndex + 1
        val nextSong = playlist.getOrNull(nextIndex) ?: return

        val curAnalysis = automix.currentAnalysis
        val mixOutMs = curAnalysis?.mixOutMs
            ?: (durationMs - 8000L).coerceAtLeast((durationMs * 0.80f).toLong())

        if (durationMs < 20_000L) return // Skip Automix for very short audio clips

        // 1. Pre-buffer the inactive deck 6 seconds before the Automix Zone starts
        if (positionMs >= (mixOutMs - 6000L) && isPrebufferedForIndex != nextIndex) {
            prebufferInactiveDeck(nextIndex)
        }

        // 2. Trigger the Equal-Power Crossfade + Tempo Sync when entering the Automix Zone
        if (positionMs >= mixOutMs && positionMs < durationMs - 1500L) {
            startCrossfadeTransition(nextIndex, nextSong)
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
        inactiveDeck.setMediaItems(items, nextIndex, cueInMs)
        inactiveDeck.prepare()
        isPrebufferedForIndex = nextIndex
    }

    private fun startCrossfadeTransition(nextIndex: Int, nextSong: Song) {
        if (crossfadeJob?.isActive == true) return

        if (isPrebufferedForIndex != nextIndex) {
            prebufferInactiveDeck(nextIndex)
        }

        val outgoingDeck = activeDeck
        val incomingDeck = inactiveDeck

        val curAnalysis = _playerState.value.automix.currentAnalysis
        val nextAnalysis = analysisEngine.getCached(nextSong.id)
            ?: _playerState.value.automix.nextMatchedAnalysis

        val crossfadeDurationMs = (curAnalysis?.crossfadeDurationMs ?: 7500L).coerceIn(5000L, 10_000L)

        val (outTempoRatio, inTempoRatio) = if (curAnalysis != null && nextAnalysis != null) {
            AutomixQueueSelector.computeTempoSyncRatios(curAnalysis.bpm, nextAnalysis.bpm)
        } else {
            1.0f to 1.0f
        }

        crossfadeJob = scope.launch {
            // Start incoming deck at volume 0 with pitch-preserving tempo sync
            incomingDeck.volume = 0f
            incomingDeck.playbackParameters = PlaybackParameters(inTempoRatio, 1.0f)
            incomingDeck.play()

            val stepMs = 50L
            val totalSteps = (crossfadeDurationMs / stepMs).toInt().coerceAtLeast(20)
            var hasSwappedActiveDeck = false

            for (step in 1..totalSteps) {
                if (!isActive) break
                val t = (step.toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)

                // Equal-Power Crossfade curves: cos(t * PI/2) and sin(t * PI/2)
                val outGain = cos(t * PI * 0.5).toFloat()
                val inGain = sin(t * PI * 0.5).toFloat()

                outgoingDeck.volume = (outGain * duckMultiplier).coerceIn(0f, 1f)
                incomingDeck.volume = (inGain * duckMultiplier).coerceIn(0f, 1f)

                // Smooth pitch-preserving tempo sync (±4% max)
                val currentDisplayTempo: Float
                if (t <= 0.5f) {
                    val frac = t / 0.5f
                    val outSpeed = 1.0f + (outTempoRatio - 1.0f) * frac
                    outgoingDeck.playbackParameters = PlaybackParameters(outSpeed, 1.0f)
                    incomingDeck.playbackParameters = PlaybackParameters(inTempoRatio, 1.0f)
                    currentDisplayTempo = inTempoRatio
                } else {
                    val frac = (t - 0.5f) / 0.5f
                    val inSpeed = inTempoRatio + (1.0f - inTempoRatio) * frac
                    incomingDeck.playbackParameters = PlaybackParameters(inSpeed, 1.0f)
                    currentDisplayTempo = inSpeed
                }

                // At the peak of the transition (t >= 0.50f), swap active deck so UI & MediaSession
                // smoothly transition cover art, Monet palette, and metadata to the incoming track!
                if (t >= 0.50f && !hasSwappedActiveDeck) {
                    hasSwappedActiveDeck = true
                    activeDeck = incomingDeck
                    inactiveDeck = outgoingDeck
                    mediaSession?.player = activeDeck

                    val newDuration = activeDeck.duration.takeIf { it > 0 } ?: nextSong.durationMs
                    _playerState.value = _playerState.value.copy(
                        currentSong = nextSong,
                        currentIndex = nextIndex,
                        durationMs = newDuration,
                        currentPositionMs = activeDeck.currentPosition.coerceAtLeast(0L),
                        automix = _playerState.value.automix.copy(
                            currentAnalysis = nextAnalysis,
                            transitionProgress = t,
                            isTransitioning = true,
                            activeTempoRatio = currentDisplayTempo
                        )
                    )
                } else {
                    _playerState.value = _playerState.value.copy(
                        automix = _playerState.value.automix.copy(
                            isTransitioning = true,
                            transitionProgress = t,
                            activeTempoRatio = currentDisplayTempo
                        )
                    )
                }

                delay(stepMs)
            }

            // Ensure deck swap completed if loop ended
            if (!hasSwappedActiveDeck) {
                activeDeck = incomingDeck
                inactiveDeck = outgoingDeck
                mediaSession?.player = activeDeck
            }

            // Stop and reset the old outgoing deck
            outgoingDeck.pause()
            outgoingDeck.volume = 1.0f * duckMultiplier
            outgoingDeck.playbackParameters = PlaybackParameters.DEFAULT

            // Restore incoming (now active) deck to 100% volume and 1.0x natural speed
            activeDeck.volume = 1.0f * duckMultiplier
            activeDeck.playbackParameters = PlaybackParameters.DEFAULT
            isPrebufferedForIndex = -1

            _playerState.value = _playerState.value.copy(
                currentSong = nextSong,
                currentIndex = nextIndex,
                automix = _playerState.value.automix.copy(
                    currentAnalysis = nextAnalysis,
                    isTransitioning = false,
                    transitionProgress = 0f,
                    activeTempoRatio = 1.0f
                )
            )

            // Schedule lookahead analysis & smart queue selection for the newly active track
            triggerAnalysisAndSmartQueue(nextIndex)
        }
    }

    private fun completeCrossfadeImmediately() {
        crossfadeJob?.cancel()
        crossfadeJob = null

        val nextIdx = _playerState.value.currentIndex + 1
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

            val nextAnalysis = analysisEngine.getCached(nextSong.id)
            _playerState.value = _playerState.value.copy(
                currentSong = nextSong,
                currentIndex = nextIdx,
                durationMs = activeDeck.duration.takeIf { it > 0 } ?: nextSong.durationMs,
                currentPositionMs = activeDeck.currentPosition.coerceAtLeast(0L),
                automix = _playerState.value.automix.copy(
                    currentAnalysis = nextAnalysis,
                    isTransitioning = false,
                    transitionProgress = 0f,
                    activeTempoRatio = 1.0f
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

        inactiveDeck.pause()
        inactiveDeck.volume = 1.0f * duckMultiplier
        inactiveDeck.playbackParameters = PlaybackParameters.DEFAULT

        activeDeck.volume = 1.0f * duckMultiplier
        activeDeck.playbackParameters = PlaybackParameters.DEFAULT

        _playerState.value = _playerState.value.copy(
            automix = _playerState.value.automix.copy(
                isTransitioning = false,
                transitionProgress = 0f,
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

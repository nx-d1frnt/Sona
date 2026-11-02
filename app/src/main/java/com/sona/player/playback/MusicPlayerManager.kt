package com.sona.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.ShuffleMode
import com.sona.player.data.model.Song
import kotlinx.coroutines.flow.StateFlow

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffleEnabled: Boolean = false,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentQueue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val automix: AutomixTransitionState = AutomixTransitionState()
)

class MusicPlayerManager(private val context: Context) {

    private val engine = AutomixPlaybackEngine.getInstance(context.applicationContext)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    val playerState: StateFlow<PlayerState> = engine.playerState

    val libraryAnalysis = com.sona.player.analysis.LibraryAnalysisService.state
    fun startLibraryAnalysis(force: Boolean) = com.sona.player.analysis.LibraryAnalysisService.start(context, force)
    fun cancelLibraryAnalysis() = com.sona.player.analysis.LibraryAnalysisService.cancel(context)

    fun needsLibraryAnalysis(songId: Long) = engine.needsLibraryAnalysis(songId)

    suspend fun analyzeLibrarySong(song: Song, force: Boolean) = engine.analyzeLibrarySong(song, force)

    fun init() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
    }

    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        engine.playQueue(songs, startIndex)
    }

    fun togglePlayPause() = engine.togglePlayPause()

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun skipToNext() = engine.skipToNext()

    fun skipToPrevious() = engine.skipToPrevious()

    fun toggleShuffle() = engine.toggleShuffle()

    fun toggleRepeatMode() = engine.toggleRepeatMode()

    fun toggleAutomix() = engine.toggleAutomix()

    fun skipToQueueIndex(index: Int) = engine.skipToQueueIndex(index)

    fun playNext(song: Song) = engine.playNext(song)

    fun addToQueue(song: Song) = engine.addToQueue(song)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = engine.moveQueueItem(fromIndex, toIndex)

    fun removeFromQueue(index: Int) = engine.removeFromQueue(index)

    fun release() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }
}

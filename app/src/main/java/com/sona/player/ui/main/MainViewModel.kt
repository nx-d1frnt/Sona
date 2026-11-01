package com.sona.player.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sona.player.data.model.Song
import com.sona.player.data.repository.AudioRepository
import com.sona.player.playback.MusicPlayerManager
import com.sona.player.playback.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

data class LibraryAnalysisState(
    val running: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val cached: Int = 0,
    val failed: Int = 0,
    val title: String = "",
    val waitingForPlayback: Boolean = false,
    val cancelled: Boolean = false
)

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Success(val songs: List<Song>) : LibraryUiState
    data object Empty : LibraryUiState
    data object PermissionRequired : LibraryUiState
}

class MainViewModel(
    private val repository: AudioRepository,
    private val playerManager: MusicPlayerManager
) : ViewModel() {

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    private val _rawSongs = MutableStateFlow<List<Song>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    private val _hasPermission = MutableStateFlow(false)

    val uiState: StateFlow<LibraryUiState> = combine(
        _rawSongs,
        _searchQuery,
        _isLoading,
        _hasPermission
    ) { songs, query, loading, hasPermission ->
        when {
            !hasPermission -> LibraryUiState.PermissionRequired
            loading -> LibraryUiState.Loading
            songs.isEmpty() -> LibraryUiState.Empty
            else -> {
                val filtered = if (query.isBlank()) {
                    songs
                } else {
                    songs.filter {
                        it.title.contains(query, ignoreCase = true) ||
                        it.artist.contains(query, ignoreCase = true) ||
                        it.album.contains(query, ignoreCase = true)
                    }
                }
                LibraryUiState.Success(filtered)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState.Loading)

    private val _libraryAnalysis = MutableStateFlow(LibraryAnalysisState())
    val libraryAnalysis = _libraryAnalysis.asStateFlow()
    private var libraryAnalysisJob: kotlinx.coroutines.Job? = null
    val canAnalyze = combine(_rawSongs, _hasPermission, _isLoading) { songs, permission, loading ->
        permission && !loading && songs.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun startLibraryAnalysis(force: Boolean) {
        if (libraryAnalysisJob?.isActive == true || !_hasPermission.value || _isLoading.value) return
        val songs = _rawSongs.value.toList() // Entire library, independent of search.
        if (songs.isEmpty()) return
        _libraryAnalysis.value = LibraryAnalysisState(running = true, total = songs.size)
        libraryAnalysisJob = viewModelScope.launch {
            try {
                for (song in songs) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (!force && !playerManager.needsLibraryAnalysis(song.id)) {
                        _libraryAnalysis.value = _libraryAnalysis.value.let {
                            it.copy(completed = it.completed + 1, cached = it.cached + 1)
                        }
                        continue
                    }
                    _libraryAnalysis.value = _libraryAnalysis.value.copy(title = song.title)
                    // Give playback priority; do not compete with two real-time decoders.
                    while (playerState.value.isPlaying || playerState.value.automix.isTransitioning) {
                        _libraryAnalysis.value = _libraryAnalysis.value.copy(waitingForPlayback = true)
                        kotlinx.coroutines.delay(500)
                    }
                    _libraryAnalysis.value = _libraryAnalysis.value.copy(waitingForPlayback = false)
                    try {
                        playerManager.analyzeLibrarySong(song, force)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        _libraryAnalysis.value = _libraryAnalysis.value.let { it.copy(failed = it.failed + 1) }
                    }
                    _libraryAnalysis.value = _libraryAnalysis.value.let { it.copy(completed = it.completed + 1) }
                    kotlinx.coroutines.yield()
                }
            } finally {
                _libraryAnalysis.value = _libraryAnalysis.value.copy(running = false, waitingForPlayback = false)
            }
        }
    }

    fun cancelLibraryAnalysis() {
        _libraryAnalysis.value = _libraryAnalysis.value.copy(cancelled = true)
        libraryAnalysisJob?.cancel()
    }

    fun onPermissionGranted() {
        _hasPermission.value = true
        loadSongs()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun loadSongs() {
        viewModelScope.launch {
            _isLoading.value = true
            val songs = repository.getSongs()
            _rawSongs.value = songs
            _isLoading.value = false
        }
    }

    fun playSong(song: Song) {
        val currentList = _rawSongs.value
        val index = currentList.indexOfFirst { it.id == song.id }
        if (index != -1) {
            playerManager.playQueue(currentList, index)
        } else {
            playerManager.playQueue(listOf(song), 0)
        }
    }

    fun togglePlayPause() = playerManager.togglePlayPause()
    fun skipToNext() = playerManager.skipToNext()
    fun skipToPrevious() = playerManager.skipToPrevious()
    fun seekTo(positionMs: Long) = playerManager.seekTo(positionMs)
    fun toggleShuffle() = playerManager.toggleShuffle()
    fun toggleRepeat() = playerManager.toggleRepeatMode()
    fun toggleAutomix() = playerManager.toggleAutomix()
    fun skipToQueueIndex(index: Int) = playerManager.skipToQueueIndex(index)
    fun moveQueueItem(fromIndex: Int, toIndex: Int) = playerManager.moveQueueItem(fromIndex, toIndex)
    fun removeFromQueue(index: Int) = playerManager.removeFromQueue(index)
}

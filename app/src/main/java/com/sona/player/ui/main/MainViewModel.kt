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
}

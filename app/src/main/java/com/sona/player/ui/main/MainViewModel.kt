package com.sona.player.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sona.player.automix.AutomixMode
import com.sona.player.automix.AutomixVibe
import com.sona.player.automix.ShuffleMode
import com.sona.player.automix.SmartMixGenerator
import com.sona.player.data.model.Album
import com.sona.player.data.model.Artist
import com.sona.player.data.model.Playlist
import com.sona.player.data.model.Song
import com.sona.player.data.model.SortOrder
import com.sona.player.data.model.TrackAudioDetails
import com.sona.player.data.repository.AudioRepository
import com.sona.player.data.repository.SettingsRepository
import com.sona.player.data.repository.ThemeMode
import com.sona.player.data.repository.UserLibraryRepository
import com.sona.player.data.repository.UserSettings
import com.sona.player.playback.MusicPlayerManager
import com.sona.player.playback.PlayerState
import com.sona.player.ui.components.ArtistAlbumViewTarget
import com.sona.player.ui.components.PlaylistViewTarget
import kotlinx.coroutines.Dispatchers
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

enum class MainNavTab {
    HOME,
    TRACKS,
    PLAYLISTS,
    ARTISTS_ALBUMS
}

class MainViewModel(
    private val repository: AudioRepository,
    private val userLibraryRepository: UserLibraryRepository,
    private val playerManager: MusicPlayerManager,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsRepository.settings

    val playerState: StateFlow<PlayerState> = playerManager.playerState

    private val _rawSongs = MutableStateFlow<List<Song>>(emptyList())
    val rawSongs: StateFlow<List<Song>> = _rawSongs.asStateFlow()

    // Songs filtered by blacklisted folders
    val activeSongs: StateFlow<List<Song>> = combine(_rawSongs, settings) { songs, userSettings ->
        val blacklisted = userSettings.blacklistedFolders
        if (blacklisted.isEmpty()) {
            songs
        } else {
            songs.filter { song ->
                val path = song.filePath
                if (path.isBlank()) true
                else {
                    blacklisted.none { folder -> path == folder || path.startsWith("$folder/") }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.TITLE_ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _selectedTab = MutableStateFlow(MainNavTab.HOME)
    val selectedTab: StateFlow<MainNavTab> = _selectedTab.asStateFlow()

    private val _playlistViewTarget = MutableStateFlow<PlaylistViewTarget>(PlaylistViewTarget.Root)
    val playlistViewTarget: StateFlow<PlaylistViewTarget> = _playlistViewTarget.asStateFlow()

    private val _artistAlbumViewTarget = MutableStateFlow<ArtistAlbumViewTarget>(ArtistAlbumViewTarget.Root)
    val artistAlbumViewTarget: StateFlow<ArtistAlbumViewTarget> = _artistAlbumViewTarget.asStateFlow()

    // Dialog & Sheet States
    private val _trackForOptions = MutableStateFlow<Song?>(null)
    val trackForOptions: StateFlow<Song?> = _trackForOptions.asStateFlow()

    private val _trackDetails = MutableStateFlow<TrackAudioDetails?>(null)
    val trackDetails: StateFlow<TrackAudioDetails?> = _trackDetails.asStateFlow()

    private val _trackForAddToPlaylist = MutableStateFlow<Song?>(null)
    val trackForAddToPlaylist: StateFlow<Song?> = _trackForAddToPlaylist.asStateFlow()

    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog: StateFlow<Boolean> = _showCreatePlaylistDialog.asStateFlow()

    private val _trackToDelete = MutableStateFlow<Song?>(null)
    val trackToDelete: StateFlow<Song?> = _trackToDelete.asStateFlow()

    // Repository flows
    val favorites: StateFlow<Set<Long>> = userLibraryRepository.favorites
    val playlists: StateFlow<List<Playlist>> = userLibraryRepository.playlists
    val playStats: StateFlow<Map<Long, Int>> = userLibraryRepository.playStats

    private val _isLoading = MutableStateFlow(true)
    private val _hasPermission = MutableStateFlow(false)

    // Main sorted and filtered song list
    val uiState: StateFlow<LibraryUiState> = combine(
        activeSongs,
        _searchQuery,
        _sortOrder,
        _isLoading,
        _hasPermission
    ) { songs, query, sort, loading, hasPermission ->
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

                val sorted = when (sort) {
                    SortOrder.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
                    SortOrder.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
                    SortOrder.DATE_ADDED_DESC -> filtered.sortedByDescending { it.dateAdded }
                    SortOrder.DATE_ADDED_ASC -> filtered.sortedBy { it.dateAdded }
                    SortOrder.ARTIST_ASC -> filtered.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
                    SortOrder.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
                    SortOrder.DURATION_ASC -> filtered.sortedBy { it.durationMs }
                }
                LibraryUiState.Success(sorted)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState.Loading)

    // Derived smart collections
    val favoriteSongs: StateFlow<List<Song>> = combine(activeSongs, favorites) { songs, favs ->
        songs.filter { favs.contains(it.id) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyAddedSongs: StateFlow<List<Song>> = combine(activeSongs) { (songs) ->
        songs.sortedByDescending { it.dateAdded }.take(100)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mostPlayedSongs: StateFlow<List<Song>> = combine(activeSongs, playStats) { songs, stats ->
        songs.filter { (stats[it.id] ?: 0) > 0 }
            .sortedByDescending { stats[it.id] ?: 0 }
            .take(100)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val automixVibeSets: StateFlow<Map<AutomixVibe, List<Song>>> = combine(
        activeSongs,
        playerManager.libraryAnalysis,
        playStats
    ) { songs, _, stats ->
        if (songs.isEmpty()) emptyMap()
        else {
            AutomixVibe.entries.associateWith { vibe ->
                SmartMixGenerator.generateVibeMix(
                    vibe = vibe,
                    allSongs = songs,
                    analysisProvider = { playerManager.getCachedAnalysis(it) },
                    playStats = stats
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val albums: StateFlow<List<Album>> = combine(activeSongs) { (songs) ->
        UserLibraryRepository.extractAlbums(songs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<Artist>> = combine(activeSongs, settings) { songs, userSettings ->
        UserLibraryRepository.extractArtists(songs, userSettings.artistSeparators)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryAnalysis = playerManager.libraryAnalysis
    val canAnalyze = combine(_rawSongs, _hasPermission, _isLoading) { songs, permission, loading ->
        permission && !loading && songs.isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun startLibraryAnalysis(force: Boolean) {
        if (_hasPermission.value && !_isLoading.value && _rawSongs.value.isNotEmpty()) {
            playerManager.startLibraryAnalysis(force)
        }
    }
    fun cancelLibraryAnalysis() = playerManager.cancelLibraryAnalysis()

    fun updateTransitionDuration(durationSec: Int) = settingsRepository.updateTransitionDuration(durationSec)
    fun updateHarmonicMixingStrict(enabled: Boolean) = settingsRepository.updateHarmonicMixingStrict(enabled)
    fun updateAvoidVocalClash(enabled: Boolean) = settingsRepository.updateAvoidVocalClash(enabled)
    fun updateEnergyMatching(enabled: Boolean) = settingsRepository.updateEnergyMatching(enabled)
    fun toggleFolderBlacklist(folderPath: String, blacklisted: Boolean) = settingsRepository.setFolderBlacklisted(folderPath, blacklisted)
    fun updateArtistSeparators(separators: List<String>) = settingsRepository.updateArtistSeparators(separators)
    fun updateGaplessPlayback(enabled: Boolean) = settingsRepository.updateGaplessPlayback(enabled)
    fun updateAudioFocusDucking(enabled: Boolean) = settingsRepository.updateAudioFocusDucking(enabled)
    fun updatePauseOnHeadsetDisconnect(enabled: Boolean) = settingsRepository.updatePauseOnHeadsetDisconnect(enabled)
    fun updateThemeMode(mode: ThemeMode) = settingsRepository.updateThemeMode(mode)
    fun updateUseDynamicMonet(enabled: Boolean) = settingsRepository.updateUseDynamicMonet(enabled)
    fun updateUseArtworkPalette(enabled: Boolean) = settingsRepository.updateUseArtworkPalette(enabled)
    fun updateExpressiveAnimations(enabled: Boolean) = settingsRepository.updateExpressiveAnimations(enabled)
    fun resetSettings() = settingsRepository.resetToDefaults()

    fun onPermissionGranted() {
        _hasPermission.value = true
        loadSongs()
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun selectTab(tab: MainNavTab) {
        _selectedTab.value = tab
    }

    fun loadSongs() {
        viewModelScope.launch {
            _isLoading.value = true
            val songs = repository.getSongs()
            _rawSongs.value = songs
            _isLoading.value = false
            userLibraryRepository.refreshAll()
        }
    }

    // Playback Helpers
    fun playSong(song: Song) {
        val currentList = (uiState.value as? LibraryUiState.Success)?.songs ?: _rawSongs.value
        val index = currentList.indexOfFirst { it.id == song.id }
        if (index != -1) {
            playerManager.playQueue(currentList, index)
        } else {
            playerManager.playQueue(listOf(song), 0)
        }
    }

    fun playSongFromList(song: Song, list: List<Song>) {
        val index = list.indexOfFirst { it.id == song.id }
        if (index != -1) {
            playerManager.playQueue(list, index)
        } else {
            playerManager.playQueue(listOf(song), 0)
        }
    }

    fun playAll(songs: List<Song>, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val listToPlay = if (shuffle) songs.shuffled() else songs
        playerManager.playQueue(listToPlay, 0)
    }

    fun playNext(song: Song) {
        playerManager.playNext(song)
    }

    fun addToQueue(song: Song) {
        playerManager.addToQueue(song)
    }

    // Favorites & Playlists
    fun toggleFavorite(songId: Long) {
        viewModelScope.launch {
            userLibraryRepository.toggleFavorite(songId)
        }
    }

    fun isFavorite(songId: Long): Boolean {
        return favorites.value.contains(songId)
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            userLibraryRepository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            userLibraryRepository.deletePlaylist(playlist.id)
            if (_playlistViewTarget.value is PlaylistViewTarget.Custom &&
                (_playlistViewTarget.value as PlaylistViewTarget.Custom).playlist.id == playlist.id) {
                _playlistViewTarget.value = PlaylistViewTarget.Root
            }
        }
    }

    fun addTrackToPlaylist(playlist: Playlist, song: Song) {
        viewModelScope.launch {
            userLibraryRepository.addTrackToPlaylist(playlist.id, song.id)
        }
    }

    fun removeTrackFromPlaylist(playlist: Playlist, song: Song) {
        viewModelScope.launch {
            userLibraryRepository.removeTrackFromPlaylist(playlist.id, song.id)
            // Refresh custom playlist view if currently open
            if (_playlistViewTarget.value is PlaylistViewTarget.Custom &&
                (_playlistViewTarget.value as PlaylistViewTarget.Custom).playlist.id == playlist.id) {
                openCustomPlaylist(playlist)
            }
        }
    }

    // Navigation sub-views
    fun openFavoritesView() {
        _playlistViewTarget.value = PlaylistViewTarget.Favorites(favoriteSongs.value)
    }

    fun openRecentlyAddedView() {
        _playlistViewTarget.value = PlaylistViewTarget.RecentlyAdded(recentlyAddedSongs.value)
    }

    fun openMostPlayedView() {
        _playlistViewTarget.value = PlaylistViewTarget.MostPlayed(mostPlayedSongs.value)
    }

    fun openAutomixSet(vibe: AutomixVibe) {
        val songs = automixVibeSets.value[vibe] ?: SmartMixGenerator.generateVibeMix(
            vibe = vibe,
            allSongs = _rawSongs.value,
            analysisProvider = { playerManager.getCachedAnalysis(it) },
            playStats = playStats.value
        )
        triggerMicroAnalysisIfNeeded(songs.size)
        _playlistViewTarget.value = PlaylistViewTarget.AutomixSet(vibe, songs)
    }

    fun playAutomixSet(vibe: AutomixVibe, songs: List<Song>? = null) {
        val listToPlay = songs ?: automixVibeSets.value[vibe] ?: SmartMixGenerator.generateVibeMix(
            vibe = vibe,
            allSongs = _rawSongs.value,
            analysisProvider = { playerManager.getCachedAnalysis(it) },
            playStats = playStats.value
        )
        if (listToPlay.isEmpty()) return
        triggerMicroAnalysisIfNeeded(listToPlay.size)
        playerManager.setAutomixMode(AutomixMode.AUTO_DJ)
        playerManager.setShuffleMode(ShuffleMode.OFF)
        playerManager.playQueue(listToPlay, 0)
    }

    fun startSmartMix(seedSong: Song) {
        closeTrackOptions()
        viewModelScope.launch {
            val mix = SmartMixGenerator.generateSeedMix(
                seedSong = seedSong,
                allSongs = _rawSongs.value,
                analysisProvider = { playerManager.getCachedAnalysis(it) },
                playStats = playStats.value
            )
            triggerMicroAnalysisIfNeeded(mix.size)
            playerManager.setAutomixMode(AutomixMode.AUTO_DJ)
            playerManager.setShuffleMode(ShuffleMode.OFF)
            playerManager.playQueue(mix, 0)
        }
    }

    fun saveSmartMixAsPlaylist(name: String, smartType: String, seedSongId: Long? = null, songs: List<Song>) {
        viewModelScope.launch {
            userLibraryRepository.createSmartPlaylist(
                name = name,
                smartType = smartType,
                seedSongId = seedSongId,
                initialTrackIds = songs.map { it.id }
            )
        }
    }

    private fun triggerMicroAnalysisIfNeeded(matchCount: Int) {
        if (matchCount >= 15) return
        viewModelScope.launch(Dispatchers.IO) {
            val unanalyzed = _rawSongs.value.filter { playerManager.needsLibraryAnalysis(it.id) }.take(12)
            for (song in unanalyzed) {
                try {
                    playerManager.analyzeLibrarySong(song, force = false)
                } catch (_: Exception) {}
            }
        }
    }

    fun openCustomPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val songs: List<Song>
            if (playlist.isSmart) {
                val regenerated = when {
                    playlist.smartType == "SEED" && playlist.seedSongId != null -> {
                        val seedSong = _rawSongs.value.firstOrNull { it.id == playlist.seedSongId }
                        if (seedSong != null) {
                            SmartMixGenerator.generateSeedMix(
                                seedSong = seedSong,
                                allSongs = _rawSongs.value,
                                analysisProvider = { playerManager.getCachedAnalysis(it) },
                                playStats = playStats.value
                            )
                        } else null
                    }
                    AutomixVibe.fromId(playlist.smartType) != null -> {
                        val vibe = AutomixVibe.fromId(playlist.smartType)!!
                        SmartMixGenerator.generateVibeMix(
                            vibe = vibe,
                            allSongs = _rawSongs.value,
                            analysisProvider = { playerManager.getCachedAnalysis(it) },
                            playStats = playStats.value
                        )
                    }
                    else -> null
                }

                if (!regenerated.isNullOrEmpty()) {
                    songs = regenerated
                    userLibraryRepository.updatePlaylistTracks(playlist.id, songs.map { it.id })
                } else {
                    val trackIds = userLibraryRepository.getPlaylistTrackIds(playlist.id)
                    val idMap = _rawSongs.value.associateBy { it.id }
                    songs = trackIds.mapNotNull { idMap[it] }
                }
            } else {
                val trackIds = userLibraryRepository.getPlaylistTrackIds(playlist.id)
                val idMap = _rawSongs.value.associateBy { it.id }
                songs = trackIds.mapNotNull { idMap[it] }
            }
            _playlistViewTarget.value = PlaylistViewTarget.Custom(playlist, songs)
        }
    }

    fun closePlaylistSubView() {
        _playlistViewTarget.value = PlaylistViewTarget.Root
    }

    fun openAlbumDetail(album: Album) {
        val songs = _rawSongs.value.filter { it.album.equals(album.title, ignoreCase = true) }
            .sortedBy { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
        _artistAlbumViewTarget.value = ArtistAlbumViewTarget.AlbumDetail(album, songs)
    }

    fun openArtistDetail(artist: Artist) {
        val songs = _rawSongs.value.filter { it.artist.equals(artist.name, ignoreCase = true) }
            .sortedWith(compareBy({ it.album.lowercase() }, { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }))
        _artistAlbumViewTarget.value = ArtistAlbumViewTarget.ArtistDetail(artist, songs)
    }

    fun closeArtistAlbumSubView() {
        _artistAlbumViewTarget.value = ArtistAlbumViewTarget.Root
    }

    // Track Options & Details
    fun openTrackOptions(song: Song) {
        _trackForOptions.value = song
    }

    fun closeTrackOptions() {
        _trackForOptions.value = null
    }

    fun showTrackDetails(context: Context, song: Song) {
        viewModelScope.launch {
            val details = TrackAudioDetails.extract(context, song)
            _trackDetails.value = details
        }
    }

    fun closeTrackDetails() {
        _trackDetails.value = null
    }

    fun openAddToPlaylist(song: Song) {
        _trackForAddToPlaylist.value = song
    }

    fun closeAddToPlaylist() {
        _trackForAddToPlaylist.value = null
    }

    fun openCreatePlaylist() {
        _showCreatePlaylistDialog.value = true
    }

    fun closeCreatePlaylist() {
        _showCreatePlaylistDialog.value = false
    }

    fun requestDeleteTrack(song: Song) {
        _trackToDelete.value = song
    }

    fun closeDeleteTrack() {
        _trackToDelete.value = null
    }

    // Transport controls
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

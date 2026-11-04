package com.sona.player.data.repository

import android.content.Context
import com.sona.player.data.database.UserLibraryDatabaseHelper
import com.sona.player.data.model.Album
import com.sona.player.data.model.Artist
import com.sona.player.data.model.Playlist
import com.sona.player.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserLibraryRepository(private val context: Context) {

    private val dbHelper = UserLibraryDatabaseHelper.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _favorites = MutableStateFlow<Set<Long>>(emptySet())
    val favorites: StateFlow<Set<Long>> = _favorites.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _playStats = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val playStats: StateFlow<Map<Long, Int>> = _playStats.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        scope.launch {
            val favs = dbHelper.getFavoriteSongIds()
            val lists = dbHelper.getAllPlaylists()
            val stats = dbHelper.getPlayStats()
            _favorites.value = favs
            _playlists.value = lists
            _playStats.value = stats
        }
    }

    suspend fun toggleFavorite(songId: Long): Boolean = withContext(Dispatchers.IO) {
        val currentFav = dbHelper.isFavorite(songId)
        val newFav = !currentFav
        dbHelper.setFavorite(songId, newFav)
        _favorites.value = dbHelper.getFavoriteSongIds()
        newFav
    }

    suspend fun isFavorite(songId: Long): Boolean = withContext(Dispatchers.IO) {
        dbHelper.isFavorite(songId)
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val id = dbHelper.createPlaylist(name)
        _playlists.value = dbHelper.getAllPlaylists()
        id
    }

    suspend fun createSmartPlaylist(name: String, smartType: String, seedSongId: Long? = null, initialTrackIds: List<Long> = emptyList()): Long = withContext(Dispatchers.IO) {
        val id = dbHelper.createSmartPlaylist(name, smartType, seedSongId)
        if (id > 0 && initialTrackIds.isNotEmpty()) {
            dbHelper.replacePlaylistTracks(id, initialTrackIds)
        }
        _playlists.value = dbHelper.getAllPlaylists()
        id
    }

    suspend fun updatePlaylistTracks(playlistId: Long, trackIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        val success = dbHelper.replacePlaylistTracks(playlistId, trackIds)
        if (success) {
            _playlists.value = dbHelper.getAllPlaylists()
        }
        success
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String): Boolean = withContext(Dispatchers.IO) {
        val success = dbHelper.renamePlaylist(playlistId, newName)
        if (success) {
            _playlists.value = dbHelper.getAllPlaylists()
        }
        success
    }

    suspend fun deletePlaylist(playlistId: Long): Boolean = withContext(Dispatchers.IO) {
        val success = dbHelper.deletePlaylist(playlistId)
        if (success) {
            _playlists.value = dbHelper.getAllPlaylists()
        }
        success
    }

    suspend fun getPlaylistTrackIds(playlistId: Long): List<Long> = withContext(Dispatchers.IO) {
        dbHelper.getPlaylistTrackIds(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, songId: Long): Boolean = withContext(Dispatchers.IO) {
        val success = dbHelper.addTrackToPlaylist(playlistId, songId)
        if (success) {
            _playlists.value = dbHelper.getAllPlaylists()
        }
        success
    }

    suspend fun removeTrackFromPlaylist(playlistId: Long, songId: Long): Boolean = withContext(Dispatchers.IO) {
        val success = dbHelper.removeTrackFromPlaylist(playlistId, songId)
        if (success) {
            _playlists.value = dbHelper.getAllPlaylists()
        }
        success
    }

    suspend fun recordPlay(songId: Long) = withContext(Dispatchers.IO) {
        dbHelper.recordPlay(songId)
        _playStats.value = dbHelper.getPlayStats()
    }

    suspend fun getTopPlayedSongIds(limit: Int = 50): List<Long> = withContext(Dispatchers.IO) {
        dbHelper.getTopPlayedSongIds(limit)
    }

    companion object {
        fun extractAlbums(songs: List<Song>): List<Album> {
            return songs.groupBy { it.album }
                .map { (_, albumSongs) ->
                    val first = albumSongs.first()
                    Album(
                        id = albumSongs.firstOrNull { it.id > 0 }?.id ?: 0L,
                        title = first.album,
                        artist = first.artist,
                        songCount = albumSongs.size,
                        year = albumSongs.maxOfOrNull { it.year } ?: 0,
                        albumArtUri = albumSongs.firstNotNullOfOrNull { it.albumArtUri }
                    )
                }
                .sortedBy { it.title.lowercase() }
        }

        fun extractArtists(songs: List<Song>): List<Artist> {
            return songs.groupBy { it.artist }
                .map { (artistName, artistSongs) ->
                    val albumCount = artistSongs.map { it.album }.distinct().size
                    Artist(
                        name = artistName,
                        songCount = artistSongs.size,
                        albumCount = albumCount
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
    }
}

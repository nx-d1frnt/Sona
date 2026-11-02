package com.sona.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sona.player.R
import com.sona.player.data.model.Playlist
import com.sona.player.data.model.Song

sealed interface PlaylistViewTarget {
    data object Root : PlaylistViewTarget
    data class Favorites(val songs: List<Song>) : PlaylistViewTarget
    data class RecentlyAdded(val songs: List<Song>) : PlaylistViewTarget
    data class MostPlayed(val songs: List<Song>) : PlaylistViewTarget
    data class Custom(val playlist: Playlist, val songs: List<Song>) : PlaylistViewTarget
}

@Composable
fun PlaylistsTabScreen(
    currentView: PlaylistViewTarget,
    playlists: List<Playlist>,
    favoriteSongs: List<Song>,
    recentlyAddedSongs: List<Song>,
    mostPlayedSongs: List<Song>,
    currentPlayingSongId: Long?,
    isPlaying: Boolean,
    onOpenFavorites: () -> Unit,
    onOpenRecentlyAdded: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenCustomPlaylist: (Playlist) -> Unit,
    onBackToRoot: () -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onPlaySong: (Song, List<Song>) -> Unit,
    onPlayAll: (List<Song>) -> Unit,
    onShuffleAll: (List<Song>) -> Unit,
    onTrackOptionsClick: (Song) -> Unit,
    onRemoveFromPlaylist: ((Playlist, Song) -> Unit)? = null
) {
    when (currentView) {
        is PlaylistViewTarget.Root -> {
            PlaylistsRootView(
                playlists = playlists,
                favoritesCount = favoriteSongs.size,
                recentlyAddedCount = recentlyAddedSongs.size,
                mostPlayedCount = mostPlayedSongs.size,
                onOpenFavorites = onOpenFavorites,
                onOpenRecentlyAdded = onOpenRecentlyAdded,
                onOpenMostPlayed = onOpenMostPlayed,
                onOpenCustomPlaylist = onOpenCustomPlaylist,
                onCreatePlaylistClick = onCreatePlaylistClick,
                onDeletePlaylist = onDeletePlaylist
            )
        }
        is PlaylistViewTarget.Favorites -> {
            PlaylistDetailView(
                title = stringResource(R.string.smart_playlist_favorites),
                subtitle = stringResource(R.string.smart_playlist_favorites_subtitle, currentView.songs.size),
                icon = Icons.Rounded.Favorite,
                iconColor = MaterialTheme.colorScheme.error,
                songs = currentView.songs,
                currentPlayingSongId = currentPlayingSongId,
                isPlaying = isPlaying,
                onBack = onBackToRoot,
                onPlayAll = { onPlayAll(currentView.songs) },
                onShuffleAll = { onShuffleAll(currentView.songs) },
                onPlaySong = { song -> onPlaySong(song, currentView.songs) },
                onTrackOptionsClick = onTrackOptionsClick
            )
        }
        is PlaylistViewTarget.RecentlyAdded -> {
            PlaylistDetailView(
                title = stringResource(R.string.smart_playlist_recently_added),
                subtitle = stringResource(R.string.smart_playlist_recently_added_subtitle, currentView.songs.size),
                icon = Icons.Rounded.Update,
                iconColor = MaterialTheme.colorScheme.primary,
                songs = currentView.songs,
                currentPlayingSongId = currentPlayingSongId,
                isPlaying = isPlaying,
                onBack = onBackToRoot,
                onPlayAll = { onPlayAll(currentView.songs) },
                onShuffleAll = { onShuffleAll(currentView.songs) },
                onPlaySong = { song -> onPlaySong(song, currentView.songs) },
                onTrackOptionsClick = onTrackOptionsClick
            )
        }
        is PlaylistViewTarget.MostPlayed -> {
            PlaylistDetailView(
                title = stringResource(R.string.smart_playlist_most_played),
                subtitle = stringResource(R.string.smart_playlist_most_played_subtitle, currentView.songs.size),
                icon = Icons.Rounded.LocalFireDepartment,
                iconColor = MaterialTheme.colorScheme.secondary,
                songs = currentView.songs,
                currentPlayingSongId = currentPlayingSongId,
                isPlaying = isPlaying,
                onBack = onBackToRoot,
                onPlayAll = { onPlayAll(currentView.songs) },
                onShuffleAll = { onShuffleAll(currentView.songs) },
                onPlaySong = { song -> onPlaySong(song, currentView.songs) },
                onTrackOptionsClick = onTrackOptionsClick
            )
        }
        is PlaylistViewTarget.Custom -> {
            PlaylistDetailView(
                title = currentView.playlist.name,
                subtitle = stringResource(R.string.playlist_tracks_count, currentView.songs.size),
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                iconColor = MaterialTheme.colorScheme.tertiary,
                songs = currentView.songs,
                currentPlayingSongId = currentPlayingSongId,
                isPlaying = isPlaying,
                onBack = onBackToRoot,
                onPlayAll = { onPlayAll(currentView.songs) },
                onShuffleAll = { onShuffleAll(currentView.songs) },
                onPlaySong = { song -> onPlaySong(song, currentView.songs) },
                onTrackOptionsClick = onTrackOptionsClick,
                onRemoveTrack = onRemoveFromPlaylist?.let { remover ->
                    { song -> remover(currentView.playlist, song) }
                }
            )
        }
    }
}

@Composable
private fun PlaylistsRootView(
    playlists: List<Playlist>,
    favoritesCount: Int,
    recentlyAddedCount: Int,
    mostPlayedCount: Int,
    onOpenFavorites: () -> Unit,
    onOpenRecentlyAdded: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenCustomPlaylist: (Playlist) -> Unit,
    onCreatePlaylistClick: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.playlists_smart_section),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        item {
            SmartPlaylistCard(
                title = stringResource(R.string.smart_playlist_favorites),
                subtitle = stringResource(R.string.smart_playlist_favorites_count, favoritesCount),
                icon = Icons.Rounded.Favorite,
                iconColor = MaterialTheme.colorScheme.error,
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                onClick = onOpenFavorites
            )
        }

        item {
            SmartPlaylistCard(
                title = stringResource(R.string.smart_playlist_recently_added),
                subtitle = stringResource(R.string.smart_playlist_recently_added_count, recentlyAddedCount),
                icon = Icons.Rounded.Update,
                iconColor = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                onClick = onOpenRecentlyAdded
            )
        }

        item {
            SmartPlaylistCard(
                title = stringResource(R.string.smart_playlist_most_played),
                subtitle = stringResource(R.string.smart_playlist_most_played_count, mostPlayedCount),
                icon = Icons.Rounded.LocalFireDepartment,
                iconColor = MaterialTheme.colorScheme.secondary,
                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                onClick = onOpenMostPlayed
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.playlists_my_section),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                FilledTonalButton(
                    onClick = onCreatePlaylistClick,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.action_create))
                }
            }
        }

        if (playlists.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.playlists_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                var menuExpanded by remember { mutableStateOf(false) }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onOpenCustomPlaylist(playlist) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.playlist_tracks_count, playlist.trackCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.action_more_options)
                                )
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.playlist_delete)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onDeletePlaylist(playlist)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartPlaylistCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    containerColor: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.25f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PlaylistDetailView(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconColor: Color,
    songs: List<Song>,
    currentPlayingSongId: Long?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onTrackOptionsClick: (Song) -> Unit,
    onRemoveTrack: ((Song) -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top navigation bar with Back button and playlist info
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Action Buttons: Play All & Shuffle
        if (songs.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_play))
                }

                FilledTonalButton(
                    onClick = onShuffleAll,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Rounded.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_shuffle))
                }
            }
        }

        // Songs list
        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.playlist_is_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(songs, key = { it.id }) { song ->
                    com.sona.player.ui.main.SongListItem(
                        song = song,
                        isSelected = currentPlayingSongId == song.id,
                        isPlaying = isPlaying && currentPlayingSongId == song.id,
                        onClick = { onPlaySong(song) },
                        onOptionsClick = { onTrackOptionsClick(song) }
                    )
                }
            }
        }
    }
}

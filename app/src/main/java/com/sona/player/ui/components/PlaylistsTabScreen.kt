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
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ElectricBolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Nightlife
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Spa
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sona.player.R
import com.sona.player.automix.AutomixVibe
import com.sona.player.data.model.Playlist
import com.sona.player.data.model.Song

sealed interface PlaylistViewTarget {
    data object Root : PlaylistViewTarget
    data class Favorites(val songs: List<Song>) : PlaylistViewTarget
    data class RecentlyAdded(val songs: List<Song>) : PlaylistViewTarget
    data class MostPlayed(val songs: List<Song>) : PlaylistViewTarget
    data class AutomixSet(val vibe: AutomixVibe, val songs: List<Song>) : PlaylistViewTarget
    data class Custom(val playlist: Playlist, val songs: List<Song>) : PlaylistViewTarget
}

@Composable
fun PlaylistsTabScreen(
    currentView: PlaylistViewTarget,
    playlists: List<Playlist>,
    favoriteSongs: List<Song>,
    recentlyAddedSongs: List<Song>,
    mostPlayedSongs: List<Song>,
    automixSets: Map<AutomixVibe, List<Song>> = emptyMap(),
    currentPlayingSongId: Long?,
    isPlaying: Boolean,
    onOpenFavorites: () -> Unit,
    onOpenRecentlyAdded: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenAutomixSet: (AutomixVibe) -> Unit = {},
    onPlayAutomixSet: (AutomixVibe, List<Song>) -> Unit = { _, _ -> },
    onSaveAutomixSetAsPlaylist: (AutomixVibe, List<Song>) -> Unit = { _, _ -> },
    onRefreshAutomixSet: (AutomixVibe) -> Unit = {},
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
                automixSets = automixSets,
                onOpenFavorites = onOpenFavorites,
                onOpenRecentlyAdded = onOpenRecentlyAdded,
                onOpenMostPlayed = onOpenMostPlayed,
                onOpenAutomixSet = onOpenAutomixSet,
                onPlayAutomixSet = { vibe ->
                    val songs = automixSets[vibe] ?: emptyList()
                    onPlayAutomixSet(vibe, songs)
                },
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
                iconColor = MaterialTheme.colorScheme.tertiary,
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
        is PlaylistViewTarget.AutomixSet -> {
            val vibe = currentView.vibe
            val (icon, iconColor) = when (vibe) {
                AutomixVibe.HIGH_ENERGY -> Icons.Rounded.ElectricBolt to MaterialTheme.colorScheme.tertiary
                AutomixVibe.CHILL -> Icons.Rounded.Spa to MaterialTheme.colorScheme.secondary
                AutomixVibe.INSTRUMENTAL -> Icons.Rounded.GraphicEq to MaterialTheme.colorScheme.primary
                AutomixVibe.NIGHT_DRIVE -> Icons.Rounded.Nightlife to MaterialTheme.colorScheme.primary
            }
            PlaylistDetailView(
                title = stringResource(vibe.titleRes),
                subtitle = stringResource(vibe.subtitleRes),
                icon = icon,
                iconColor = iconColor,
                songs = currentView.songs,
                currentPlayingSongId = currentPlayingSongId,
                isPlaying = isPlaying,
                onBack = onBackToRoot,
                onPlayAll = { onPlayAutomixSet(vibe, currentView.songs) },
                onShuffleAll = { onPlayAutomixSet(vibe, currentView.songs) },
                onPlaySong = { song -> onPlaySong(song, currentView.songs) },
                onTrackOptionsClick = onTrackOptionsClick,
                isSmart = true,
                onSaveAsPlaylist = { onSaveAutomixSetAsPlaylist(vibe, currentView.songs) },
                onRefreshMix = { onRefreshAutomixSet(vibe) }
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
    automixSets: Map<AutomixVibe, List<Song>> = emptyMap(),
    onOpenFavorites: () -> Unit,
    onOpenRecentlyAdded: () -> Unit,
    onOpenMostPlayed: () -> Unit,
    onOpenAutomixSet: (AutomixVibe) -> Unit = {},
    onPlayAutomixSet: (AutomixVibe) -> Unit = {},
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
                iconColor = MaterialTheme.colorScheme.tertiary,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f),
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

        // Dedicated Automix Sets Section & Carousel
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.playlists_automix_sets_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.playlists_automix_sets_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            AutomixSetsCarousel(
                automixSets = automixSets,
                onOpenAutomixSet = onOpenAutomixSet,
                onPlayAutomixSet = onPlayAutomixSet
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
                            color = if (playlist.isSmart) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (playlist.isSmart) Icons.Rounded.AutoAwesome else Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = if (playlist.isSmart) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (playlist.isSmart) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AutoAwesome,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = stringResource(R.string.smart_badge),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
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
    onRemoveTrack: ((Song) -> Unit)? = null,
    isSmart: Boolean = false,
    onSaveAsPlaylist: (() -> Unit)? = null,
    onRefreshMix: (() -> Unit)? = null
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSmart) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = stringResource(R.string.smart_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (onRefreshMix != null) {
                IconButton(onClick = onRefreshMix) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.automix_refresh_action),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (onSaveAsPlaylist != null) {
                IconButton(onClick = onSaveAsPlaylist) {
                    Icon(
                        imageVector = Icons.Rounded.BookmarkAdd,
                        contentDescription = stringResource(R.string.smart_mix_save_action),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Action Buttons: Play All & Shuffle / Save
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
                    Icon(
                        imageVector = if (isSmart) Icons.Rounded.AutoAwesome else Icons.Rounded.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isSmart) stringResource(R.string.home_quick_mix) else stringResource(R.string.action_play))
                }

                if (isSmart && onSaveAsPlaylist != null) {
                    FilledTonalButton(
                        onClick = onSaveAsPlaylist,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(imageVector = Icons.Rounded.BookmarkAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.action_save))
                    }
                } else {
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

@Composable
private fun AutomixSetsCarousel(
    automixSets: Map<AutomixVibe, List<Song>>,
    onOpenAutomixSet: (AutomixVibe) -> Unit,
    onPlayAutomixSet: (AutomixVibe) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(AutomixVibe.entries.toTypedArray(), key = { it.id }) { vibe ->
            val songs = automixSets[vibe] ?: emptyList()
            AutomixSetCard(
                vibe = vibe,
                trackCount = songs.size,
                onClick = { onOpenAutomixSet(vibe) },
                onPlay = { onPlayAutomixSet(vibe) }
            )
        }
    }
}

@Composable
private fun AutomixSetCard(
    vibe: AutomixVibe,
    trackCount: Int,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    val (gradientColors, icon, accentColor) = when (vibe) {
        AutomixVibe.HIGH_ENERGY -> Triple(
            listOf(Color(0xFF381207), Color(0xFF1F0D09)),
            Icons.Rounded.ElectricBolt,
            Color(0xFFFF7043)
        )
        AutomixVibe.CHILL -> Triple(
            listOf(Color(0xFF072728), Color(0xFF0A191C)),
            Icons.Rounded.Spa,
            Color(0xFF26A69A)
        )
        AutomixVibe.INSTRUMENTAL -> Triple(
            listOf(Color(0xFF221133), Color(0xFF140D24)),
            Icons.Rounded.GraphicEq,
            Color(0xFFBA68C8)
        )
        AutomixVibe.NIGHT_DRIVE -> Triple(
            listOf(Color(0xFF0C192E), Color(0xFF0A101C)),
            Icons.Rounded.Nightlife,
            Color(0xFF42A5F5)
        )
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier
            .width(176.dp)
            .height(184.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(gradientColors))
                .padding(14.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = accentColor.copy(alpha = 0.20f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = accentColor.copy(alpha = 0.25f),
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onPlay)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = stringResource(R.string.action_play),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = stringResource(vibe.titleRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(vibe.subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = stringResource(R.string.playlist_tracks_count, trackCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color.White.copy(alpha = 0.90f),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.22f)
                    ) {
                        Text(
                            text = stringResource(R.string.automix_daily_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

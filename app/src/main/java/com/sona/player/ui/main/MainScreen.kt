package com.sona.player.ui.main

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sona.player.R
import com.sona.player.data.model.Song
import com.sona.player.data.model.SortOrder
import com.sona.player.ui.components.AddToPlaylistDialog
import com.sona.player.ui.components.ArtistAlbumViewTarget
import com.sona.player.ui.components.ArtistsAlbumsTabScreen
import com.sona.player.ui.components.ConfirmDeleteDialog
import com.sona.player.ui.components.CreatePlaylistDialog
import com.sona.player.ui.components.HomeScreen
import com.sona.player.ui.components.MiniPlayer
import com.sona.player.ui.components.NowPlayingScreen
import com.sona.player.ui.components.PlaylistViewTarget
import com.sona.player.ui.components.PlaylistsTabScreen
import com.sona.player.ui.components.TrackDetailsDialog
import com.sona.player.ui.components.TrackOptionsMenuSheet
import com.sona.player.ui.settings.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit,
    onDeleteSong: (Song) -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val useNavRail = configuration.screenWidthDp >= 600

    val uiState by viewModel.uiState.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val playlistViewTarget by viewModel.playlistViewTarget.collectAsState()
    val artistAlbumViewTarget by viewModel.artistAlbumViewTarget.collectAsState()

    val analysis by viewModel.libraryAnalysis.collectAsState()
    val canAnalyze by viewModel.canAnalyze.collectAsState()

    val favorites by viewModel.favorites.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val favoriteSongs by viewModel.favoriteSongs.collectAsState()
    val recentlyAddedSongs by viewModel.recentlyAddedSongs.collectAsState()
    val mostPlayedSongs by viewModel.mostPlayedSongs.collectAsState()
    val automixSets by viewModel.automixVibeSets.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val rawSongs by viewModel.rawSongs.collectAsState()

    // Dialog & Options states
    val trackForOptions by viewModel.trackForOptions.collectAsState()
    val trackDetails by viewModel.trackDetails.collectAsState()
    val trackForAddToPlaylist by viewModel.trackForAddToPlaylist.collectAsState()
    val showCreatePlaylistDialog by viewModel.showCreatePlaylistDialog.collectAsState()
    val trackToDelete by viewModel.trackToDelete.collectAsState()

    var settingsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var forceAnalysis by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var isNowPlayingExpanded by remember { mutableStateOf(false) }

    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { viewModel.startLibraryAnalysis(forceAnalysis) }

    fun shareSong(song: Song) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, song.contentUri)
            putExtra(Intent.EXTRA_SUBJECT, song.title)
            putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.artist}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_track_title)))
    }

    BackHandler(enabled = isNowPlayingExpanded || settingsOpen || playlistViewTarget !is PlaylistViewTarget.Root || artistAlbumViewTarget !is ArtistAlbumViewTarget.Root || selectedTab != MainNavTab.HOME) {
        when {
            isNowPlayingExpanded -> isNowPlayingExpanded = false
            settingsOpen -> settingsOpen = false
            selectedTab == MainNavTab.PLAYLISTS && playlistViewTarget !is PlaylistViewTarget.Root -> {
                viewModel.closePlaylistSubView()
            }
            selectedTab == MainNavTab.ARTISTS_ALBUMS && artistAlbumViewTarget !is ArtistAlbumViewTarget.Root -> {
                viewModel.closeArtistAlbumSubView()
            }
            selectedTab != MainNavTab.HOME -> {
                viewModel.selectTab(MainNavTab.HOME)
            }
        }
    }

    Scaffold(
        topBar = {
            if (!settingsOpen) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        if (selectedTab == MainNavTab.TRACKS) {
                            Box {
                                IconButton(onClick = { sortMenuOpen = true }) {
                                    Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.action_sort))
                                }

                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false }
                                ) {
                                    SortOrder.values().forEach { order ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(order.displayNameRes)) },
                                            trailingIcon = {
                                                if (order == sortOrder) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            },
                                            onClick = {
                                                viewModel.setSortOrder(order)
                                                sortMenuOpen = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        IconButton(onClick = { settingsOpen = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.action_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val progress = if (playerState.durationMs > 0) {
                    playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()
                } else {
                    0f
                }

                MiniPlayer(
                    song = playerState.currentSong,
                    isPlaying = playerState.isPlaying,
                    progress = progress,
                    automixState = playerState.automix,
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onNextClick = { viewModel.skipToNext() },
                    onClick = { isNowPlayingExpanded = true }
                )

                if (!settingsOpen && !useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == MainNavTab.HOME,
                            onClick = { viewModel.selectTab(MainNavTab.HOME) },
                            icon = { Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.tab_home)) },
                            label = { Text(stringResource(R.string.tab_home)) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainNavTab.TRACKS,
                            onClick = { viewModel.selectTab(MainNavTab.TRACKS) },
                            icon = { Icon(Icons.Rounded.MusicNote, contentDescription = stringResource(R.string.tab_tracks)) },
                            label = { Text(stringResource(R.string.tab_tracks)) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainNavTab.PLAYLISTS,
                            onClick = { viewModel.selectTab(MainNavTab.PLAYLISTS) },
                            icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.tab_playlists)) },
                            label = { Text(stringResource(R.string.tab_playlists)) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainNavTab.ARTISTS_ALBUMS,
                            onClick = { viewModel.selectTab(MainNavTab.ARTISTS_ALBUMS) },
                            icon = { Icon(Icons.Rounded.Album, contentDescription = stringResource(R.string.tab_albums)) },
                            label = { Text(stringResource(R.string.tab_albums)) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (useNavRail && !settingsOpen) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    NavigationRailItem(
                        selected = selectedTab == MainNavTab.HOME,
                        onClick = { viewModel.selectTab(MainNavTab.HOME) },
                        icon = { Icon(Icons.Rounded.Home, contentDescription = stringResource(R.string.tab_home)) },
                        label = { Text(stringResource(R.string.tab_home)) }
                    )
                    NavigationRailItem(
                        selected = selectedTab == MainNavTab.TRACKS,
                        onClick = { viewModel.selectTab(MainNavTab.TRACKS) },
                        icon = { Icon(Icons.Rounded.MusicNote, contentDescription = stringResource(R.string.tab_tracks)) },
                        label = { Text(stringResource(R.string.tab_tracks)) }
                    )
                    NavigationRailItem(
                        selected = selectedTab == MainNavTab.PLAYLISTS,
                        onClick = { viewModel.selectTab(MainNavTab.PLAYLISTS) },
                        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = stringResource(R.string.tab_playlists)) },
                        label = { Text(stringResource(R.string.tab_playlists)) }
                    )
                    NavigationRailItem(
                        selected = selectedTab == MainNavTab.ARTISTS_ALBUMS,
                        onClick = { viewModel.selectTab(MainNavTab.ARTISTS_ALBUMS) },
                        icon = { Icon(Icons.Rounded.Album, contentDescription = stringResource(R.string.tab_albums)) },
                        label = { Text(stringResource(R.string.tab_albums)) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
            if (settingsOpen) {
                SettingsScreen(
                    settings = settings,
                    allSongs = rawSongs,
                    analysisState = analysis,
                    canAnalyze = canAnalyze,
                    onClose = { settingsOpen = false },
                    onUpdateTransitionDuration = { viewModel.updateTransitionDuration(it) },
                    onUpdateHarmonicMixingStrict = { viewModel.updateHarmonicMixingStrict(it) },
                    onUpdateAvoidVocalClash = { viewModel.updateAvoidVocalClash(it) },
                    onUpdateEnergyMatching = { viewModel.updateEnergyMatching(it) },
                    onToggleFolderBlacklist = { path, blacklisted -> viewModel.toggleFolderBlacklist(path, blacklisted) },
                    onUpdateArtistSeparators = { viewModel.updateArtistSeparators(it) },
                    onUpdateGaplessPlayback = { viewModel.updateGaplessPlayback(it) },
                    onUpdateAudioFocusDucking = { viewModel.updateAudioFocusDucking(it) },
                    onUpdatePauseOnHeadsetDisconnect = { viewModel.updatePauseOnHeadsetDisconnect(it) },
                    onUpdateThemeMode = { viewModel.updateThemeMode(it) },
                    onUpdateUseDynamicMonet = { viewModel.updateUseDynamicMonet(it) },
                    onUpdateUseArtworkPalette = { viewModel.updateUseArtworkPalette(it) },
                    onUpdateExpressiveAnimations = { viewModel.updateExpressiveAnimations(it) },
                    onResetSettings = { viewModel.resetSettings() },
                    onStartAnalysis = { force -> viewModel.startLibraryAnalysis(force) },
                    onCancelAnalysis = { viewModel.cancelLibraryAnalysis() },
                    context = context,
                    notificationPermissionLauncher = notificationPermission
                )
            } else {
                when (selectedTab) {
                MainNavTab.HOME -> {
                    HomeScreen(
                        allSongs = (uiState as? LibraryUiState.Success)?.songs ?: viewModel.rawSongs.collectAsState().value,
                        currentSong = playerState.currentSong,
                        isPlaying = playerState.isPlaying,
                        favoriteSongs = favoriteSongs,
                        recentlyAddedSongs = recentlyAddedSongs,
                        mostPlayedSongs = mostPlayedSongs,
                        albums = albums,
                        artists = artists,
                        isAutomixEnabled = playerState.automix.isEnabled,
                        onPlaySong = { viewModel.playSong(it) },
                        onPlayAll = { songs, shuffle -> viewModel.playAll(songs, shuffle) },
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onToggleAutomix = { viewModel.toggleAutomix() },
                        onOpenTrackOptions = { viewModel.openTrackOptions(it) },
                        onOpenAlbum = { album ->
                            viewModel.openAlbumDetail(album)
                            viewModel.selectTab(MainNavTab.ARTISTS_ALBUMS)
                        },
                        onOpenArtist = { artist ->
                            viewModel.openArtistDetail(artist)
                            viewModel.selectTab(MainNavTab.ARTISTS_ALBUMS)
                        },
                        onNavigateToTab = { viewModel.selectTab(it) }
                    )
                }
                MainNavTab.TRACKS -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = viewModel::onSearchQueryChanged,
                            placeholder = { Text(stringResource(R.string.search_placeholder)) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Rounded.Search, contentDescription = stringResource(R.string.action_search))
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                        Icon(imageVector = Icons.Rounded.Clear, contentDescription = stringResource(R.string.action_clear))
                                    }
                                }
                            },
                            shape = CircleShape,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )

                        // Content body
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f)
                        ) {
                            when (val state = uiState) {
                                is LibraryUiState.PermissionRequired -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = stringResource(R.string.permission_required_message),
                                                style = MaterialTheme.typography.bodyLarge,
                                                modifier = Modifier.padding(bottom = 16.dp)
                                            )
                                            Button(onClick = onRequestPermission) {
                                                Text(stringResource(R.string.permission_grant_button))
                                            }
                                        }
                                    }
                                }
                                is LibraryUiState.Loading -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                                is LibraryUiState.Empty -> {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.no_audio_files_found),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                is LibraryUiState.Success -> {
                                    LazyColumn(
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                    ) {
                                        items(
                                            items = state.songs,
                                            key = { it.id }
                                        ) { song ->
                                            SongListItem(
                                                song = song,
                                                isSelected = playerState.currentSong?.id == song.id,
                                                isPlaying = playerState.isPlaying && playerState.currentSong?.id == song.id,
                                                onClick = { viewModel.playSong(song) },
                                                onOptionsClick = { viewModel.openTrackOptions(song) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                MainNavTab.PLAYLISTS -> {
                    PlaylistsTabScreen(
                        currentView = playlistViewTarget,
                        playlists = playlists,
                        favoriteSongs = favoriteSongs,
                        recentlyAddedSongs = recentlyAddedSongs,
                        mostPlayedSongs = mostPlayedSongs,
                        automixSets = automixSets,
                        currentPlayingSongId = playerState.currentSong?.id,
                        isPlaying = playerState.isPlaying,
                        onOpenFavorites = { viewModel.openFavoritesView() },
                        onOpenRecentlyAdded = { viewModel.openRecentlyAddedView() },
                        onOpenMostPlayed = { viewModel.openMostPlayedView() },
                        onOpenAutomixSet = { viewModel.openAutomixSet(it) },
                        onPlayAutomixSet = { vibe, songs -> viewModel.playAutomixSet(vibe, songs) },
                        onSaveAutomixSetAsPlaylist = { vibe, songs ->
                            val defaultName = context.getString(vibe.titleRes)
                            viewModel.saveSmartMixAsPlaylist(
                                name = defaultName,
                                smartType = vibe.id,
                                songs = songs
                            )
                            android.widget.Toast.makeText(context, R.string.smart_mix_saved_toast, android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onOpenCustomPlaylist = { viewModel.openCustomPlaylist(it) },
                        onBackToRoot = { viewModel.closePlaylistSubView() },
                        onCreatePlaylistClick = { viewModel.openCreatePlaylist() },
                        onDeletePlaylist = { viewModel.deletePlaylist(it) },
                        onPlaySong = { song, list -> viewModel.playSongFromList(song, list) },
                        onPlayAll = { list -> viewModel.playAll(list, shuffle = false) },
                        onShuffleAll = { list -> viewModel.playAll(list, shuffle = true) },
                        onTrackOptionsClick = { viewModel.openTrackOptions(it) },
                        onRemoveFromPlaylist = { playlist, song -> viewModel.removeTrackFromPlaylist(playlist, song) }
                    )
                }
                MainNavTab.ARTISTS_ALBUMS -> {
                    ArtistsAlbumsTabScreen(
                        currentView = artistAlbumViewTarget,
                        albums = albums,
                        artists = artists,
                        currentPlayingSongId = playerState.currentSong?.id,
                        isPlaying = playerState.isPlaying,
                        onSelectAlbum = { viewModel.openAlbumDetail(it) },
                        onSelectArtist = { viewModel.openArtistDetail(it) },
                        onBackToRoot = { viewModel.closeArtistAlbumSubView() },
                        onPlaySong = { song, list -> viewModel.playSongFromList(song, list) },
                        onPlayAll = { list -> viewModel.playAll(list, shuffle = false) },
                        onShuffleAll = { list -> viewModel.playAll(list, shuffle = true) },
                        onTrackOptionsClick = { viewModel.openTrackOptions(it) }
                    )
                }
            }
        }
    }
}
}

    // Modal Bottom Sheet: Track Options
    trackForOptions?.let { song ->
        TrackOptionsMenuSheet(
            song = song,
            isFavorite = favorites.contains(song.id),
            onDismiss = { viewModel.closeTrackOptions() },
            onStartSmartMix = { viewModel.startSmartMix(song) },
            onPlayNext = { viewModel.playNext(song) },
            onAddToQueue = { viewModel.addToQueue(song) },
            onToggleFavorite = { viewModel.toggleFavorite(song.id) },
            onAddToPlaylist = { viewModel.openAddToPlaylist(song) },
            onShowDetails = { viewModel.showTrackDetails(context, song) },
            onShare = { shareSong(song) },
            onDelete = { viewModel.requestDeleteTrack(song) }
        )
    }

    // Dialog: Track Technical Details
    trackDetails?.let { details ->
        val automix = playerState.automix.analyzedTracks[details.songId]
            ?: playerState.automix.currentAnalysis?.takeIf { it.songId == details.songId }
        TrackDetailsDialog(
            details = details,
            automixAnalysis = automix,
            onDismiss = { viewModel.closeTrackDetails() }
        )
    }

    // Dialog: Add to Playlist
    trackForAddToPlaylist?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = playlists,
            onSelectPlaylist = { playlist -> viewModel.addTrackToPlaylist(playlist, song) },
            onCreateNewPlaylistClick = { viewModel.openCreatePlaylist() },
            onDismiss = { viewModel.closeAddToPlaylist() }
        )
    }

    // Dialog: Create Playlist
    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onConfirm = { name -> viewModel.createPlaylist(name) },
            onDismiss = { viewModel.closeCreatePlaylist() }
        )
    }

    // Dialog: Confirm Track Delete
    trackToDelete?.let { song ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.delete_track_dialog_title),
            message = stringResource(R.string.delete_track_dialog_message, song.title),
            onConfirm = {
                onDeleteSong(song)
                viewModel.closeDeleteTrack()
            },
            onDismiss = { viewModel.closeDeleteTrack() }
        )
    }

    // Full Screen Now Playing Overlay
    AnimatedVisibility(
        visible = isNowPlayingExpanded,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val currentSong = playerState.currentSong
        NowPlayingScreen(
            song = currentSong,
            isPlaying = playerState.isPlaying,
            currentPositionMs = playerState.currentPositionMs,
            durationMs = playerState.durationMs,
            isShuffle = playerState.isShuffleEnabled,
            shuffleMode = playerState.shuffleMode,
            repeatMode = playerState.repeatMode,
            queue = playerState.currentQueue,
            currentIndex = playerState.currentIndex,
            automixState = playerState.automix,
            smartQueueUpdatedAtMs = playerState.smartQueueUpdatedAtMs,
            isFavorite = currentSong != null && favorites.contains(currentSong.id),
            onToggleFavorite = { currentSong?.let { viewModel.toggleFavorite(it.id) } },
            onMoreClick = { currentSong?.let { viewModel.openTrackOptions(it) } },
            onShareClick = { currentSong?.let { shareSong(it) } },
            onPlayPauseClick = { viewModel.togglePlayPause() },
            onNextClick = { viewModel.skipToNext() },
            onPreviousClick = { viewModel.skipToPrevious() },
            onShuffleClick = { viewModel.toggleShuffle() },
            onRepeatClick = { viewModel.toggleRepeat() },
            onAutomixClick = { viewModel.toggleAutomix() },
            onQueueItemClick = { viewModel.skipToQueueIndex(it) },
            onMoveQueueItem = { from, to -> viewModel.moveQueueItem(from, to) },
            onRemoveQueueItem = { viewModel.removeFromQueue(it) },
            onSeek = { viewModel.seekTo(it) },
            onDismiss = { isNowPlayingExpanded = false },
            onSaveQueueAsPlaylist = {
                val song = playerState.currentSong
                val queue = playerState.currentQueue
                if (song != null && queue.isNotEmpty()) {
                    val name = context.getString(R.string.smart_mix_for_track, song.title)
                    viewModel.saveSmartMixAsPlaylist(
                        name = name,
                        smartType = "SEED",
                        seedSongId = song.id,
                        songs = queue
                    )
                    android.widget.Toast.makeText(context, R.string.smart_mix_saved_toast, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}

@Composable
fun SongListItem(
    song: Song,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onOptionsClick: (() -> Unit)? = null
) {
    Surface(
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = stringResource(R.string.content_desc_album_art),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(50.dp)
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${song.artist} • ${song.formattedDuration}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onOptionsClick != null) {
                IconButton(onClick = onOptionsClick) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.action_more_options),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

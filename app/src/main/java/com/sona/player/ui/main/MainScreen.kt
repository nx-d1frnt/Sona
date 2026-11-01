package com.sona.player.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sona.player.data.model.Song
import com.sona.player.ui.components.MiniPlayer
import com.sona.player.ui.components.NowPlayingScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestPermission: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val analysis by viewModel.libraryAnalysis.collectAsState()
    val canAnalyze by viewModel.canAnalyze.collectAsState()
    var settingsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var forceAnalysis by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var isNowPlayingExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Sona",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            val progress = if (playerState.durationMs > 0) {
                playerState.currentPositionMs.toFloat() / playerState.durationMs.toFloat()
            } else {
                0f
            }

            MiniPlayer(
                song = playerState.currentSong,
                isPlaying = playerState.isPlaying,
                progress = progress,
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onNextClick = { viewModel.skipToNext() },
                onClick = { isNowPlayingExpanded = true }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchQueryChanged,
                placeholder = { Text("Search songs, artists, albums...") },
                leadingIcon = {
                    Icon(imageVector = Icons.Rounded.Search, contentDescription = "Search")
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                            Icon(imageVector = Icons.Rounded.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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
                                    text = "Permission needed to access audio files",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(bottom = 16.dp)
                                )
                                Button(onClick = onRequestPermission) {
                                    Text("Grant Permission")
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
                                text = "No audio files found",
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
                                    onClick = { viewModel.playSong(song) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Настройки автомикса") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    Text("Анализ всей библиотеки", style = MaterialTheme.typography.titleMedium)
                    Text("Распознаёт вокал, ритм, громкость и точки переходов. Результаты сохраняются на устройстве и используются повторно.")
                    Text("Обработка ждёт паузы в воспроизведении. Держите приложение открытым до завершения; после закрытия можно продолжить с сохранённых результатов.",
                        style = MaterialTheme.typography.bodySmall)
                    if (!analysis.running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = forceAnalysis,
                                onCheckedChange = { forceAnalysis = it }
                            )
                            Text("Пересчитать даже сохранённые треки", modifier = Modifier
                                .weight(1f).clickable { forceAnalysis = !forceAnalysis })
                        }
                        if (forceAnalysis) Text("Повторный анализ всей библиотеки может занять много времени и расходует заряд.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (analysis.total > 0) {
                        Text(when {
                            analysis.running && analysis.waitingForPlayback -> "Ожидание паузы в воспроизведении"
                            analysis.running -> "Анализ: ${analysis.title}"
                            analysis.cancelled -> "Анализ остановлен"
                            else -> "Анализ завершён"
                        })
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { analysis.completed.toFloat() / analysis.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${analysis.completed} из ${analysis.total} • Из кэша: ${analysis.cached} • Ошибок: ${analysis.failed}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (analysis.running) {
                        androidx.compose.material3.OutlinedButton(onClick = viewModel::cancelLibraryAnalysis) { Text("Остановить анализ") }
                    } else {
                        Button(onClick = { viewModel.startLibraryAnalysis(forceAnalysis) }, enabled = canAnalyze) {
                            Text(if (forceAnalysis) "Пересчитать всю библиотеку" else "Анализировать библиотеку")
                        }
                        if (!canAnalyze) Text("Для запуска нужен доступ к непустой музыкальной библиотеке.")
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { settingsOpen = false }) { Text("Закрыть") } }
        )
    }

    // Full Screen Now Playing Overlay
    AnimatedVisibility(
        visible = isNowPlayingExpanded,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        NowPlayingScreen(
            song = playerState.currentSong,
            isPlaying = playerState.isPlaying,
            currentPositionMs = playerState.currentPositionMs,
            durationMs = playerState.durationMs,
            isShuffle = playerState.isShuffleEnabled,
            shuffleMode = playerState.shuffleMode,
            repeatMode = playerState.repeatMode,
            queue = playerState.currentQueue,
            currentIndex = playerState.currentIndex,
            automixState = playerState.automix,
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
            onDismiss = { isNowPlayingExpanded = false }
        )
    }
}

@Composable
fun SongListItem(
    song: Song,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
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
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = null,
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
        }
    }
}

package com.sona.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.data.model.Song
import com.sona.player.ui.theme.rememberArtworkPalette
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    song: Song?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    isShuffle: Boolean,
    repeatMode: Int,
    queue: List<Song> = emptyList(),
    currentIndex: Int = 0,
    automixState: AutomixTransitionState = AutomixTransitionState(),
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onAutomixClick: () -> Unit = {},
    onQueueItemClick: (Int) -> Unit = {},
    onMoveQueueItem: (Int, Int) -> Unit = { _, _ -> },
    onRemoveQueueItem: (Int) -> Unit = {},
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (song == null) return

    // Динамическое извлечение палитры Monet прямо из обложки текущего трека
    val palette = rememberArtworkPalette(song.albumArtUri)

    var isQueueSheetOpen by remember { mutableStateOf(false) }
    var isUserSeeking by remember { mutableFloatStateOf(-1f) }
    val progress = if (isUserSeeking >= 0f) {
        isUserSeeking
    } else if (durationMs > 0) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val automixZoneFraction = if (automixState.isEnabled) {
        automixState.currentAnalysis?.automixZoneFraction ?: 0.85f
    } else {
        1.0f
    }

    BackHandler(onBack = {
        if (isQueueSheetOpen) {
            isQueueSheetOpen = false
        } else {
            onDismiss()
        }
    })

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { /* Consume touches so taps on cover art don't pass through to SongList/MiniPlayer */ }
            )
    ) {
        // 1. Четкая полноэкранная обложка с плавным растворением (Crossfade) в пике Automix-перехода
        Crossfade(
            targetState = song.albumArtUri,
            animationSpec = tween(durationMillis = 900),
            label = "FullScreenCoverCrossfade"
        ) { targetArtUri ->
            if (targetArtUri != null) {
                AsyncImage(
                    model = targetArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(120.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }

        // 2. Прогрессивный блюр обложки: размытая полноэкранная копия с альфа-маской (BlendMode.DstIn)
        Crossfade(
            targetState = song.albumArtUri,
            animationSpec = tween(durationMillis = 900),
            label = "ProgressiveBlurCrossfade"
        ) { targetArtUri ->
            if (targetArtUri != null) {
                AsyncImage(
                    model = targetArtUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(30.dp)
                        .graphicsLayer(alpha = 0.99f)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0.0f to Color.Transparent,
                                    0.35f to Color.Transparent,
                                    0.60f to Color.Black.copy(alpha = 0.5f),
                                    0.85f to Color.Black
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                )
            }
        }

        // 3. Мягкое затемнение сверху (чтобы статус-бар и иконка закрытия читались на светлых обложках)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 4. Плавное градиентное затемнение под элементами управления
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.35f to Color.Transparent,
                        0.58f to Color.Black.copy(alpha = 0.25f),
                        0.78f to Color.Black.copy(alpha = 0.65f),
                        1.0f to Color.Black.copy(alpha = 0.90f)
                    )
                )
        )

        // 3. UI элементы
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Верхняя панель (Header bar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Collapse player",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.size(48.dp))
            }

            // Пространство для открытого просмотра полноэкранной обложки
            Spacer(modifier = Modifier.weight(1f))

            // Блок информации о треке и кнопки действий (как в референсе: название слева, share/favorite справа)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                var isFavorite by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = "${song.artist} • ${song.album}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.70f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Expressive action pills (Share & Like) с динамическими цветами от обложки
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.14f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            IconButton(onClick = { /* Share */ }) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = "Share",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        val favoriteBgColor by animateColorAsState(
                            targetValue = if (isFavorite) palette.primaryAccent else Color.White.copy(alpha = 0.14f),
                            label = "FavoriteBg"
                        )
                        val favoriteIconColor by animateColorAsState(
                            targetValue = if (isFavorite) palette.onPrimaryAccent else Color.White,
                            label = "FavoriteIcon"
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = favoriteBgColor,
                            modifier = Modifier.size(44.dp)
                        ) {
                            IconButton(onClick = { isFavorite = !isFavorite }) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = favoriteIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Стильная капсула Automix: показывает фазу DJ-сведения («High-Pass Blend», «Bass Swap», «Filter Sweep») или «Up Next»
                val nextTrack = automixState.nextMatchedSong
                val targetTrack = automixState.transitionTargetSong ?: nextTrack
                val showAutomixBanner = automixState.isEnabled && (
                    (automixState.isTransitioning && targetTrack != null) ||
                        (progress >= automixZoneFraction && nextTrack != null && nextTrack.id != song.id)
                    )

                AnimatedContent(
                    targetState = showAutomixBanner,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                    label = "AutomixBannerTransition"
                ) { showBanner ->
                    if (showBanner) {
                        val bpmText = (if (automixState.isTransitioning) {
                            automixState.currentAnalysis?.bpm ?: automixState.nextMatchedAnalysis?.bpm
                        } else {
                            automixState.nextMatchedAnalysis?.bpm ?: automixState.currentAnalysis?.bpm
                        })?.roundToInt()

                        val bannerLabel = if (automixState.isTransitioning && targetTrack != null) {
                            val pct = (automixState.transitionProgress * 100f).roundToInt().coerceIn(1, 100)
                            val djStage = when {
                                automixState.transitionProgress < 0.44f -> "Automix (EQ High-Pass) → ${targetTrack.title}"
                                automixState.transitionProgress < 0.56f -> "Automix (Bass Swap!) → ${targetTrack.title}"
                                else -> "Automix (LPF Sweep $pct%)"
                            }
                            if (bpmText != null && automixState.transitionProgress < 0.56f) {
                                "$djStage • $bpmText BPM"
                            } else if (bpmText != null) {
                                "$djStage • $bpmText BPM"
                            } else {
                                djStage
                            }
                        } else {
                            val upcomingTitle = nextTrack?.title ?: ""
                            val suffix = if (bpmText != null) " • $bpmText BPM" else ""
                            "Up Next → $upcomingTitle$suffix"
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = palette.secondaryContainer.copy(alpha = 0.72f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clickable { isQueueSheetOpen = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = palette.secondaryAccent,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = bannerLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // Вариант 2: Фирменный волновой прогресс-бар Sona с реальной точкой аутро (automixZoneStartFraction)
                ExpressiveWaveSlider(
                    progress = progress,
                    isPlaying = isPlaying,
                    primaryColor = palette.primaryAccent,
                    automixAccentColor = palette.secondaryAccent,
                    automixZoneStartFraction = automixZoneFraction,
                    onValueChange = { fraction ->
                        isUserSeeking = fraction
                    },
                    onValueChangeFinished = { finalFraction ->
                        val newPosition = (finalFraction.coerceIn(0f, 1f) * durationMs).toLong()
                        onSeek(newPosition)
                        isUserSeeking = -1f
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val currentDisplayMs = if (isUserSeeking >= 0f) {
                        (isUserSeeking * durationMs).toLong()
                    } else {
                        currentPositionMs
                    }
                    Text(
                        text = formatTime(currentDisplayMs),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.65f)
                    )

                    // Ненавязчивый индикатор текущего BPM и статуса Automix между таймкодами
                    val curBpm = automixState.currentAnalysis?.bpm?.roundToInt()
                    if (automixState.isEnabled && curBpm != null) {
                        val tempoTag = if (automixState.isTransitioning && automixState.activeTempoRatio != 1.0f) {
                            val pct = ((automixState.activeTempoRatio - 1.0f) * 100f).roundToInt()
                            val sign = if (pct > 0) "+" else ""
                            " ($sign$pct%)"
                        } else ""
                        Text(
                            text = "$curBpm BPM$tempoTag",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (automixState.isTransitioning) palette.secondaryAccent else Color.White.copy(alpha = 0.50f)
                        )
                    } else {
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    Text(
                        text = formatTime(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Вариант 1: Ансамбль кнопок с живым морфингом формы (M3 Expressive Shape Morphing) и пружинной физикой
                ExpressiveTransportControls(
                    isPlaying = isPlaying,
                    primaryAccent = palette.primaryAccent,
                    onPrimaryAccent = palette.onPrimaryAccent,
                    secondaryAccent = palette.secondaryAccent,
                    onPlayPauseClick = onPlayPauseClick,
                    onPreviousClick = onPreviousClick,
                    onNextClick = onNextClick,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Дополнительный нижний ряд Material 3 Expressive в виде капсул (как на референсе)
                val isAutomixEnabled = automixState.isEnabled

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Группа режимов слева (Queue, Automix, Shuffle, Repeat)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Queue pill (opens Queue & Automix Chain sheet)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isQueueSheetOpen) palette.primaryContainer.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(width = 48.dp, height = 40.dp)
                        ) {
                            IconButton(onClick = { isQueueSheetOpen = !isQueueSheetOpen }) {
                                Icon(
                                    imageVector = Icons.Rounded.QueueMusic,
                                    contentDescription = "Queue",
                                    tint = if (isQueueSheetOpen) palette.onPrimaryContainer else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Automix Expressive Toggle Pill (Monet TertiaryContainer & Tertiary)
                        val automixBg by animateColorAsState(
                            targetValue = if (isAutomixEnabled) palette.secondaryContainer.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                            label = "AutomixBg"
                        )
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = automixBg,
                            modifier = Modifier.size(width = 48.dp, height = 40.dp)
                        ) {
                            IconButton(onClick = onAutomixClick) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Automix",
                                    tint = if (isAutomixEnabled) palette.secondaryAccent else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Shuffle pill (Monet PrimaryContainer & OnPrimaryContainer)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isShuffle) palette.primaryContainer.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(width = 48.dp, height = 40.dp)
                        ) {
                            IconButton(onClick = onShuffleClick) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffle) palette.onPrimaryContainer else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Repeat pill (Monet PrimaryContainer & OnPrimaryContainer)
                        val isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isRepeatActive) palette.primaryContainer.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(width = 48.dp, height = 40.dp)
                        ) {
                            IconButton(onClick = onRepeatClick) {
                                val icon = if (repeatMode == Player.REPEAT_MODE_ONE) {
                                    Icons.Rounded.RepeatOne
                                } else {
                                    Icons.Rounded.Repeat
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = "Repeat",
                                    tint = if (isRepeatActive) palette.onPrimaryContainer else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    // Кнопка меню (More) справа
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(onClick = { /* More options */ }) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = "More",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Выдвижное меню очереди треков с сохранёнными DJ-переходами
        AnimatedContent(
            targetState = isQueueSheetOpen,
            transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(200)) },
            label = "QueueSheetOverlay"
        ) { open ->
            if (open) {
                AutomixQueueSheet(
                    queue = queue,
                    currentIndex = currentIndex,
                    currentSong = song,
                    automixState = automixState,
                    palette = palette,
                    onTrackClick = { idx -> onQueueItemClick(idx) },
                    onMoveTrack = { from, to -> onMoveQueueItem(from, to) },
                    onRemoveTrack = { idx -> onRemoveQueueItem(idx) },
                    onToggleAutomix = onAutomixClick,
                    onDismiss = { isQueueSheetOpen = false }
                )
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

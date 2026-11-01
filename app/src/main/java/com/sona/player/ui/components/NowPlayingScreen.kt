package com.sona.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import com.sona.player.data.model.Song
import com.sona.player.ui.theme.rememberArtworkPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    song: Song?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    isShuffle: Boolean,
    repeatMode: Int,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (song == null) return

    // Динамическое извлечение цветов прямо из обложки текущего трека (Вариант 4)
    val palette = rememberArtworkPalette(song.albumArtUri)

    var isUserSeeking by remember { mutableFloatStateOf(-1f) }
    val progress = if (isUserSeeking >= 0f) {
        isUserSeeking
    } else if (durationMs > 0) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. Четкая полноэкранная обложка альбома на весь экран (базовый слой)
        if (song.albumArtUri != null) {
            AsyncImage(
                model = song.albumArtUri,
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

        // 2. Прогрессивный блюр обложки: размытая полноэкранная копия с альфа-маской (BlendMode.DstIn)
        // Плавное нарастание блюра сверху вниз без видимых стыков и резких границ
        if (song.albumArtUri != null) {
            AsyncImage(
                model = song.albumArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(30.dp)
                    .graphicsLayer(alpha = 0.99f) // создаем отдельный слой рендеринга для DstIn маски
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

                Spacer(modifier = Modifier.height(20.dp))

                // Вариант 2: Фирменный волновой прогресс-бар Sona с подсветкой зоны Automix
                ExpressiveWaveSlider(
                    progress = progress,
                    isPlaying = isPlaying,
                    primaryColor = palette.primaryAccent,
                    automixAccentColor = palette.secondaryAccent,
                    onValueChange = { isUserSeeking = it },
                    onValueChangeFinished = {
                        if (isUserSeeking >= 0f) {
                            val newPosition = (isUserSeeking * durationMs).toLong()
                            onSeek(newPosition)
                            isUserSeeking = -1f
                        }
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

                    // Индикатор зоны Automix при приближении к концу трека
                    val isInAutomixZone = progress >= 0.85f
                    AnimatedContent(
                        targetState = isInAutomixZone,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "AutomixBadge"
                    ) { inZone ->
                        if (inZone) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = palette.secondaryAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "AUTOMIX ZONE",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = palette.secondaryAccent
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.height(14.dp))
                        }
                    }

                    Text(
                        text = formatTime(durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Главный ряд управления: Крупная пилюля Play/Pause с динамическим цветом обложки + морфинг
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous Button with expressive dark glass container
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.size(60.dp)
                    ) {
                        IconButton(
                            onClick = onPreviousClick,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipPrevious,
                                contentDescription = "Previous",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    // Expressive Hero Pill Button (Play / Pause) — динамический цвет и морфинг углов
                    val playPillWidth by animateDpAsState(
                        targetValue = if (isPlaying) 176.dp else 196.dp,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "PlayPillWidth"
                    )
                    val playPillCorner by animateDpAsState(
                        targetValue = if (isPlaying) 24.dp else 34.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "PlayPillCorner"
                    )

                    Surface(
                        onClick = onPlayPauseClick,
                        shape = RoundedCornerShape(playPillCorner),
                        color = palette.primaryAccent,
                        shadowElevation = 12.dp,
                        modifier = Modifier
                            .height(68.dp)
                            .width(playPillWidth)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            AnimatedContent(
                                targetState = isPlaying,
                                transitionSpec = { fadeIn() togetherWith fadeOut() },
                                label = "PlayIconTransition"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (playing) "Pause" else "Play",
                                    tint = palette.onPrimaryAccent,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isPlaying) "Pause" else "Play",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = palette.onPrimaryAccent
                            )
                        }
                    }

                    // Next Button with expressive dark glass container
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.size(60.dp)
                    ) {
                        IconButton(
                            onClick = onNextClick,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SkipNext,
                                contentDescription = "Next",
                                tint = Color.White,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Дополнительный нижний ряд Material 3 Expressive в виде капсул (как на референсе)
                var isAutomixEnabled by remember { mutableStateOf(true) }

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
                        // Queue pill
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(width = 48.dp, height = 40.dp)
                        ) {
                            IconButton(onClick = { /* Queue */ }) {
                                Icon(
                                    imageVector = Icons.Rounded.QueueMusic,
                                    contentDescription = "Queue",
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Automix Expressive Toggle Pill
                        val automixBg by animateColorAsState(
                            targetValue = if (isAutomixEnabled) palette.secondaryAccent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.12f),
                            label = "AutomixBg"
                        )
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = automixBg,
                            modifier = Modifier.size(width = 48.dp, height = 40.dp)
                        ) {
                            IconButton(onClick = { isAutomixEnabled = !isAutomixEnabled }) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Automix",
                                    tint = if (isAutomixEnabled) palette.secondaryAccent else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Shuffle pill
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isShuffle) palette.primaryAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.size(width = 48.dp, height = 40.dp)
                        ) {
                            IconButton(onClick = onShuffleClick) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = "Shuffle",
                                    tint = if (isShuffle) palette.primaryAccent else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Repeat pill
                        val isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isRepeatActive) palette.primaryAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f),
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
                                    tint = if (isRepeatActive) palette.primaryAccent else Color.White.copy(alpha = 0.85f),
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
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

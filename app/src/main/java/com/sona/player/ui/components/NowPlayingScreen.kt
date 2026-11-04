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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
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
import androidx.compose.runtime.produceState
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
import com.sona.player.automix.TransitionPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sona.player.automix.TransitionPlanner
import com.sona.player.automix.AutomixMode
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.ShuffleMode
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
    shuffleMode: ShuffleMode = ShuffleMode.OFF,
    repeatMode: Int,
    queue: List<Song> = emptyList(),
    currentIndex: Int = 0,
    automixState: AutomixTransitionState = AutomixTransitionState(),
    smartQueueUpdatedAtMs: Long = 0L,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onShareClick: () -> Unit = {},
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
    onSaveQueueAsPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (song == null) return

    // Динамическое извлечение палитры Monet прямо из обложки текущего трека
    val palette = rememberArtworkPalette(song.albumArtUri)
    val targetTrack = automixState.transitionTargetSong ?: automixState.nextMatchedSong
    val targetPalette = rememberArtworkPalette(targetTrack?.albumArtUri)

    var isQueueSheetOpen by remember { mutableStateOf(false) }
    var isUserSeeking by remember { mutableFloatStateOf(-1f) }

    val rawProgress = if (durationMs > 0) {
        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val progressMotion = rememberAutomixProgressMotion(
        progress = rawProgress,
        primaryColor = palette.primaryAccent,
        isTransitioning = automixState.isTransitioning,
        targetSongId = targetTrack?.id
    )
    val progress = if (isUserSeeking >= 0f) isUserSeeking else rawProgress

    val activeTrackAnalysis = automixState.currentAnalysis?.takeIf { it.songId == song.id }
        ?: automixState.analyzedTracks[song.id]
    val nextId = automixState.nextMatchedSong?.id
    val activeNextAnalysis = automixState.nextMatchedAnalysis?.takeIf { it.songId == nextId }
        ?: nextId?.let { automixState.analyzedTracks[it] }
    val transitionPreview by produceState<TransitionPlan?>(null,
        activeTrackAnalysis, activeNextAnalysis, automixState.mode, automixState.entryPositionMs) {
        value = null
        if (activeTrackAnalysis != null && activeNextAnalysis != null) {
            value = withContext(Dispatchers.Default) {
                TransitionPlanner.plan(activeTrackAnalysis, activeNextAnalysis, automixState.mode,
                    entryPositionMs = automixState.entryPositionMs)
            }
        }
    }
    val displayedMixOutMs = transitionPreview?.mixOutMs?.let { planned ->
        if (automixState.manualSeek) planned else maxOf(planned, com.sona.player.automix.TrackListeningGuard.earliestPosition(
            currentPositionMs, durationMs, automixState.playedMs, automixState.activeTempoRatio))
    }
    val targetZoneFraction = if (automixState.isEnabled) {
        displayedMixOutMs?.let { (it.toFloat() / activeTrackAnalysis!!.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f) }
            ?: activeTrackAnalysis?.effectiveZoneFraction(automixState.mode, activeNextAnalysis)
            ?: if (automixState.mode == AutomixMode.QUICK_MID_MIX) 0.56f else 0.82f
    } else {
        1.0f
    }
    val automixZoneFraction by animateFloatAsState(
        targetValue = targetZoneFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "AutomixZoneFraction"
    )

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
                        contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_collapse_player),
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Text(
                    text = androidx.compose.ui.res.stringResource(com.sona.player.R.string.now_playing_title),
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
                            IconButton(onClick = onShareClick) {
                                Icon(
                                    imageVector = Icons.Rounded.Share,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_share),
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
                            IconButton(onClick = onToggleFavorite) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_favorite),
                                    tint = favoriteIconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val nextQueueTrack = when {
                    repeatMode == Player.REPEAT_MODE_ONE -> song
                    currentIndex !in queue.indices -> null
                    currentIndex + 1 < queue.size -> queue[currentIndex + 1]
                    repeatMode == Player.REPEAT_MODE_ALL -> queue.firstOrNull()
                    else -> null
                }
                val canMixNext = automixState.isEnabled &&
                    repeatMode != Player.REPEAT_MODE_ONE &&
                    currentIndex in queue.indices && currentIndex + 1 < queue.size &&
                    durationMs >= 20_000L
                val plannedMixOutMs = displayedMixOutMs
                    ?: activeTrackAnalysis?.effectiveMixOutMs(automixState.mode, activeNextAnalysis)
                    ?: if (automixState.mode == AutomixMode.QUICK_MID_MIX) {
                        (durationMs * 0.56f).toLong().coerceAtLeast(15_000L)
                    } else {
                        (durationMs - 14_000L).coerceAtLeast((durationMs * 0.72f).toLong())
                    }
                val guardedMixOutMs = if (automixState.manualSeek) plannedMixOutMs else maxOf(
                    plannedMixOutMs,
                    com.sona.player.automix.TrackListeningGuard.earliestPosition(
                        currentPositionMs, durationMs, automixState.playedMs, automixState.activeTempoRatio
                    )
                )
                val nextChangeAtMs = if (canMixNext && guardedMixOutMs < durationMs - 2_500L) {
                    guardedMixOutMs
                } else durationMs
                SonaLiveBanner(
                    songId = song.id,
                    showUpcoming = isSonaLiveUpcoming(
                        currentPositionMs, nextChangeAtMs, automixState.activeTempoRatio
                    ),
                    isPlaying = isPlaying,
                    isTransitioning = automixState.isTransitioning,
                    transitionTitle = automixState.transitionTargetSong?.title
                        ?: automixState.nextMatchedSong?.title,
                    nextTitle = nextQueueTrack?.title,
                    repeatOne = repeatMode == Player.REPEAT_MODE_ONE,
                    smartShuffleEnabled = shuffleMode == ShuffleMode.SMART,
                    isAnalyzing = automixState.isAnalyzing,
                    smartQueueUpdatedAtMs = smartQueueUpdatedAtMs,
                    containerColor = palette.secondaryContainer.copy(alpha = 0.72f),
                    accentColor = palette.secondaryAccent,
                    onClick = { isQueueSheetOpen = true },
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Вариант 2: Фирменный волновой прогресс-бар Sona с реальной точкой аутро (automixZoneStartFraction)
                ExpressiveWaveSlider(
                    progress = progress,
                    animatedProgress = if (isUserSeeking >= 0f) null else progressMotion.progress,
                    isPlaying = isPlaying,
                    primaryColor = progressMotion.outgoingColor,
                    automixAccentColor = palette.secondaryAccent,
                    automixZoneStartFraction = automixZoneFraction,
                    isTransitioning = automixState.isTransitioning,
                    transitionProgress = automixState.transitionProgress,
                    targetPrimaryColor = targetTrack?.let { targetPalette.primaryAccent },
                    bpm = activeTrackAnalysis?.bpm,
                    activeTempoRatio = automixState.activeTempoRatio,
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

                    // Ненавязчивый индикатор текущего BPM, режима Automix (вкл. решение AUTO) и точного таймкода точки перехода
                    val curBpm = activeTrackAnalysis?.bpm?.roundToInt()
                    if (automixState.isEnabled && curBpm != null) {
                        val tempoTag = if (automixState.isTransitioning && automixState.activeTempoRatio != 1.0f) {
                            val pct = ((automixState.activeTempoRatio - 1.0f) * 100f).roundToInt()
                            val sign = if (pct > 0) "+" else ""
                            " ($sign$pct%)"
                        } else ""
                        val resolvedMode = activeTrackAnalysis.resolveEffectiveMode(automixState.mode, activeNextAnalysis)
                        val mixPointMs = displayedMixOutMs
                            ?: activeTrackAnalysis.effectiveMixOutMs(automixState.mode, activeNextAnalysis)
                        val mixPointTag = when (automixState.mode) {
                            AutomixMode.AUTO_DJ -> {
                                val autoKind = if (resolvedMode == AutomixMode.QUICK_MID_MIX) "MID" else "Mix"
                                " • AUTO ($autoKind @ ${formatTime(mixPointMs)})"
                            }
                            AutomixMode.QUICK_MID_MIX -> " • MID @ ${formatTime(mixPointMs)}"
                            else -> " • Mix @ ${formatTime(mixPointMs)}"
                        }
                        Text(
                            text = "$curBpm BPM$tempoTag$mixPointTag",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (automixState.isTransitioning || resolvedMode == AutomixMode.QUICK_MID_MIX) {
                                palette.secondaryAccent
                            } else {
                                Color.White.copy(alpha = 0.60f)
                            }
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
                                    imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_queue),
                                    tint = if (isQueueSheetOpen) palette.onPrimaryContainer else Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Automix Expressive Mode Pill: cycles AUTO -> OUTRO -> MID DJ -> OFF
                        val automixBg by animateColorAsState(
                            targetValue = when (automixState.mode) {
                                AutomixMode.AUTO_DJ -> palette.secondaryContainer.copy(alpha = 0.90f)
                                AutomixMode.QUICK_MID_MIX -> palette.secondaryAccent.copy(alpha = 0.28f)
                                AutomixMode.CLASSIC_OUTRO -> palette.secondaryContainer.copy(alpha = 0.78f)
                                AutomixMode.OFF -> Color.White.copy(alpha = 0.12f)
                            },
                            label = "AutomixBg"
                        )
                        val automixBadgeText = when (automixState.mode) {
                            AutomixMode.AUTO_DJ -> "AUTO"
                            AutomixMode.CLASSIC_OUTRO -> "OUTRO"
                            AutomixMode.QUICK_MID_MIX -> "MID"
                            AutomixMode.OFF -> null
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = automixBg,
                            modifier = Modifier
                                .height(40.dp)
                                .clickable(onClick = onAutomixClick)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_automix_mode),
                                    tint = if (isAutomixEnabled) palette.secondaryAccent else Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp)
                                )
                                if (automixBadgeText != null) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = automixBadgeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = palette.secondaryAccent
                                    )
                                }
                            }
                        }

                        // Shuffle Expressive Mode Pill: cycles OFF -> RANDOM -> SMART (Harmonic DJ Shuffle)
                        val isSmartShuffle = shuffleMode == ShuffleMode.SMART
                        val isAnyShuffle = shuffleMode != ShuffleMode.OFF || isShuffle
                        val shuffleBg by animateColorAsState(
                            targetValue = when (shuffleMode) {
                                ShuffleMode.SMART -> palette.primaryAccent.copy(alpha = 0.28f)
                                ShuffleMode.RANDOM -> palette.primaryContainer.copy(alpha = 0.85f)
                                ShuffleMode.OFF -> if (isShuffle) palette.primaryContainer.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.12f)
                            },
                            label = "ShuffleBg"
                        )
                        val shuffleTint = when (shuffleMode) {
                            ShuffleMode.SMART -> palette.primaryAccent
                            ShuffleMode.RANDOM -> palette.onPrimaryContainer
                            ShuffleMode.OFF -> if (isShuffle) palette.onPrimaryContainer else Color.White.copy(alpha = 0.85f)
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = shuffleBg,
                            modifier = Modifier
                                .height(40.dp)
                                .clickable(onClick = onShuffleClick)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Shuffle,
                                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_shuffle_mode),
                                    tint = shuffleTint,
                                    modifier = Modifier.size(19.dp)
                                )
                                if (isSmartShuffle) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "SMART",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = palette.primaryAccent
                                    )
                                }
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
                                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_repeat),
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
                        IconButton(onClick = onMoreClick) {
                            Icon(
                                imageVector = Icons.Rounded.MoreVert,
                                contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_more),
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
                    onDismiss = { isQueueSheetOpen = false },
                    onSaveAsSmartPlaylist = onSaveQueueAsPlaylist
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

package com.sona.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sona.player.R
import com.sona.player.lyrics.model.LyricLine
import com.sona.player.lyrics.model.LyricSource
import com.sona.player.lyrics.model.TrackLyrics
import com.sona.player.ui.theme.ArtworkPalette
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun LyricsView(
    lyrics: TrackLyrics?,
    isLoading: Boolean,
    currentPositionMs: Long,
    palette: ArtworkPalette,
    onSeekTo: (Long) -> Unit,
    onOffsetChange: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onOpenManualSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showOffsetControls by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = palette.primaryAccent,
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = stringResource(R.string.lyrics_searching),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            lyrics == null || lyrics.isEmpty -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.lyrics_not_found),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Surface(
                            shape = CircleShape,
                            color = palette.primaryAccent.copy(alpha = 0.22f),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable(role = Role.Button, onClick = onOpenManualSearch)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = palette.primaryAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.lyrics_manual_search),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            !lyrics.isSynced -> {
                // Plain unsynced lyrics display
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        LyricsHeaderBar(
                            lyrics = lyrics,
                            palette = palette,
                            onToggleOffset = { showOffsetControls = !showOffsetControls },
                            onOpenManualSearch = onOpenManualSearch
                        )
                    }
                    item {
                        Text(
                            text = lyrics.plainLyrics.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp),
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }

            else -> {
                // Synced Lyrics (Apple Music interactive style)
                SyncedLyricsContent(
                    lyrics = lyrics,
                    currentPositionMs = currentPositionMs,
                    palette = palette,
                    showOffsetControls = showOffsetControls,
                    onSeekTo = onSeekTo,
                    onToggleOffset = { showOffsetControls = !showOffsetControls },
                    onOffsetChange = onOffsetChange,
                    onResetOffset = onResetOffset,
                    onOpenManualSearch = onOpenManualSearch
                )
            }
        }
    }
}

@Composable
private fun SyncedLyricsContent(
    lyrics: TrackLyrics,
    currentPositionMs: Long,
    palette: ArtworkPalette,
    showOffsetControls: Boolean,
    onSeekTo: (Long) -> Unit,
    onToggleOffset: () -> Unit,
    onOffsetChange: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onOpenManualSearch: () -> Unit
) {
    val listState = rememberLazyListState()
    val activeIndex = remember(currentPositionMs, lyrics) {
        lyrics.findActiveLineIndex(currentPositionMs)
    }

    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var userScrolledAway by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            userScrolledAway = true
        }
    }

    // Material 3 Emphasized Decel 550ms curved auto-scroll to keep active line centered
    LaunchedEffect(activeIndex, userScrolledAway) {
        if (!userScrolledAway && activeIndex in lyrics.lines.indices) {
            val targetItem = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
            if (targetItem != null) {
                val delta = (targetItem.offset - 220).toFloat()
                if (kotlin.math.abs(delta) > 4f) {
                    listState.animateScrollBy(
                        value = delta,
                        animationSpec = tween(
                            durationMillis = 550,
                            easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
                        )
                    )
                }
            } else {
                listState.animateScrollToItem(
                    index = activeIndex,
                    scrollOffset = -220
                )
            }
        }
    }

    val isCurrentLineVisible = remember(listState.layoutInfo, activeIndex) {
        listState.layoutInfo.visibleItemsInfo.any { it.index == activeIndex }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LyricsHeaderBar(
            lyrics = lyrics,
            palette = palette,
            onToggleOffset = onToggleOffset,
            onOpenManualSearch = onOpenManualSearch
        )

        AnimatedVisibility(
            visible = showOffsetControls,
            enter = fadeIn() + androidx.compose.animation.expandVertically(),
            exit = fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            LyricsOffsetControlBar(
                offsetMs = lyrics.userOffsetMs,
                palette = palette,
                onOffsetChange = onOffsetChange,
                onResetOffset = onResetOffset
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 220.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                itemsIndexed(
                    items = lyrics.lines,
                    key = { index, line -> "${line.startMs}_$index" }
                ) { index, line ->
                    val isActive = index == activeIndex
                    val isPast = index < activeIndex

                    LyricLineItem(
                        line = line,
                        isActive = isActive,
                        isPast = isPast,
                        currentPositionMs = currentPositionMs - lyrics.userOffsetMs,
                        palette = palette,
                        onClick = {
                            userScrolledAway = false
                            onSeekTo(line.startMs + lyrics.userOffsetMs)
                        }
                    )
                }
            }

            // Floating "Jump to current lyric" pill ONLY when user has dragged away and active line is not visible
            androidx.compose.animation.AnimatedVisibility(
                visible = userScrolledAway && !isCurrentLineVisible && activeIndex in lyrics.lines.indices,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = palette.primaryContainer,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                userScrolledAway = false
                                scope.launch {
                                    listState.animateScrollToItem(activeIndex, scrollOffset = -220)
                                }
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            tint = palette.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.lyrics_jump_to_current),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricLineItem(
    line: LyricLine,
    isActive: Boolean,
    isPast: Boolean,
    currentPositionMs: Long,
    palette: ArtworkPalette,
    onClick: () -> Unit
) {
    val letterSpacing by animateFloatAsState(
        targetValue = if (isActive) 0.22f else 0.0f,
        animationSpec = tween(durationMillis = 350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "LyricLetterSpacing"
    )

    val alpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1.0f
            isPast -> 0.48f
            else -> 0.38f
        },
        animationSpec = tween(durationMillis = 350, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "LyricAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = stringResource(R.string.lyrics_tap_to_seek),
                onClick = onClick
            )
            .padding(vertical = 4.dp)
    ) {
        if (isActive && line.words.isNotEmpty()) {
            // Continuous word-by-word karaoke progressive interpolation
            val annotatedText = buildAnnotatedString {
                for (word in line.words) {
                    val wordDuration = (word.endMs - word.startMs).coerceAtLeast(80L)
                    val wordProgress = ((currentPositionMs - word.startMs).toFloat() / wordDuration.toFloat()).coerceIn(0f, 1f)
                    val currentAlpha = 0.35f + (0.65f * wordProgress)
                    val wordColor = Color.White.copy(alpha = currentAlpha)
                    withStyle(SpanStyle(color = wordColor)) {
                        append(word.text)
                    }
                    append(" ")
                }
            }
            Text(
                text = annotatedText,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = letterSpacing.sp,
                    lineHeight = 30.sp
                )
            )
        } else {
            Text(
                text = line.text,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = 22.sp,
                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                    letterSpacing = letterSpacing.sp,
                    lineHeight = 30.sp
                ),
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.70f)
            )
        }
    }
}

@Composable
private fun LyricsHeaderBar(
    lyrics: TrackLyrics,
    palette: ArtworkPalette,
    onToggleOffset: () -> Unit,
    onOpenManualSearch: () -> Unit
) {
    val sourceLabel = when (lyrics.source) {
        LyricSource.LOCAL_FILE -> stringResource(R.string.lyrics_source_local)
        LyricSource.EMBEDDED -> stringResource(R.string.lyrics_source_embedded)
        LyricSource.LRCLIB -> stringResource(R.string.lyrics_source_online)
        LyricSource.MANUAL -> stringResource(R.string.lyrics_source_manual)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Informational source badge (M3 Suggestion Chip geometry)
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = 0.12f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(palette.primaryAccent, CircleShape)
                )
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Offset Adjuster Toggle Button (Accessible M3 Pill with >= 48dp target)
            Surface(
                shape = CircleShape,
                color = if (lyrics.userOffsetMs != 0L) palette.primaryContainer else Color.White.copy(alpha = 0.14f),
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .clickable(role = Role.Button, onClick = onToggleOffset)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sync,
                        contentDescription = stringResource(R.string.lyrics_offset_adjust),
                        tint = if (lyrics.userOffsetMs != 0L) palette.onPrimaryContainer else Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    if (lyrics.userOffsetMs != 0L) {
                        Text(
                            text = stringResource(R.string.lyrics_offset_ms, lyrics.userOffsetMs),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = palette.onPrimaryContainer
                        )
                    }
                }
            }

            // Manual Search Button (48dp touch target)
            IconButton(
                onClick = onOpenManualSearch,
                modifier = Modifier.size(48.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.14f),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.lyrics_manual_search),
                            tint = Color.White,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsOffsetControlBar(
    offsetMs: Long,
    palette: ArtworkPalette,
    onOffsetChange: (Long) -> Unit,
    onResetOffset: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = palette.primaryContainer.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${stringResource(R.string.lyrics_offset_adjust)}: ${stringResource(R.string.lyrics_offset_ms, offsetMs)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = palette.onPrimaryContainer
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { onOffsetChange(-500L) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = palette.onPrimaryContainer.copy(alpha = 0.12f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "-.5s",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.onPrimaryContainer
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { onOffsetChange(500L) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = palette.onPrimaryContainer.copy(alpha = 0.12f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "+.5s",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.onPrimaryContainer
                            )
                        }
                    }
                }

                if (offsetMs != 0L) {
                    IconButton(
                        onClick = onResetOffset,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = palette.onPrimaryContainer.copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.lyrics_offset_reset),
                                    tint = palette.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

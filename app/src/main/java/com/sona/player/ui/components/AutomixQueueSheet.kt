package com.sona.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.SavedTransition
import com.sona.player.automix.TrackAnalysis
import com.sona.player.data.model.Song
import com.sona.player.ui.theme.ArtworkPalette
import kotlin.math.roundToInt

/**
 * Material 3 Expressive Queue & Automix Chain sheet.
 * Displays the track queue along with preserved DJ transition connectors between every pair of tracks,
 * updating seamlessly as songs transition from track to track.
 */
@Composable
fun AutomixQueueSheet(
    queue: List<Song>,
    currentIndex: Int,
    currentSong: Song?,
    automixState: AutomixTransitionState,
    palette: ArtworkPalette,
    onTrackClick: (Int) -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onToggleAutomix: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Scroll to the active track when the sheet opens
    LaunchedEffect(Unit) {
        if (currentIndex in queue.indices) {
            val targetScroll = (currentIndex * 2 - 1).coerceAtLeast(0)
            listState.scrollToItem(targetScroll)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = palette.surfaceContainerTint,
            tonalElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .clickable(enabled = false) {} // Prevent clicks inside sheet from dismissing
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.28f))
                    )
                }

                // Header Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Queue & Automix Chain",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        val subtitle = if (automixState.isEnabled) {
                            "${queue.size} tracks • ${automixState.mode.displayName} Active"
                        } else {
                            "${queue.size} tracks • Standard Queue"
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                    }

                    // Automix Toggle Chip inside Queue Header
                    val chipBg by animateColorAsState(
                        targetValue = if (automixState.isEnabled) {
                            palette.secondaryContainer
                        } else {
                            Color.White.copy(alpha = 0.10f)
                        },
                        animationSpec = tween(250),
                        label = "QueueAutomixChipBg"
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = chipBg,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable(onClick = onToggleAutomix)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = if (automixState.isEnabled) palette.secondaryAccent else Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = automixState.mode.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    }

                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.10f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close Queue",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Queue Track List + Preserved Transition Connectors
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    queue.forEachIndexed { index, track ->
                        val isCurrent = (index == currentIndex) || (track.id == currentSong?.id && index == currentIndex)
                        val isPlayed = index < currentIndex
                        val analysis = automixState.analyzedTracks[track.id]
                            ?: if (isCurrent) automixState.currentAnalysis else null

                        item(key = "track_${track.id}_$index") {
                            QueueTrackRow(
                                index = index,
                                song = track,
                                analysis = analysis,
                                isCurrent = isCurrent,
                                isPlayed = isPlayed,
                                isTransitionTarget = automixState.isTransitioning && automixState.transitionTargetSong?.id == track.id,
                                canMoveUp = index > currentIndex + 1 && !automixState.isTransitioning,
                                canMoveDown = index > currentIndex && index < queue.lastIndex && !automixState.isTransitioning,
                                canRemove = index != currentIndex && !automixState.isTransitioning,
                                palette = palette,
                                onClick = { onTrackClick(index) },
                                onMoveUp = { onMoveTrack(index, index - 1) },
                                onMoveDown = { onMoveTrack(index, index + 1) },
                                onRemove = { onRemoveTrack(index) }
                            )
                        }

                        // Render DJ Transition Connector between track[index] and track[index + 1]
                        if (automixState.isEnabled && index < queue.lastIndex) {
                            val nextSongInQueue = queue[index + 1]
                            val key = SavedTransition.key(track.id, nextSongInQueue.id)
                            // Planned in the background by the engine; never plan on the UI thread here.
                            val savedTransition = automixState.savedTransitions[key]

                            val isLiveTransitionHere = automixState.isTransitioning && (
                                (automixState.transitionFromSongId == track.id && automixState.transitionTargetSong?.id == nextSongInQueue.id) ||
                                    (index == currentIndex && automixState.transitionTargetSong?.id == nextSongInQueue.id)
                                )

                            item(key = "transition_${track.id}_${nextSongInQueue.id}_$index") {
                                TransitionConnectorItem(
                                    savedTransition = savedTransition,
                                    isLiveTransition = isLiveTransitionHere,
                                    liveProgress = if (isLiveTransitionHere) automixState.transitionProgress else 0f,
                                    isPastTransition = index < currentIndex && !isLiveTransitionHere,
                                    palette = palette
                                )
                            }
                        } else if (index < queue.lastIndex) {
                            item(key = "spacer_$index") {
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    index: Int,
    song: Song,
    analysis: TrackAnalysis?,
    isCurrent: Boolean,
    isPlayed: Boolean,
    isTransitionTarget: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    val containerColor = when {
        isCurrent -> palette.primaryContainer.copy(alpha = 0.55f)
        isTransitionTarget -> palette.secondaryContainer.copy(alpha = 0.50f)
        isPlayed -> Color.White.copy(alpha = 0.04f)
        else -> Color.White.copy(alpha = 0.08f)
    }

    val borderModifier = when {
        isCurrent -> Modifier.border(1.5.dp, palette.primaryAccent.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
        isTransitionTarget -> Modifier.border(1.dp, palette.secondaryAccent.copy(alpha = 0.75f), RoundedCornerShape(18.dp))
        else -> Modifier
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Cover art with status overlay
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (song.albumArtUri != null) {
                    AsyncImage(
                        model = song.albumArtUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (isCurrent) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Now Playing",
                            tint = palette.primaryAccent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Track Title, Artist & Acoustic Badges
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent) {
                        Text(
                            text = "NOW • ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = palette.primaryAccent
                        )
                    } else if (isTransitionTarget) {
                        Text(
                            text = "MIXING IN • ",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = palette.secondaryAccent
                        )
                    }
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent || isTransitionTarget) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isPlayed) Color.White.copy(alpha = 0.50f) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${song.artist} • ${song.formattedDuration}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = if (isPlayed) 0.38f else 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (analysis != null) {
                        val bpmInt = analysis.bpm.roundToInt()
                        val energyPct = (analysis.energy * 100f).roundToInt()
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "$bpmInt BPM • E:$energyPct%",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                fontWeight = FontWeight.SemiBold,
                                color = palette.secondaryAccent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Reorder & Remove Actions for Upcoming Queue Tracks
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canMoveUp) {
                    IconButton(
                        onClick = onMoveUp,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Move Up",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (canMoveDown) {
                    IconButton(
                        onClick = onMoveDown,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Move Down",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (canRemove) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove from queue",
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else if (!isCurrent && !isPlayed) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Renders the preserved DJ transition node connecting Track[i] -> Track[i+1].
 */
@Composable
private fun TransitionConnectorItem(
    savedTransition: SavedTransition?,
    isLiveTransition: Boolean,
    liveProgress: Float,
    isPastTransition: Boolean,
    palette: ArtworkPalette
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical timeline connector line
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(if (isLiveTransition) 38.dp else 26.dp)
                .background(
                    if (isLiveTransition) palette.secondaryAccent
                    else if (isPastTransition) Color.White.copy(alpha = 0.18f)
                    else palette.primaryAccent.copy(alpha = 0.45f)
                )
        )

        Spacer(modifier = Modifier.width(12.dp))

        val pillBg = when {
            isLiveTransition -> palette.secondaryContainer.copy(alpha = 0.88f)
            isPastTransition -> Color.White.copy(alpha = 0.04f)
            else -> palette.secondaryContainer.copy(alpha = 0.32f)
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = pillBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (isPastTransition && savedTransition?.isCompleted == true) {
                                Icons.Rounded.CheckCircle
                            } else {
                                Icons.Rounded.AutoAwesome
                            },
                            contentDescription = null,
                            tint = if (isPastTransition) {
                                Color.White.copy(alpha = 0.45f)
                            } else {
                                palette.secondaryAccent
                            },
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))

                        val labelText = when {
                            isLiveTransition -> {
                                val pct = (liveProgress * 100f).roundToInt()
                                val stage = when {
                                    liveProgress < 0.44f -> "Live EQ High-Pass Entry ($pct%)"
                                    liveProgress < 0.55f -> "Live DJ Bass Swap! ($pct%)"
                                    else -> "Live LPF Filter Sweep ($pct%)"
                                }
                                stage
                            }
                            savedTransition != null -> {
                                val bpmFrom = savedTransition.fromBpm.roundToInt()
                                val bpmTo = savedTransition.toBpm.roundToInt()
                                val cueSec = savedTransition.cueInMs / 1000L
                                val cueTag = if (cueSec >= 2L) " • In @ 0:%02d".format(cueSec) else ""
                                val prefix = if (isPastTransition) "Mixed: " else ""
                                "$prefix${savedTransition.transitionTool.shortBadge} • $bpmFrom→$bpmTo BPM$cueTag"
                            }
                            else -> "Smart DJ EQ Transition (Analyzing...)"
                        }

                        Text(
                            text = labelText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            fontWeight = if (isLiveTransition) FontWeight.Bold else FontWeight.Medium,
                            color = if (isPastTransition) Color.White.copy(alpha = 0.50f) else Color.White.copy(alpha = 0.90f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (savedTransition != null) {
                        Text(
                            text = "${savedTransition.matchScorePercent}% Match",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            color = if (isPastTransition) {
                                Color.White.copy(alpha = 0.40f)
                            } else {
                                palette.secondaryAccent
                            }
                        )
                    }
                }

                if (isLiveTransition) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { liveProgress.coerceIn(0f, 1f) },
                        color = palette.secondaryAccent,
                        trackColor = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}

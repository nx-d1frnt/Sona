package com.sona.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.data.model.Song
import com.sona.player.ui.theme.rememberArtworkPalette

@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    progress: Float,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    automixState: AutomixTransitionState? = null
) {
    AnimatedVisibility(
        visible = song != null,
        modifier = modifier
    ) {
        if (song == null) return@AnimatedVisibility

        val palette = rememberArtworkPalette(song.albumArtUri)
        val targetSong = automixState?.let { it.transitionTargetSong ?: it.nextMatchedSong }
        val targetPalette = rememberArtworkPalette(targetSong?.albumArtUri)

        val progressMotion = rememberAutomixProgressMotion(
            progress = progress,
            primaryColor = palette.primaryAccent,
            isTransitioning = automixState?.isTransitioning == true,
            targetSongId = targetSong?.id
        )

        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = palette.surfaceContainerTint
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clickable(onClick = onClick)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(palette.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        if (song.albumArtUri != null) {
                            AsyncImage(
                                model = song.albumArtUri,
                                contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.content_desc_album_art),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(48.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.MusicNote,
                                contentDescription = null,
                                tint = palette.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    FilledIconButton(
                        onClick = onPlayPauseClick,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = palette.primaryAccent,
                            contentColor = palette.onPrimaryAccent
                        ),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = androidx.compose.ui.res.stringResource(if (isPlaying) com.sona.player.R.string.action_pause else com.sona.player.R.string.action_play)
                        )
                    }

                    IconButton(onClick = onNextClick) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_next_track)
                        )
                    }
                }

                val mixProgress by animateFloatAsState(
                    targetValue = if (automixState?.isTransitioning == true) {
                        automixState.transitionProgress.coerceIn(0f, 1f)
                    } else 0f,
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                    label = "MiniMixProgress"
                )
                val incomingColor = if (automixState?.isTransitioning == true && targetSong != null) targetPalette.primaryAccent else palette.primaryAccent
                val startColor by animateColorAsState(
                    targetValue = lerp(progressMotion.outgoingColor, incomingColor, mixProgress),
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                    label = "MiniMixStart"
                )
                val endColor by animateColorAsState(
                    targetValue = incomingColor,
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                    label = "MiniMixEnd"
                )
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(palette.primaryContainer.copy(alpha = 0.5f), cornerRadius = radius)
                    val activeWidth = size.width * progressMotion.progress.value.coerceIn(0f, 1f)
                    if (activeWidth > 0f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(listOf(startColor, endColor), endX = activeWidth),
                            topLeft = Offset.Zero,
                            size = Size(activeWidth, size.height),
                            cornerRadius = radius
                        )
                    }
                }
            }
        }
    }
}

package com.sona.player.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sona.player.R
import kotlinx.coroutines.delay

@Composable
internal fun SonaLiveBanner(
    songId: Long,
    showUpcoming: Boolean,
    isPlaying: Boolean,
    isTransitioning: Boolean,
    transitionTitle: String?,
    nextTitle: String?,
    repeatOne: Boolean,
    smartShuffleEnabled: Boolean,
    isAnalyzing: Boolean,
    smartQueueUpdatedAtMs: Long,
    containerColor: Color,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Latch the first event: continued tail updates cannot rearm a visible or expired notice.
    var noticeStartedAtMs by remember(songId, smartShuffleEnabled) { mutableStateOf(0L) }
    LaunchedEffect(songId, smartShuffleEnabled, smartQueueUpdatedAtMs) {
        noticeStartedAtMs = queueNoticeStart(noticeStartedAtMs, smartQueueUpdatedAtMs)
    }
    var showQueueUpdate by remember(songId, smartShuffleEnabled) { mutableStateOf(false) }
    LaunchedEffect(songId, smartShuffleEnabled, noticeStartedAtMs) {
        val remaining = queueNoticeRemainingMs(noticeStartedAtMs, SystemClock.elapsedRealtime())
        showQueueUpdate = remaining > 0L
        if (remaining > 0L) delay(remaining)
        showQueueUpdate = false
    }
    val message = resolveSonaLiveMessage(
        isPlaying, isTransitioning, transitionTitle, nextTitle, repeatOne,
        smartShuffleEnabled, isAnalyzing, showQueueUpdate, showUpcoming
    )
    // Stable state keys keep position updates from restarting the notice timeout.
    val noticeEvent = if (message?.kind == SonaLiveKind.QUEUE_UPDATED) noticeStartedAtMs else 0L
    var noticeVisible by remember(songId, message, noticeEvent) { mutableStateOf(true) }
    LaunchedEffect(songId, message, noticeEvent) {
        if (message != null && message.kind != SonaLiveKind.MIXING && message.kind != SonaLiveKind.UP_NEXT) {
            delay(6_000L)
            noticeVisible = false
        }
    }
    // Keep the outgoing content during the disappearance animation.
    var lastMessage by remember { mutableStateOf<SonaLiveMessage?>(null) }
    LaunchedEffect(message) { if (message != null) lastMessage = message }
    AnimatedVisibility(
        visible = message != null && noticeVisible,
        enter = fadeIn(tween(250)) + expandVertically(tween(250)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(250))
    ) {
        val displayedMessage = message ?: lastMessage ?: return@AnimatedVisibility
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = containerColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (displayedMessage.kind) {
                        SonaLiveKind.MIXING -> Icons.Rounded.AutoAwesome
                        SonaLiveKind.QUEUE_UPDATED, SonaLiveKind.SMART_SHUFFLE -> Icons.Rounded.Shuffle
                        SonaLiveKind.PAUSED -> Icons.Rounded.Pause
                        SonaLiveKind.REPEATING -> Icons.Rounded.RepeatOne
                        SonaLiveKind.UP_NEXT -> Icons.Rounded.SkipNext
                        SonaLiveKind.QUEUE_END -> Icons.AutoMirrored.Rounded.QueueMusic
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                AnimatedContent(
                    targetState = displayedMessage,
                    transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                    label = "SonaLiveMessage",
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
                ) { current ->
                    Text(
                        text = when (current.kind) {
                            SonaLiveKind.MIXING -> stringResource(R.string.sona_live_mixing, current.trackTitle.orEmpty())
                            SonaLiveKind.QUEUE_UPDATED -> stringResource(R.string.sona_live_queue_updated)
                            SonaLiveKind.PAUSED -> stringResource(R.string.sona_live_paused)
                            SonaLiveKind.SMART_SHUFFLE -> stringResource(R.string.sona_live_smart_shuffle)
                            SonaLiveKind.REPEATING -> stringResource(R.string.sona_live_repeating, current.trackTitle.orEmpty())
                            SonaLiveKind.UP_NEXT -> stringResource(R.string.sona_live_up_next, current.trackTitle.orEmpty())
                            SonaLiveKind.QUEUE_END -> stringResource(R.string.sona_live_queue_end)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

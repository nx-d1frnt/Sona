package com.sona.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Routes transport commands from MediaSession through the dual-deck engine. */
@Suppress("OVERRIDE_DEPRECATION")
@OptIn(UnstableApi::class)
internal class AutomixSessionPlayer(
    deck: Player,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onStop: () -> Unit,
    private val onSeek: (Long) -> Unit,
    private val onSeekToItem: (Int, Long) -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit
) : ForwardingPlayer(deck) {
    override fun play() = onPlay()
    override fun pause() = onPause()
    override fun stop() = onStop()
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) onPlay() else onPause()
    }

    override fun seekTo(positionMs: Long) = onSeek(positionMs)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = onSeekToItem(mediaItemIndex, positionMs)
    override fun seekToDefaultPosition() = onSeek(C.TIME_UNSET)
    override fun seekToDefaultPosition(mediaItemIndex: Int) = onSeekToItem(mediaItemIndex, C.TIME_UNSET)
    override fun seekBack() = onSeek((currentPosition - seekBackIncrement).coerceAtLeast(0L))
    override fun seekForward() {
        val target = currentPosition + seekForwardIncrement
        onSeek(if (duration == C.TIME_UNSET) target else target.coerceAtMost(duration))
    }

    override fun seekToNext() = onNext()
    override fun seekToNextMediaItem() = onNext()
    override fun seekToNextWindow() = onNext()
    override fun seekToPrevious() = onPrevious()
    override fun seekToPreviousMediaItem() = onPrevious()
    override fun seekToPreviousWindow() = onPrevious()
}

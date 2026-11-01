package com.sona.player.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AutomixSessionPlayerTest {
    private val commands = mutableListOf<String>()
    private val deck = Proxy.newProxyInstance(
        Player::class.java.classLoader, arrayOf(Player::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getCurrentPosition" -> 10_000L
            "getDuration" -> 12_000L
            "getSeekBackIncrement", "getSeekForwardIncrement" -> 5_000L
            else -> error("Transport command reached raw deck: ${method.name}")
        }
    } as Player

    private fun player() = AutomixSessionPlayer(
        deck,
        onPlay = { commands += "play" },
        onPause = { commands += "pause" },
        onStop = { commands += "stop" },
        onSeek = { commands += "seek:$it" },
        onSeekToItem = { index, position -> commands += "item:$index:$position" },
        onNext = { commands += "next" },
        onPrevious = { commands += "previous" }
    )

    @Test fun pauseAndPlayCommandsReachEngine() {
        val player = player()
        player.pause()
        player.playWhenReady = false
        player.play()
        player.playWhenReady = true
        player.stop()
        assertEquals(listOf("pause", "pause", "play", "play", "stop"), commands)
    }

    @Test fun nextAndPreviousCommandsReachEngine() {
        val player = player()
        player.seekToNext()
        player.seekToNextMediaItem()
        player.seekToPrevious()
        player.seekToPreviousMediaItem()
        assertEquals(listOf("next", "next", "previous", "previous"), commands)
    }

    @Test fun seeksReachEngineAndRespectDuration() {
        val player = player()
        player.seekTo(123L)
        player.seekTo(2, 456L)
        player.seekToDefaultPosition()
        player.seekToDefaultPosition(3)
        player.seekBack()
        player.seekForward()
        assertEquals(listOf("seek:123", "item:2:456", "seek:${C.TIME_UNSET}",
            "item:3:${C.TIME_UNSET}", "seek:5000", "seek:12000"), commands)
    }
}

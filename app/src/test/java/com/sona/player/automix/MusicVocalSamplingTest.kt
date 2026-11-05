package com.sona.player.automix

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MusicVocalSamplingTest {
    @Test
    fun stableVocalsNeedFewerCallsAndNeverIntroduceAPause() = runBlocking {
        val calls = mutableListOf<Int>()
        val scores = MusicVocalSampling.scores(8) { index -> calls += index; 0.9f }
        assertEquals(listOf(0, 2, 4, 6, 7), calls)
        assertTrue(scores.all { it >= 0.75f })
    }

    @Test
    fun probesQuietNeighboursSoAShortVocalEntryIsNotSkipped() = runBlocking {
        val calls = mutableSetOf<Int>()
        val scores = MusicVocalSampling.scores(5) { index ->
            calls += index
            if (index == 1) 0.95f else 0.05f
        }
        assertEquals(5, calls.size)
        assertEquals(0.95f, scores[1], 0.001f)
        assertEquals(0.05f, scores[3], 0.001f)
    }

    @Test
    fun uncertainBoundaryGetsMeasuredRatherThanFilledWithStrongNeighbour() = runBlocking {
        val source = floatArrayOf(0.9f, 0.2f, 0.6f)
        assertArrayEquals(source, MusicVocalSampling.scores(3) { source[it] }, 0.001f)
    }

    @Test
    fun emptyAndShortWindowsKeepAllAvailableMeasurements() = runBlocking {
        for (count in 0..2) {
            val calls = mutableListOf<Int>()
            val scores = MusicVocalSampling.scores(count) { calls += it; it.toFloat() }
            assertEquals(count, calls.size)
            assertArrayEquals(FloatArray(count) { it.toFloat() }, scores, 0.001f)
        }
    }
}

package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test

class YamnetVocalDetectorTest {

    @Test
    fun vocalScoreDetectsVocalClasses() {
        val scores = FloatArray(521)
        // Set speech/singing indices (e.g. index 0, 1, 2)
        scores[0] = 0.85f
        scores[10] = 0.99f // non-vocal class
        val score = YamnetVocalDetector.vocalScore(scores)
        assertEquals(0.85f, score, 0.001f)
    }

    @Test
    fun vocalScoreHandlesEmptyOrZero() {
        val scores = FloatArray(521)
        val score = YamnetVocalDetector.vocalScore(scores)
        assertEquals(0f, score, 0.001f)
    }

    @Test
    fun vocalScoreCoercesNaNAndBounds() {
        val scores = FloatArray(521)
        scores[0] = Float.NaN
        scores[1] = 1.5f
        val score = YamnetVocalDetector.vocalScore(scores)
        assertEquals(1.0f, score, 0.001f)
    }
}

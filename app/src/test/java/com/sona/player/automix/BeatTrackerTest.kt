package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BeatTrackerTest {
    private fun clicks(bpm: Double, offsetMs: Long = 137L, seconds: Int = 18): AudioWindow {
        val samples = FloatArray(seconds * 16_000)
        var beat = offsetMs / 1000.0
        while (beat < seconds - 0.1) {
            val start = (beat * 16_000).roundToInt()
            for (i in 0 until 800) {
                samples[start + i] += (sin(2 * PI * 100 * i / 16_000) * exp(-i / 180.0) * 0.8).toFloat()
            }
            beat += 60.0 / bpm
        }
        return AudioWindow(100_000L, samples)
    }

    @Test fun findsTempoAndPhaseOnRegularKickPattern() {
        for (bpm in listOf(90.0, 120.0, 140.0)) {
            val section = requireNotNull(BeatTracker.analyze(clicks(bpm)))
            assertEquals(bpm, section.bpm.toDouble(), 2.5)
            assertTrue("confidence=${section.confidence} at $bpm", section.confidence >= 0.65f)
            assertTrue(section.beatsMs.size >= 12)
            for (time in section.beatsMs) {
                val cycles = (time - 100_137L) / (60_000 / bpm)
                assertTrue("Beat phase at $time", abs(cycles - cycles.roundToInt()) * (60_000 / bpm) < 40)
            }
        }
    }

    @Test fun silenceAndSustainedToneDoNotProduceConfidentBeatGrid() {
        assertNull(BeatTracker.analyze(AudioWindow(0, FloatArray(160_000))))
        val tone = FloatArray(160_000) { (sin(2 * PI * 440 * it / 16_000) * 0.3).toFloat() }
        assertTrue((BeatTracker.analyze(AudioWindow(0, tone))?.confidence ?: 0f) < 0.65f)
    }

    @Test fun shortAudioRemainsUnknown() {
        assertNull(BeatTracker.analyze(AudioWindow(0, FloatArray(16_000))))
    }
}

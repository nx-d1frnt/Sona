package com.sona.player.automix

import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class LoudnessAnalyzerTest {
    private fun tone(amplitude: Float, frequency: Double = 1000.0, leadingSilence: Int = 0): AudioWindow {
        val samples = FloatArray((4 + leadingSilence) * 16_000)
        for (i in leadingSilence * 16_000 until samples.size) {
            samples[i] = (amplitude * sin(2 * PI * frequency * (i - leadingSilence * 16_000) / 16_000)).toFloat()
        }
        return AudioWindow(0, samples)
    }

    @Test fun silenceAndInsufficientSamplesRemainUnknown() {
        assertNull(LoudnessAnalyzer.analyze(AudioWindow(0, FloatArray(16_000))))
        assertNull(LoudnessAnalyzer.analyze(AudioWindow(0, FloatArray(100))))
    }

    @Test fun estimatesReferenceMonoSineAndTracksAmplitudeInDecibels() {
        val quiet = requireNotNull(LoudnessAnalyzer.analyze(tone(0.1f)))
        val loud = requireNotNull(LoudnessAnalyzer.analyze(tone(0.2f)))
        assertEquals(-23.0f, quiet.lufs, 0.25f)
        assertEquals(6.0206f, loud.lufs - quiet.lufs, 0.01f)
        assertEquals(0.2f, loud.samplePeak, 0.001f)
    }

    @Test fun silenceGatingDoesNotTurnAQuietIntroIntoHugeGain() {
        val normal = requireNotNull(LoudnessAnalyzer.analyze(tone(0.1f)))
        val padded = requireNotNull(LoudnessAnalyzer.analyze(tone(0.1f, leadingSilence = 8)))
        assertEquals(normal.lufs, padded.lufs, 0.4f)
    }

    @Test fun weightingReducesLowFrequencyContribution() {
        val mid = requireNotNull(LoudnessAnalyzer.analyze(tone(0.1f)))
        val bass = requireNotNull(LoudnessAnalyzer.analyze(tone(0.1f, 30.0)))
        assertTrue(bass.lufs < mid.lufs - 5)
    }

    @Test fun gainCorrectionMatchesLevelsAndRespectsHeadroomAndBounds() {
        val quiet = LoudnessSection(0, 1000, -24f, 0.2f)
        val loud = LoudnessSection(0, 1000, -12f, 0.8f)
        val a = quiet.lufs + 20 * log10(LoudnessNormalizer.gain(quiet))
        val b = loud.lufs + 20 * log10(LoudnessNormalizer.gain(loud))
        assertEquals(a, b, 0.02f)
        assertTrue(LoudnessNormalizer.gain(quiet.copy(samplePeak = 0.9f)) * 0.9f <= 0.891f)
        assertEquals(1f, LoudnessNormalizer.gain(null as LoudnessSection?), 0f)
        assertEquals(1f, LoudnessNormalizer.gain(quiet.copy(lufs = Float.NaN)), 0f)
        assertEquals(1f, LoudnessNormalizer.gain(quiet.copy(lufs = -65f)), 0f)
    }
}

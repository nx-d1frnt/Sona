package com.sona.player.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class KeyDetectorTest {
    private fun tones(midiNotes: IntArray, seconds: Int = 12, rate: Int = 16_000): AudioWindow {
        val samples = FloatArray(seconds * rate)
        for (note in midiNotes) {
            val freq = 440.0 * 2.0.pow((note - 69) / 12.0)
            for (i in samples.indices) samples[i] += (0.1 * sin(2 * PI * freq * i / rate)).toFloat()
        }
        return AudioWindow(0L, samples, rate)
    }

    @Test
    fun camelotMapping() {
        assertEquals("8B", MusicalKey(0, false, 1f).camelotLabel)  // C major
        assertEquals("8A", MusicalKey(9, true, 1f).camelotLabel)   // A minor
        assertEquals("9B", MusicalKey(7, false, 1f).camelotLabel)  // G major
        assertEquals("5A", MusicalKey(0, true, 1f).camelotLabel)   // C minor
        assertEquals(MusicalKey(4, true, 0.5f), MusicalKey.fromIndex(16, 0.5f))
    }

    @Test
    fun compatibilityOrdering() {
        val c = MusicalKey(0, false, 1f)
        assertEquals(1f, MusicalKey.compatibility(c, c))
        assertEquals(0.9f, MusicalKey.compatibility(c, MusicalKey(9, true, 1f)))  // relative minor
        assertEquals(0.9f, MusicalKey.compatibility(c, MusicalKey(7, false, 1f))) // fifth
        assertTrue(MusicalKey.compatibility(c, MusicalKey(6, false, 1f)) < 0.5f)  // tritone
    }

    @Test
    fun detectsCMajorTriadWithScaleTones() {
        // C major triad over several octaves plus D/F/G/A colour tones.
        val window = tones(intArrayOf(48, 52, 55, 60, 64, 67, 72, 62, 65, 69))
        val chroma = DoubleArray(12)
        val frames = KeyDetector.accumulate(window, chroma)
        val key = KeyDetector.estimate(chroma, frames)
        assertNotNull(key)
        // C major or its relative (A minor) share Camelot 8.
        assertEquals(8, key!!.camelotNumber)
    }

    @Test
    fun detunedA432TrackStillMapsToCorrectCamelotKey() {
        // Detuned by -31.8 cents (A4 = 432 Hz instead of 440 Hz)
        val rate = 16_000
        val samples = FloatArray(12 * rate)
        val midiNotes = intArrayOf(60, 64, 67, 72, 62, 65, 69)
        for (note in midiNotes) {
            val freq = 432.0 * 2.0.pow((note - 69) / 12.0)
            for (i in samples.indices) samples[i] += (0.1 * sin(2 * PI * freq * i / rate)).toFloat()
        }
        val chroma = DoubleArray(12)
        val frames = KeyDetector.accumulate(AudioWindow(0L, samples, rate), chroma)
        val key = KeyDetector.estimate(chroma, frames)
        assertNotNull(key)
        assertEquals(8, key!!.camelotNumber)
    }

    @Test
    fun detectsEMajorPopProgressionWithoutMediantMinorBias() {
        // E - G#m - C#m - A (e.g. Adela - "Ain't In LA").
        // Previously misclassified by raw Krumhansl as 1A (G# minor) due to mediant-minor bias.
        val rate = 16_000
        val chordSeconds = 3
        val chords = listOf(
            intArrayOf(64, 68, 71), // E major: E4, G#4, B4
            intArrayOf(68, 71, 75), // G# minor: G#4, B4, D#5
            intArrayOf(61, 64, 68), // C# minor: C#4, E4, G#4
            intArrayOf(57, 61, 64)  // A major: A3, C#4, E4
        )
        val samples = FloatArray(chords.size * chordSeconds * rate)
        for ((idx, chord) in chords.withIndex()) {
            val startSample = idx * chordSeconds * rate
            val endSample = (idx + 1) * chordSeconds * rate
            for (note in chord) {
                val freq = 440.0 * 2.0.pow((note - 69) / 12.0)
                for (i in startSample until endSample) {
                    val t = (i - startSample).toDouble()
                    samples[i] += (0.1 * sin(2 * PI * freq * t / rate)).toFloat()
                }
            }
        }
        val chroma = DoubleArray(12)
        val frames = KeyDetector.accumulate(AudioWindow(0L, samples, rate), chroma)
        val key = KeyDetector.estimate(chroma, frames)
        assertNotNull(key)
        assertEquals("12B", key!!.camelotLabel) // 12B = E major
    }
}

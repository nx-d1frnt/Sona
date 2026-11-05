package com.sona.player.automix

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class TrackEnergyAnalyzerTest {
    private fun tone(amplitude: Float = 0.2f, frequency: Double = 180.0) =
        AudioWindow(0, FloatArray(8 * 16_000) { i ->
            (amplitude * sin(2 * PI * frequency * i / 16_000)).toFloat()
        })

    private fun drums(amplitude: Float = 0.6f) =
        AudioWindow(0, FloatArray(8 * 16_000) { i ->
            val time = i / 16_000.0
            val decay = exp(-(time % 0.5) * 25)
            (amplitude * decay * (0.6 * sin(2 * PI * 120 * time) +
                0.4 * sin(2 * PI * 2600 * time))).toFloat()
        })

    private fun score(vararg windows: AudioWindow) =
        requireNotNull(TrackEnergyAnalyzer.analyze(windows.toList()))

    @Test fun silenceIsZeroAndMissingOrInvalidAudioIsUnknown() {
        assertEquals(0f, score(AudioWindow(0, FloatArray(16_000))), 0f)
        assertNull(TrackEnergyAnalyzer.analyze(emptyList()))
        assertNull(TrackEnergyAnalyzer.analyze(listOf(AudioWindow(0, FloatArray(100)))))
        assertNull(TrackEnergyAnalyzer.analyze(listOf(AudioWindow(0, FloatArray(16_000) { Float.NaN }))))
    }

    @Test fun masteringGainHasLimitedInfluence() {
        // A 12 dB mastering change should have a balanced influence without drowning out acoustic features.
        val quiet = score(drums(0.15f))
        val loud = score(drums(0.6f))
        assertTrue(loud >= quiet)
        assertTrue("quiet=$quiet loud=$loud", loud - quiet < 0.15f)
    }

    @Test fun loudSustainedBassDoesNotOutrankPercussiveMusic() {
        val sustained = score(tone(0.8f))
        val percussive = score(drums(0.3f))
        assertTrue("sustained=$sustained percussive=$percussive", percussive > sustained + 0.1f)
        assertTrue(sustained < 0.65f)
    }

    @Test fun quietMiddleSectionDoesNotDetermineWholeTrackEnergy() {
        val reference = score(drums(), drums(), drums())
        val breakdown = score(drums(), drums(0.03f), drums())
        assertEquals(reference, breakdown, 0.05f)
    }

    @Test fun structuralWindowsHaveEqualInfluenceAndSilentPaddingIsIgnored() {
        val shortTone = tone().let { it.copy(samples = it.samples.copyOf(16_000)) }
        assertEquals(score(tone(), drums()), score(shortTone, drums()), 0.01f)
        assertEquals(score(drums()), score(AudioWindow(0, FloatArray(16_000)), drums()), 0f)
        val padded = AudioWindow(0, FloatArray(4 * 16_000) + drums().samples)
        assertEquals(score(drums()), score(padded), 0.06f)
    }

    @Test fun staleEnergyMakesCachedAnalysisIncompleteWithoutDiscardingOtherFeatures() {
        val current = TrackAnalysis(
            songId = 1L, bpm = 120f, energy = 0.6f, cueInMs = 0,
            mixOutMs = 180_000, crossfadeDurationMs = 10_000, durationMs = 200_000,
            loudnessAnalyzed = true, keyAnalyzed = true
        )
        assertTrue(current.isComplete)
        assertFalse(current.copy(energyAnalysisVersion = 0).isComplete)
        assertFalse(current.copy(energyAnalysisVersion = 1).isComplete)
        assertFalse(current.copy(energyAnalysisVersion = 2).isComplete)
        assertFalse(current.copy(energy = Float.NaN).isComplete)
        assertFalse(current.copy(energy = 1.1f).isComplete)
        assertTrue(current.copy(energyAnalysisVersion = 0).keyAnalyzed)
    }

    @Test fun energeticDanceGrooveReachesHighEnergyScore() {
        val danceBanger = AudioWindow(0, FloatArray(8 * 16_000) { i ->
            val time = i / 16_000.0
            val kick = exp(-(time % 0.468) * 20) * sin(2 * PI * 65 * time)
            val bass = 0.45 * sin(2 * PI * 55 * time)
            val clap = if ((time % 0.937) in 0.468..0.550) 0.35 * sin(2 * PI * 2200 * time) else 0.0
            (0.85f * (kick + bass + clap)).toFloat().coerceIn(-1f, 1f)
        })
        val energy = requireNotNull(TrackEnergyAnalyzer.analyze(listOf(danceBanger), 128f))
        assertTrue("energy=$energy should be >= 0.70f", energy >= 0.70f)
    }
}

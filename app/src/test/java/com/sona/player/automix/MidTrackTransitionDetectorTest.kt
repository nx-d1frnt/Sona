package com.sona.player.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidTrackTransitionDetectorTest {
    private fun detect(envelope: FloatArray) = MidTrackTransitionDetector.detect(
        coreFrames = envelope, coreStartMs = 144_000L, frameDurationMs = 25L,
        estimatedBpm = 120f, durationMs = 300_000L, mixOutMs = 285_000L
    )

    private fun envelope(energy: (Int) -> Float) = FloatArray(1680) { energy(it / 20) }

    @Test fun constantEnergyIsNotEvidenceForAnEarlyMix() {
        assertEquals(0f, detect(envelope { 0.4f }).confidence, 0f)
    }

    @Test fun risingEnergyIsNotEvidenceForAnEarlyMix() {
        assertEquals(0f, detect(envelope { if (it < 48) 0.15f else 0.4f }).confidence, 0f)
    }

    @Test fun briefDipCannotBeMistakenForSustainedBreakdown() {
        assertEquals(0f, detect(envelope { if (it == 48) 0.05f else 0.4f }).confidence, 0f)
    }

    @Test fun shallowDropDoesNotQualifyForAutoMidMix() {
        assertTrue(detect(envelope { if (it < 48) 0.4f else 0.36f }).confidence < 0.8f)
    }

    @Test fun insufficientDataHasNoConfidence() {
        assertEquals(0f, detect(FloatArray(40) { 0.4f }).confidence, 0f)
    }

    @Test fun quietNoiseHasNoConfidenceEvenWithLargeRelativeDrop() {
        assertEquals(0f, detect(envelope { if (it < 48) 0.01f else 0.001f }).confidence, 0f)
    }

    @Test fun sustainedStrongDropSuppliesEvidenceAndMeasuredTimestamp() {
        val result = detect(envelope { if (it < 48) 0.4f else 0.12f })
        assertTrue(result.confidence >= 0.8f)
        assertEquals(166_000L, result.positionMs)
        val track = TrackAnalysis(
            songId = 1L, bpm = 120f, energy = 0.72f, cueInMs = 0L,
            mixOutMs = 285_000L, crossfadeDurationMs = 11_000L, durationMs = 300_000L,
            midMixOutMs = result.positionMs, midMixConfidence = result.confidence,
            vocalSections = listOf(VocalSection(160_000L, 200_000L, 0.05f))
        )
        assertEquals(AutomixMode.QUICK_MID_MIX,
            track.resolveEffectiveMode(AutomixMode.AUTO_DJ, track.copy(songId = 2L)))
    }
}

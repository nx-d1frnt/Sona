package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test

class MusicalCueSelectorTest {
    private fun track() = TrackAnalysis(1, 120f, 0.7f, 0, 285_000, 11_000, 300_000)
    private fun beats(start: Long, end: Long, bpm: Float = 120f, confidence: Float = 0.95f) =
        BeatSection(start, end, bpm, confidence, (start..end step (60_000 / bpm).toLong()).toList())

    @Test fun unknownVocalCoverageDoesNotMeanInstrumental() {
        assertNull(MusicalCueSelector.vocalRisk(track(), 0, 10_000))
        val partial = track().copy(vocalSections = listOf(VocalSection(0, 4000, 0f), VocalSection(0, 4000, 0f)))
        assertNull(MusicalCueSelector.vocalRisk(partial, 0, 10_000))
    }

    @Test fun overlappingWindowsUseConservativeScoreWithoutDoubleCounting() {
        val track = track().copy(vocalSections = listOf(VocalSection(0, 10_000, 0.1f), VocalSection(5000, 10_000, 0.9f)))
        assertEquals(0.5f, requireNotNull(MusicalCueSelector.vocalRisk(track, 0, 10_000)), 0.001f)
    }

    @Test fun incomingCuePrefersNearbyInstrumentalBeat() {
        val track = track().copy(beatSections = listOf(beats(0, 24_000)), vocalSections = listOf(
            VocalSection(0, 4000, 0.95f), VocalSection(4000, 24_000, 0.03f)))
        val cue = MusicalCueSelector.incoming(track, 0, 8500)
        assertTrue(cue in 4000L..5000L)
        assertTrue(MusicalCueSelector.isMeasuredBeat(track, cue))
    }

    @Test fun outgoingCueCanWaitForVocalsToFinishWithoutMovingIntoMiddleOfSong() {
        val track = track().copy(beatSections = listOf(beats(270_000, 300_000)), vocalSections = listOf(
            VocalSection(270_000, 287_000, 0.9f), VocalSection(287_000, 300_000, 0.02f)))
        val cue = track.effectiveMixOutMs(AutomixMode.AUTO_DJ)
        assertTrue(cue >= 287_000L)
        assertTrue(cue <= track.durationMs - track.crossfadeDurationMs - 500L)
    }

    @Test fun tempoSyncNeedsReliableLocalBeatGridsForBothTracks() {
        val from = track().copy(beatSections = listOf(beats(270_000, 300_000)))
        val to = track().copy(songId = 2, beatSections = listOf(beats(0, 24_000, 124f)))
        val ratios = MusicalCueSelector.tempoRatios(from, to, AutomixMode.AUTO_DJ)
        assertTrue(ratios.first > 1f)
        assertTrue(ratios.second < 1f)
        assertEquals(1f to 1f, MusicalCueSelector.tempoRatios(from, to.copy(beatSections = emptyList()), AutomixMode.AUTO_DJ))
        assertEquals(1f to 1f, MusicalCueSelector.tempoRatios(from,
            to.copy(beatSections = listOf(beats(0, 24_000, 124f, 0.2f))), AutomixMode.AUTO_DJ))
    }

    @Test fun beatStartDoesNotExtrapolateOutsideMeasuredWindow() {
        val track = track().copy(beatSections = listOf(beats(270_000, 280_000)))
        assertEquals(275_500L, MusicalCueSelector.nextBeatForStart(track, 275_010, 275_110))
        assertNull(MusicalCueSelector.nextBeatForStart(track, 290_000, 290_100))
    }

    @Test fun modelScoreIncludesSingingAndRapAndDoesNotCountGenericMusic() {
        val scores = FloatArray(521)
        scores[132] = 0.99f // generic Music is not evidence of vocals
        assertEquals(0f, YamnetVocalDetector.vocalScore(scores), 0f)
        scores[31] = 0.8f // rap
        assertEquals(0.8f, YamnetVocalDetector.vocalScore(scores), 0f)
        scores[24] = 0.95f // singing
        assertEquals(0.95f, YamnetVocalDetector.vocalScore(scores), 0f)
    }
}

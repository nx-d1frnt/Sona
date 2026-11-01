package com.sona.player.automix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDjModeTest {
    private val current = TrackAnalysis(
        songId = 1L, bpm = 120f, energy = 0.72f,
        cueInMs = 500L, mixOutMs = 285_000L, crossfadeDurationMs = 11_000L,
        durationMs = 300_000L, midMixOutMs = 180_000L, deepCueInMs = 12_000L,
        midMixConfidence = 0.95f,
        vocalSections = listOf(VocalSection(150_000L, 220_000L, 0.05f))
    )
    private val next = current.copy(songId = 2L, bpm = 122f, energy = 0.70f)

    private fun assertOutro(track: TrackAnalysis, incoming: TrackAnalysis? = next) {
        assertEquals(AutomixMode.CLASSIC_OUTRO, track.resolveEffectiveMode(AutomixMode.AUTO_DJ, incoming))
        assertEquals(track.mixOutMs, track.effectiveMixOutMs(AutomixMode.AUTO_DJ, incoming))
    }

    @Test fun normalLengthSongsKeepOutroEvenWhenLoudAndTempoMatched() {
        for (duration in listOf(120_000L, 149_999L)) {
            val track = current.copy(
                durationMs = duration, midMixOutMs = (duration * 0.60).toLong(),
                mixOutMs = duration - 14_000L
            )
            assertEquals(AutomixMode.CLASSIC_OUTRO, track.resolveEffectiveMode(AutomixMode.AUTO_DJ, next))
            val cue = track.effectiveMixOutMs(AutomixMode.AUTO_DJ, next)
            assertTrue(cue >= (duration * 0.70).toLong())
            assertTrue(cue in track.mixOutMs - 1500L..track.mixOutMs + 4000L)
        }
    }

    @Test fun longEnergeticSongsNeedMeasuredStructure() {
        assertOutro(current.copy(midMixConfidence = 0f))
        assertOutro(current.copy(midMixConfidence = 0.59f))
        assertOutro(current.copy(midMixConfidence = Float.NaN))
    }

    @Test fun vocalsOrUnknownVocalCoveragePreventAutomaticEarlyExit() {
        assertOutro(current.copy(vocalSections = emptyList()))
        assertOutro(current.copy(vocalSections = listOf(VocalSection(150_000L, 220_000L, 0.9f))))
    }

    @Test fun incompletePairAnalysisKeepsOutro() = assertOutro(current, null)

    @Test fun earlyPointMustLeaveEnoughMusicAndTimeBeforeOutro() {
        assertOutro(current.copy(midMixOutMs = 149_999L))
        assertOutro(current.copy(midMixOutMs = 155_000L))
        assertOutro(current.copy(midMixOutMs = 220_000L))
        assertOutro(current.copy(mixOutMs = 204_000L))
    }

    @Test fun incompatiblePairKeepsOutro() {
        assertOutro(current, next.copy(bpm = Float.NaN))
        assertOutro(current, next.copy(bpm = 0f))
    }

    @Test fun measuredLongTrackWithCompatiblePairCanMixEarly() {
        assertEquals(AutomixMode.QUICK_MID_MIX, current.resolveEffectiveMode(AutomixMode.AUTO_DJ, next))
        assertEquals(current.midMixOutMs, current.effectiveMixOutMs(AutomixMode.AUTO_DJ, next))
        assertEquals(AutomixMode.QUICK_MID_MIX,
            current.resolveEffectiveMode(AutomixMode.AUTO_DJ, next.copy(bpm = 60f)))
    }

    @Test fun ordinaryThreeMinuteTrackCanUseMeasuredBreakdown() {
        val track = current.copy(durationMs = 195_000L, midMixOutMs = 112_000L,
            mixOutMs = 180_000L, midMixConfidence = 0.65f,
            vocalSections = listOf(VocalSection(112_000L, 116_000L, 0.9f),
                VocalSection(116_000L, 120_000L, 0.05f)))
        assertEquals(AutomixMode.QUICK_MID_MIX, track.resolveEffectiveMode(AutomixMode.AUTO_DJ, next))
        assertEquals(116_000L, track.effectiveMixOutMs(AutomixMode.AUTO_DJ, next))
    }

    private fun steadyGroove() = current.copy(durationMs = 180_000L,
        mixOutMs = 165_000L, midMixOutMs = 100_000L, midMixConfidence = 0f,
        beatSections = listOf(BeatSection(94_000L, 130_000L, 120f, 0.5f,
            (94_000L..130_000L step 500L).toList())),
        vocalSections = listOf(VocalSection(94_000L, 130_000L, 0.05f)))

    @Test fun steadyGrooveWithoutEnergyDropCanMixInMiddle() {
        val track = steadyGroove()
        assertEquals(AutomixMode.QUICK_MID_MIX, track.resolveEffectiveMode(AutomixMode.AUTO_DJ, next))
        assertEquals(108_000L, track.effectiveMixOutMs(AutomixMode.AUTO_DJ, next))
        assertEquals(0.6f, track.effectiveZoneFraction(AutomixMode.AUTO_DJ, next), 0.001f)
    }

    @Test fun tempoAndEnergyChangeDoNotVetoMeasuredMiddleExit() {
        val track = steadyGroove()
        val incoming = next.copy(bpm = 160f, energy = 0.35f)
        assertEquals(AutomixMode.QUICK_MID_MIX, track.resolveEffectiveMode(AutomixMode.AUTO_DJ, incoming))
        assertTrue(TransitionPlanner.plan(track, incoming, AutomixMode.AUTO_DJ).mixOutMs in 94_000L..126_000L)
    }

    @Test fun rhythmFallbackNeedsBothMeasuredBeatsAndVocalPause() {
        val track = steadyGroove()
        assertOutro(track.copy(beatSections = emptyList()))
        assertOutro(track.copy(beatSections = track.beatSections.map { it.copy(confidence = 0.2f) }))
        assertOutro(track.copy(vocalSections = emptyList()))
        assertOutro(track.copy(vocalSections = listOf(VocalSection(94_000L, 130_000L, 0.9f))))
    }

    @Test fun twoAndHalfMinuteTrackCanUseMiddleAndLeavesOutroSpace() {
        val track = steadyGroove().copy(durationMs = 150_000L, mixOutMs = 135_000L,
            beatSections = listOf(BeatSection(80_000L, 115_000L, 120f, 0.7f,
                (80_000L..115_000L step 500L).toList())),
            vocalSections = listOf(VocalSection(80_000L, 115_000L, 0.05f)))
        val cue = track.effectiveMixOutMs(AutomixMode.AUTO_DJ, next)
        assertEquals(90_000L, cue)
        assertTrue(track.mixOutMs - cue >= 25_000L)
    }

    @Test fun explicitMidModeRemainsAvailableWithoutConfidence() {
        val track = current.copy(durationMs = 180_000L, midMixConfidence = 0f)
        assertEquals(AutomixMode.QUICK_MID_MIX, track.resolveEffectiveMode(AutomixMode.QUICK_MID_MIX))
        assertEquals(track.midMixOutMs, track.effectiveMixOutMs(AutomixMode.QUICK_MID_MIX))
        assertEquals(AutomixMode.OFF, track.resolveEffectiveMode(AutomixMode.OFF))
    }

    @Test fun autoOutroDoesNotSkipDeepIntoIncomingIntroJustBecauseOutgoingIsLoud() {
        val outgoing = current.copy(midMixConfidence = 0f)
        assertEquals(next.cueInMs, next.effectiveCueInMs(AutomixMode.AUTO_DJ, outgoing.energy, outgoing))
        assertEquals(next.deepCueInMs, next.effectiveCueInMs(AutomixMode.QUICK_MID_MIX, outgoing.energy, outgoing))
    }

    @Test fun queueTransitionMetadataUsesTheSameModeAsPlayback() {
        val outgoing = current.copy(midMixConfidence = 0f)
        val saved = AutomixQueueSelector.buildSavedTransition(
            outgoing.songId, next.songId, outgoing, next, AutomixMode.AUTO_DJ
        )
        assertEquals(outgoing.crossfadeDurationMs, saved.overlapDurationMs)
        assertEquals(next.cueInMs, saved.cueInMs)
    }
}

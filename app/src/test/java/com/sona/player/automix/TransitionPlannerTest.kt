package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test

class TransitionPlannerTest {
    private fun beats(start: Long, end: Long, bpm: Float) = BeatSection(start, end, bpm, 0.95f,
        (start..end step (60_000 / bpm).toLong()).toList())
    private fun from() = TrackAnalysis(1, 120f, 0.7f, 0L, 285_000L, 11_000L, 300_000L,
        beatSections = listOf(beats(270_000, 300_000, 120f)),
        vocalSections = listOf(VocalSection(270_000, 300_000, 0.05f)))
    private fun to(bpm: Float = 120f) = TrackAnalysis(2, bpm, 0.7f, 0L, 285_000L, 11_000L, 300_000L,
        beatSections = listOf(beats(0, 24_000, bpm)), vocalSections = listOf(VocalSection(0, 24_000, 0.05f)))

    @Test fun vocalHandoffPreservesOutgoingPhraseAndFindsInstrumentalLead() {
        val outgoing = from().copy(vocalSections = listOf(
            VocalSection(270_000, 287_000, 0.9f), VocalSection(287_000, 300_000, 0.02f)))
        val incoming = to().copy(vocalSections = listOf(VocalSection(0, 4000, 0.01f), VocalSection(4000, 24_000, 0.9f)))
        val plan = TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ)
        assertEquals(AutomixTransitionTool.VOCAL_HANDOFF, plan.tool)
        assertEquals(285_000L, plan.mixOutMs)
        assertEquals(0L, plan.cueInMs)
        assertTrue(plan.handoffFraction * plan.durationMs >= 2000f)
        val gains = TransitionEnvelopes.gains(plan.tool, plan.handoffFraction - 0.02f, plan.handoffFraction)
        assertEquals(1f, gains.first, 0.001f)
        assertTrue(gains.second <= 0.12f)
    }

    @Test fun unknownOrVocalIncomingLeadCannotEnableHandoff() {
        val outgoing = from().copy(vocalSections = listOf(VocalSection(270_000, 287_000, 0.9f),
            VocalSection(287_000, 300_000, 0.01f)))
        assertNotEquals(AutomixTransitionTool.VOCAL_HANDOFF,
            TransitionPlanner.plan(outgoing, to().copy(vocalSections = emptyList()), AutomixMode.AUTO_DJ).tool)
        assertNotEquals(AutomixTransitionTool.VOCAL_HANDOFF,
            TransitionPlanner.plan(outgoing, to().copy(vocalSections = listOf(VocalSection(0, 24_000, 0.9f))), AutomixMode.AUTO_DJ).tool)
    }

    @Test fun incompatibleInstrumentalGroovesUseShortMeasuredBeatCrossfade() {
        val plan = TransitionPlanner.plan(from(), to(160f), AutomixMode.AUTO_DJ)
        assertEquals(AutomixTransitionTool.BEAT_CUT, plan.tool)
        assertTrue(plan.durationMs in 1000L..2000L)
        assertTrue(MusicalCueSelector.isMeasuredBeat(from(), plan.mixOutMs))
        assertTrue(MusicalCueSelector.isMeasuredBeat(to(160f), plan.cueInMs))
        assertEquals(1f, plan.incomingTempoRatio, 0f)
    }

    @Test fun incompatibleVocalGroovesUseFiniteTempoEchoAtNaturalSpeed() {
        val outgoing = from().copy(vocalSections = listOf(VocalSection(270_000, 300_000, 0.9f)))
        val incoming = to(160f).copy(vocalSections = listOf(VocalSection(0, 24_000, 0.9f)))
        val plan = TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ)
        assertEquals(AutomixTransitionTool.ECHO_OUT, plan.tool)
        assertEquals(500f, plan.echoDelayMs, 0.01f)
        assertTrue(plan.durationMs in 2500L..4500L)
        assertEquals(1f, plan.outgoingTempoRatio, 0f)
        assertEquals(250f, TransitionPlanner.plan(outgoing.copy(energy = 0.9f), incoming, AutomixMode.AUTO_DJ).echoDelayMs, 0.01f)
    }

    @Test fun unreliableBeatsCannotEnableBeatCutOrTempoEcho() {
        val plan = TransitionPlanner.plan(from().copy(beatSections = emptyList()), to(160f), AutomixMode.AUTO_DJ)
        assertNotEquals(AutomixTransitionTool.BEAT_CUT, plan.tool)
        assertNotEquals(AutomixTransitionTool.ECHO_OUT, plan.tool)
    }

    @Test fun moderateMeasuredRhythmAndEnergyChangeEnableShortMixWithoutTempoStretch() {
        val outgoing = from().copy(beatSections = from().beatSections.map { it.copy(confidence = 0.5f) })
        val incoming = to(122f).copy(energy = 0.45f)
        val plan = TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ)
        assertEquals(AutomixTransitionTool.BEAT_CUT, plan.tool)
        assertEquals(1f, plan.outgoingTempoRatio, 0f)
        assertEquals(1f, plan.incomingTempoRatio, 0f)
    }

    @Test fun moderateVocalConflictEnablesEchoEvenWithMatchedTempos() {
        val outgoing = from().copy(vocalSections = listOf(VocalSection(270_000, 300_000, 0.45f)))
        val incoming = to().copy(vocalSections = listOf(VocalSection(0, 24_000, 0.45f)))
        assertEquals(AutomixTransitionTool.ECHO_OUT,
            TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ).tool)
    }

    @Test fun changingNextTrackRebuildsOutgoingPointForItsVocalEntry() {
        val outgoing = from().copy(durationMs = 360_000L, mixOutMs = 300_000L,
            vocalSections = listOf(VocalSection(270_000, 300_000, 0.01f),
                VocalSection(300_000, 304_000, 0.9f), VocalSection(304_000, 308_000, 0.01f),
                VocalSection(308_000, 360_000, 0.9f)))
        val instrumental = to()
        val vocal = to().copy(songId = 3, vocalSections = listOf(VocalSection(0, 24_000, 0.9f)))
        val first = TransitionPlanner.plan(outgoing, instrumental, AutomixMode.AUTO_DJ)
        val second = TransitionPlanner.plan(outgoing, vocal, AutomixMode.AUTO_DJ)
        assertNotEquals(first.mixOutMs, second.mixOutMs)
        assertTrue((MusicalCueSelector.vocalRisk(outgoing, second.mixOutMs, second.mixOutMs + 3000L) ?: 1f) < 0.20f)
        assertTrue(kotlin.math.abs(second.mixOutMs - first.mixOutMs) <= 6000L)
        assertSame(first, TransitionPlanner.plan(outgoing, instrumental, AutomixMode.AUTO_DJ))
    }

    @Test fun queueMetadataAndPlaybackUseTheSameToolCueAndDuration() {
        val outgoing = from()
        val incoming = to(160f)
        val plan = TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ)
        val saved = AutomixQueueSelector.buildSavedTransition(1, 2, outgoing, incoming, AutomixMode.AUTO_DJ)
        assertEquals(plan.tool, saved.transitionTool)
        assertEquals(plan.durationMs, saved.overlapDurationMs)
        assertEquals(plan.cueInMs, saved.cueInMs)
    }

    @Test fun refreshedAnalysisInvalidatesPreviewWithoutReanalysingAudio() {
        val outgoing = from()
        val incoming = to(160f)
        val first = TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ)
        assertSame(first, TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ))
        val refreshed = outgoing.copy(beatSections = emptyList())
        assertNotEquals(first.tool, TransitionPlanner.plan(refreshed, incoming, AutomixMode.AUTO_DJ).tool)
    }

    @Test fun manualSkipUsesActualPositionAndDoesNotBorrowAnUnmeasuredFutureBeatGrid() {
        val plan = TransitionPlanner.plan(from(), to(160f), AutomixMode.AUTO_DJ, 5500L, 60_000L)
        assertEquals(60_000L, plan.mixOutMs)
        assertEquals(1f, plan.outgoingTempoRatio, 0f)
        assertNotEquals(AutomixTransitionTool.BEAT_CUT, plan.tool)
    }

    @Test fun envelopeValuesStayFiniteBoundedAndIncomingEndsAtFullLevel() {
        for (tool in AutomixTransitionTool.entries) {
            for (step in 0..100) {
                val (out, incoming) = TransitionEnvelopes.gains(tool, step / 100f)
                assertTrue(out.isFinite() && out in 0f..1f)
                assertTrue(incoming.isFinite() && incoming in 0f..1f)
            }
            assertEquals(1f, TransitionEnvelopes.gains(tool, 1f).second, 0.001f)
        }
    }
}

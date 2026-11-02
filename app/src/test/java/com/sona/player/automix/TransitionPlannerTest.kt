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

    @Test fun autoMiddleExitKeepsIncomingInIntroDespiteAttractiveCore() {
        val outgoing = from().copy(midMixConfidence = 0f,
            beatSections = from().beatSections + beats(156_000, 210_000, 120f),
            vocalSections = listOf(VocalSection(156_000, 210_000, 0.05f)))
        val incoming = to().copy(deepCueInMs = 8000L,
            beatSections = to().beatSections + beats(144_000, 180_000, 120f),
            vocalSections = to().vocalSections + VocalSection(144_000, 180_000, 0.05f))
        val plan = TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ)
        assertTrue(plan.mixOutMs in 156_000L..210_000L)
        assertTrue(plan.cueInMs in 0L..24_000L)
        assertNotEquals(AutomixTransitionTool.ECHO_OUT, plan.tool)
        assertEquals(plan.cueInMs, AutomixQueueSelector.buildSavedTransition(1, 2,
            outgoing, incoming, AutomixMode.AUTO_DJ).cueInMs)
    }

    @Test fun deepEntryCannotReuseAnAlreadyPassedMiddleExit() {
        val outgoing = from().copy(beatSections = from().beatSections + beats(156_000, 210_000, 120f),
            vocalSections = listOf(VocalSection(156_000, 210_000, 0.05f),
            VocalSection(270_000, 300_000, 0.05f)))
        val initial = TransitionPlanner.plan(outgoing, to(), AutomixMode.AUTO_DJ)
        assertTrue(initial.mixOutMs < 210_000L)
        val deepEntry = TransitionPlanner.plan(outgoing, to(), AutomixMode.AUTO_DJ, entryPositionMs = 175_000L)
        assertTrue(deepEntry.mixOutMs >= 220_000L)
    }

    @Test fun incomingCoreLeavesSpaceForAtLeastFortyFiveSecondsBeforeOutro() {
        val incoming = to().copy(mixOutMs = 160_000L,
            vocalSections = listOf(VocalSection(140_000, 180_000, 0.01f)))
        val cue = MusicalCueSelector.djIncoming(incoming, 0L, 8500L, 0.8f)
        assertTrue(cue <= 115_000L)
    }

    @Test fun everyModeAndManualNextBoundLegacyDeepCuesToIntro() {
        val incoming = to().copy(cueInMs = 140_000L, deepCueInMs = 150_000L,
            vocalSections = listOf(VocalSection(140_000L, 180_000L, 0.01f)))
        for (mode in listOf(AutomixMode.AUTO_DJ, AutomixMode.CLASSIC_OUTRO, AutomixMode.QUICK_MID_MIX)) {
            assertTrue(TransitionPlanner.plan(from(), incoming, mode).cueInMs in 0L..24_000L)
            assertTrue(TransitionPlanner.plan(from(), incoming, mode, 5500L, 60_000L).cueInMs in 0L..24_000L)
        }
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

    @Test fun confidentBeatsWithEnergyJumpKeepClubHighPassRiserInsteadOfBeatCut() {
        val outgoing = from().copy(energy = 0.55f)
        val incoming = to(124f).copy(energy = 0.85f)
        val plan = TransitionPlanner.plan(outgoing, incoming, AutomixMode.AUTO_DJ)
        assertEquals(AutomixTransitionTool.HIGH_PASS_RISER_SLAM, plan.tool)
        assertTrue(plan.durationMs >= 8500L)
    }

    @Test fun driftingOrUncertainTempoFallsBackToSilkyEqualPowerCrossfade() {
        val liveJazzOut = from().copy(
            energy = 0.72f,
            beatSections = listOf(BeatSection(270_000, 300_000, 128f, 0.30f, listOf(271_000, 272_000)))
        )
        val bangerIn = to(128f).copy(energy = 0.88f)
        val plan = TransitionPlanner.plan(liveJazzOut, bangerIn, AutomixMode.AUTO_DJ)
        assertEquals(AutomixTransitionTool.SILKY_WARM_ISOLATOR, plan.tool)
        assertEquals(1f, plan.outgoingTempoRatio, 0f)
        assertEquals(1f, plan.incomingTempoRatio, 0f)
    }

    @Test fun smartShufflePenalizesSameArtistRepetition() {
        fun song(id: Long, artist: String): com.sona.player.data.model.Song {
            val cls = Class.forName("sun.misc.Unsafe")
            val unsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val inst = cls.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, com.sona.player.data.model.Song::class.java) as com.sona.player.data.model.Song
            for ((name, value) in listOf("id" to id, "title" to "Track $id", "artist" to artist, "album" to "Album", "durationMs" to 200_000L)) {
                com.sona.player.data.model.Song::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                    set(inst, value)
                }
            }
            return inst
        }
        val currentSong = song(1L, "Artist A")
        val sameArtistSong = song(2L, "Artist A")
        val diffArtistSong = song(3L, "Artist B")
        val a1 = from().copy(songId = 1L, bpm = 120f, energy = 0.70f)
        val a2 = to(120f).copy(songId = 2L, bpm = 120f, energy = 0.70f)
        val a3 = to(121f).copy(songId = 3L, bpm = 121f, energy = 0.71f)
        val map = mapOf(1L to a1, 2L to a2, 3L to a3)
        val match = AutomixQueueSelector.findBestNextTrack(
            currentSong = currentSong,
            currentAnalysis = a1,
            queue = listOf(currentSong, sameArtistSong, diffArtistSong),
            currentIndex = 0,
            analysisProvider = { map[it] }
        )
        assertNotNull(match)
        assertEquals(3L, match!!.song.id)
    }
}

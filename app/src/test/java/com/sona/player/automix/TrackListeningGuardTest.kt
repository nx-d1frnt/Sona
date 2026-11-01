package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test

class TrackListeningGuardTest {
    @Test fun deepEntryDoesNotCountSkippedAudioAsHeard() {
        val guard = TrackListeningGuard()
        guard.enter(180_000L)
        for (tick in 0..224) guard.tick(tick * 200L, true)
        assertFalse(guard.canTransition)
        guard.tick(45_000L, true)
        assertTrue(guard.canTransition)
    }

    @Test fun pausesBufferingAndSuspendedTrackerDoNotSatisfyMinimum() {
        val guard = TrackListeningGuard()
        guard.enter(0L)
        guard.tick(0L, true)
        guard.tick(200L, false)
        guard.tick(120_000L, false)
        guard.tick(120_200L, true)
        assertEquals(0L, guard.playedMs)
        guard.tick(240_000L, true)
        assertEquals(1000L, guard.playedMs)
    }

    @Test fun newDeckResetsResidenceEvenWhenEnteringEarlierInSource() {
        val guard = TrackListeningGuard()
        guard.enter(150_000L)
        for (tick in 0..250) guard.tick(tick * 200L, true)
        assertTrue(guard.canTransition)
        guard.enter(140_000L)
        assertFalse(guard.canTransition)
        assertEquals(0L, guard.playedMs)
        assertEquals(140_000L, guard.entryPositionMs)
    }
    @Test fun sparseScreenOffTicksCountAudioThatActuallyAdvanced() {
        val guard = TrackListeningGuard()
        guard.enter(0L)
        guard.tick(0, true, 0)
        guard.tick(15_000, true, 15_000)
        guard.tick(30_000, true, 30_000)
        guard.tick(45_000, true, 45_000)
        assertEquals(45_000L, guard.playedMs)
        assertTrue(guard.canTransition)
    }

    @Test fun stalledAudioDoesNotCountLongWallClockGap() {
        val guard = TrackListeningGuard()
        guard.enter(0)
        guard.tick(0, true, 0)
        guard.tick(60_000, true, 200)
        assertEquals(200L, guard.playedMs)
        assertFalse(guard.canTransition)
    }

    @Test fun seekInvalidatesPositionBaselineWithoutCountingSkippedAudio() {
        val guard = TrackListeningGuard()
        guard.enter(0)
        guard.tick(0, true, 0)
        guard.tick(5000, true, 5000)
        guard.discontinuity()
        guard.tick(5200, true, 180_000)
        guard.tick(5400, true, 180_200)
        assertEquals(5200L, guard.playedMs)
        assertFalse(guard.canTransition)
    }

    @Test fun shortOrSeekedTrackCanStillMixAtOutroWithoutEnablingEarlyChain() {
        val guard = TrackListeningGuard()
        guard.enter(0)
        guard.tick(0, true, 0)
        guard.tick(5000, true, 5000)
        assertFalse(guard.allowsAutomaticTransition(60_000))
        assertTrue(guard.allowsAutomaticTransition(10_000))
        guard.enter(150_000)
        guard.tick(6000, true, 150_000)
        assertFalse(guard.allowsAutomaticTransition(10_000))
    }

    @Test fun tempoSyncCountsPlaybackTimeRatherThanSourceTime() {
        val guard = TrackListeningGuard()
        guard.enter(0)
        guard.tick(0, true, 0, 1.05f)
        guard.tick(20_000, true, 21_000, 1.05f)
        assertTrue(guard.playedMs in 19_999L..20_000L)
    }

    @Test fun displayedPointAccountsForMinimumListeningAndOutroException() {
        assertEquals(155_000L, TrackListeningGuard.earliestPosition(149_000, 167_000, 38_000, 1f))
        assertEquals(90_000L, TrackListeningGuard.earliestPosition(60_000, 180_000, 15_000, 1f))
        assertEquals(149_000L, TrackListeningGuard.earliestPosition(149_000, 167_000, 50_000, 1f))
    }

    @Test fun manualScrubBypassesListeningMinimumOnlyUntilNextTrack() {
        val guard = TrackListeningGuard()
        guard.enter(0)
        guard.onManualSeek()
        assertTrue(guard.allowsAutomaticTransition(60_000))
        assertEquals(0L, guard.playedMs)
        guard.enter(15_000)
        assertFalse(guard.allowsAutomaticTransition(60_000))
    }

}

package com.sona.player.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonaLiveStateTest {
    private fun message(
        playing: Boolean = true,
        mixing: Boolean = false,
        target: String? = "Incoming",
        next: String? = "Next in queue",
        repeat: Boolean = false,
        smart: Boolean = false,
        analyzing: Boolean = false,
        updated: Boolean = false,
        upcoming: Boolean = true
    ) = resolveSonaLiveMessage(playing, mixing, target, next, repeat, smart, analyzing, updated, upcoming)

    @Test fun mixingTakesPriorityOverQueueUpdatesAndAnalysis() {
        assertEquals(SonaLiveMessage(SonaLiveKind.MIXING, "Incoming"),
            message(mixing = true, smart = true, analyzing = true, updated = true))
    }

    @Test fun updateTakesPriorityOverBackgroundAnalysis() {
        assertEquals(SonaLiveKind.QUEUE_UPDATED,
            message(smart = true, analyzing = true, updated = true, upcoming = false)?.kind)
    }

    @Test fun disablingSmartShuffleHidesItsMessages() {
        assertEquals(SonaLiveKind.UP_NEXT, message(analyzing = true, updated = true)?.kind)
    }

    @Test fun pausedTransitionDoesNotClaimToBeMixing() {
        assertEquals(SonaLiveKind.PAUSED, message(playing = false, mixing = true)?.kind)
    }

    @Test fun analysisIsShownOnlyForSmartShuffle() {
        assertEquals(SonaLiveKind.SMART_SHUFFLE, message(smart = true, analyzing = true, upcoming = false)?.kind)
    }

    @Test fun expiredUpdateReturnsToActualNextTrack() {
        assertEquals(SonaLiveMessage(SonaLiveKind.UP_NEXT, "Next in queue"), message(smart = true))
    }

    @Test fun missingTransitionTargetFallsBackToQueue() {
        assertEquals(SonaLiveKind.UP_NEXT, message(mixing = true, target = null)?.kind)
    }

    @Test fun repeatOneHasItsOwnMessage() {
        assertEquals(SonaLiveMessage(SonaLiveKind.REPEATING, "Next in queue"), message(repeat = true))
    }

    @Test fun emptyUpcomingQueueHasNoInventedNextTrack() {
        assertEquals(SonaLiveKind.QUEUE_END, message(next = null)?.kind)
    }

    @Test fun bannerIsHiddenDuringOrdinaryPlayback() {
        assertNull(message(upcoming = false))
        assertNull(message(smart = true, upcoming = false))
    }

    @Test fun imminentTrackTakesPriorityOverBackgroundNotices() {
        assertEquals(SonaLiveMessage(SonaLiveKind.UP_NEXT, "Next in queue"),
            message(smart = true, analyzing = true, updated = true))
    }

    @Test fun upcomingStartsExactlyFifteenSecondsBeforeChange() {
        assertFalse(isSonaLiveUpcoming(84_999L, 100_000L, 1f))
        assertTrue(isSonaLiveUpcoming(85_000L, 100_000L, 1f))
        assertTrue(isSonaLiveUpcoming(99_999L, 100_000L, 1f))
    }

    @Test fun upcomingDoesNotUseAnUnknownOrPassedChangePoint() {
        assertFalse(isSonaLiveUpcoming(0L, 0L, 1f))
        assertFalse(isSonaLiveUpcoming(100_001L, 100_000L, 1f))
    }

    @Test fun upcomingWindowAccountsForPlaybackTempo() {
        assertTrue(isSonaLiveUpcoming(82_000L, 100_000L, 1.2f))
        assertFalse(isSonaLiveUpcoming(82_000L, 100_000L, 1f))
    }
    @Test fun continualQueueBatchesCannotExtendNoticeOrReplayItAfterExpiry() {
        var start = 0L
        for (now in 1_000L..30_000L step 500L) {
            start = queueNoticeStart(start, now)
            assertEquals(1_000L, start)
            assertEquals((7_000L - now).coerceAtLeast(0L), queueNoticeRemainingMs(start, now))
        }
        assertEquals(0L, queueNoticeRemainingMs(start, 30_000L))
    }

    @Test fun expiredEventDoesNotReplayOnReopeningAndNewSessionCanNotify() {
        val start = queueNoticeStart(0L, 1_000L)
        assertEquals(0L, queueNoticeRemainingMs(start, 10_000L))
        val reset = queueNoticeStart(start, 0L)
        assertEquals(6_000L, queueNoticeRemainingMs(queueNoticeStart(reset, 12_000L), 12_000L))
        assertEquals(0L, queueNoticeRemainingMs(0L, 12_000L))
        assertEquals(0L, queueNoticeRemainingMs(12_000L, 11_000L))
    }

}

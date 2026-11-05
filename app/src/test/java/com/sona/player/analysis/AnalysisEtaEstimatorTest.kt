package com.sona.player.analysis

import org.junit.Assert.*
import org.junit.Test

class AnalysisEtaEstimatorTest {

    @Test
    fun returnsNullBeforeAnyProgress() {
        var currentTime = 1000L
        val estimator = AnalysisEtaEstimator(totalPending = 10, timeProvider = { currentTime })
        // Initially, 0 newly completed tracks
        assertNull(estimator.updateProgress(0))
    }

    @Test
    fun returnsZeroWhenAllCompleted() {
        var currentTime = 1000L
        val estimator = AnalysisEtaEstimator(totalPending = 10, timeProvider = { currentTime })
        currentTime += 5000L
        assertEquals(0L, estimator.updateProgress(10))
    }

    @Test
    fun returnsZeroWhenTotalPendingZero() {
        var currentTime = 1000L
        val estimator = AnalysisEtaEstimator(totalPending = 0, timeProvider = { currentTime })
        assertEquals(0L, estimator.updateProgress(0))
    }

    @Test
    fun calculatesAccurateEtaWithLinearProgress() {
        var currentTime = 0L
        val estimator = AnalysisEtaEstimator(totalPending = 10, timeProvider = { currentTime })

        // 1st track finishes at 2000 ms (2 sec per track). 9 remaining -> raw ETA = 18,000 ms
        currentTime = 2000L
        val eta1 = estimator.updateProgress(1)
        assertNotNull(eta1)
        assertEquals(18_000L, eta1)

        // 2nd track finishes at 4000 ms (2 sec per track). 8 remaining -> raw ETA = 16,000 ms
        // Smoothed = 0.3 * 16000 + 0.7 * 18000 = 4800 + 12600 = 17,400 ms
        currentTime = 4000L
        val eta2 = estimator.updateProgress(2)
        assertNotNull(eta2)
        assertEquals(17_400L, eta2)
    }

    @Test
    fun pausesAndResumesWithoutCountingPausedDuration() {
        var currentTime = 0L
        val estimator = AnalysisEtaEstimator(totalPending = 10, timeProvider = { currentTime })

        currentTime = 1000L // 1 second active
        estimator.pause()

        currentTime = 61_000L // 60 seconds paused!
        estimator.resume()

        currentTime = 62_000L // 1 more second active -> total active time = 2000 ms
        val eta = estimator.updateProgress(1)

        // 1 track in 2000 ms active time -> 9 remaining = 18,000 ms ETA
        assertNotNull(eta)
        assertEquals(18_000L, eta)
    }
}

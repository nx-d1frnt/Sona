package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test

class SmartQueueProgressTest {
    private fun track(id: Long) = TrackAnalysis(id, 120f, 0.7f, 0L, 285_000L, 11_000L, 300_000L)

    @Test fun deckChangeKeepsCompletedTailInsteadOfStartingFromScratch() {
        val tracks = (1L..2000L).map(::track)
        val progress = SmartQueueProgress()
        for ((from, to) in tracks.take(1000).zipWithNext()) {
            progress.record(from, to, AutomixMode.AUTO_DJ, 8000L, 8000L)
        }
        // After track 1 finishes, all previously matched links starting at track 2 remain reusable.
        var reused = 0
        for ((from, to) in tracks.drop(1).zipWithNext()) {
            if (progress.nextEntryIfUnchanged(from, to, AutomixMode.AUTO_DJ, 8000L) == null) break
            reused++
        }
        assertEquals(998, reused)
        assertNull(progress.nextEntryIfUnchanged(tracks[999], tracks[1000], AutomixMode.AUTO_DJ, 8000L))
    }

    @Test fun newAnalysisModeSeekAndDifferentNeighbourInvalidateLink() {
        val from = track(1)
        val to = track(2)
        val progress = SmartQueueProgress()
        progress.record(from, to, AutomixMode.AUTO_DJ, 0L, 8000L)
        assertEquals(8000L, progress.nextEntryIfUnchanged(from, to, AutomixMode.AUTO_DJ, 0L))
        assertNull(progress.nextEntryIfUnchanged(from.copy(bpm = 125f), to, AutomixMode.AUTO_DJ, 0L))
        assertNull(progress.nextEntryIfUnchanged(from, to.copy(energy = 0.9f), AutomixMode.AUTO_DJ, 0L))
        assertNull(progress.nextEntryIfUnchanged(from, to, AutomixMode.CLASSIC_OUTRO, 0L))
        assertNull(progress.nextEntryIfUnchanged(from, to, AutomixMode.AUTO_DJ, 100_000L))
        assertNull(progress.nextEntryIfUnchanged(from, track(3), AutomixMode.AUTO_DJ, 0L))
    }

    @Test fun zeroEntryIsReusableAndObsoleteLinksAreRemoved() {
        val from = track(1)
        val to = track(2)
        val progress = SmartQueueProgress()
        progress.record(from, to, AutomixMode.AUTO_DJ, 0L, 0L)
        assertEquals(0L, progress.nextEntryIfUnchanged(from, to, AutomixMode.AUTO_DJ, 0L))
        progress.retainPairs(emptySet())
        assertNull(progress.nextEntryIfUnchanged(from, to, AutomixMode.AUTO_DJ, 0L))
        progress.record(from, to, AutomixMode.AUTO_DJ, 0L, 0L)
        progress.clear()
        assertNull(progress.nextEntryIfUnchanged(from, to, AutomixMode.AUTO_DJ, 0L))
    }
}

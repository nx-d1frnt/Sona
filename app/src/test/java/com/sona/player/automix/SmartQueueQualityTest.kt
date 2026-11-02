package com.sona.player.automix

import com.sona.player.data.model.Song
import org.junit.Assert.*
import org.junit.Test

class SmartQueueQualityTest {
    private fun analysis(id: Long, bpm: Float = 120f) = TrackAnalysis(
        id, bpm, 0.7f, 0L, 285_000L, 11_000L, 300_000L,
        beatSections = listOf(BeatSection(0, 300_000, bpm, 0.95f,
            (0L..300_000L step (60_000 / bpm).toLong()).toList())),
        vocalSections = listOf(VocalSection(0, 300_000, 0.05f)))

    // Android Uri is unavailable in local JVM tests; only ID and artist are used by the selector.
    private fun song(id: Long): Song {
        val cls = Class.forName("sun.misc.Unsafe")
        val unsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val song = cls.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Song::class.java) as Song
        for ((name, value) in listOf("id" to id, "artist" to "Artist $id")) {
            Song::class.java.getDeclaredField(name).apply { isAccessible = true; set(song, value) }
        }
        return song
    }

    @Test fun qualityUsesActualWindowAndVocalEnvelope() {
        val a = analysis(1).copy(vocalSections = listOf(
            VocalSection(0, 280_000, 0.95f), VocalSection(280_000, 300_000, 0.05f)))
        val b = analysis(2).copy(vocalSections = listOf(VocalSection(0, 300_000, 0.95f)))
        val plan = TransitionPlan(AutomixTransitionTool.BASS_SWAP_DROP, 285_000, 0, 10_000)
        val clean = TransitionQualityEvaluator.evaluate(a, b, plan)
        val clash = TransitionQualityEvaluator.evaluate(a, b, plan.copy(mixOutMs = 100_000))
        assertTrue(clean.scorePercent > clash.scorePercent)
        val truncated = TransitionQualityEvaluator.evaluate(a, b, plan.copy(mixOutMs = 299_500))
        assertTrue(truncated.scorePercent < 20)
    }

    @Test fun missingAnalysisReducesConfidenceAndNeverBecomesPerfect() {
        val a = analysis(1)
        val b = analysis(2)
        val measured = TransitionPlanner.plan(a, b, AutomixMode.CLASSIC_OUTRO).quality!!
        val unknown = TransitionPlanner.plan(a.copy(beatSections = emptyList(), vocalSections = emptyList()),
            b.copy(beatSections = emptyList(), vocalSections = emptyList()), AutomixMode.CLASSIC_OUTRO).quality!!
        assertTrue(unknown.confidencePercent < measured.confidencePercent)
        assertTrue(unknown.scorePercent < 100)
    }

    @Test fun rankingPrefersSaferPlanForOtherwiseIdenticalTracks() {
        val queue = (1L..3L).map { song(it) }
        val a = analysis(1).copy(vocalSections = listOf(VocalSection(0, 300_000, 0.95f)))
        val clash = analysis(2).copy(vocalSections = listOf(VocalSection(0, 300_000, 0.95f)))
        val clean = analysis(3)
        val analyses = mapOf(1L to a, 2L to clash, 3L to clean)
        val match = AutomixQueueSelector.findBestNextTrack(queue[0], a, queue, 0, { analyses[it] })!!
        assertEquals(3L, match.song.id)
    }

    @Test fun savedQualityMatchesPlannedQualityAndEntry() {
        val a = analysis(1)
        val b = analysis(2, 160f)
        val plan = TransitionPlanner.plan(a, b, AutomixMode.AUTO_DJ, entryPositionMs = 175_000)
        val saved = AutomixQueueSelector.buildSavedTransition(1, 2, a, b, AutomixMode.AUTO_DJ,
            entryPositionMs = 175_000)
        assertEquals(plan.quality, saved.quality)
    }

    @Test fun chainsBeyondFirstPairAndPreservesHistoryAndEveryQueueItem() {
        val queue = (1L..7L).map { song(it) }
        val bpms = listOf(120f, 120f, 120f, 170f, 120f, 170f, 120f)
        val analyses = queue.mapIndexed { i, song -> song.id to analysis(song.id, bpms[i]) }.toMap()
        val ordered = AutomixQueueSelector.buildSmartQueue(queue, 1, { analyses[it] })
        assertEquals(queue.take(2), ordered.take(2))
        assertEquals(queue.map { it.id }.sorted(), ordered.map { it.id }.sorted())
        // Every link is selected in context, including links after the first selected pair.
        for (index in 1 until ordered.lastIndex) {
            val match = AutomixQueueSelector.findBestNextTrack(ordered[index], analyses[ordered[index].id]!!,
                ordered, index, { analyses[it] })!!
            assertEquals(ordered[index + 1].id, match.song.id)
        }
    }

    @Test fun lockedPairsStayTogetherEvenWhenMovedAsABlock() {
        val queue = (1L..7L).map { song(it) }
        val analyses = queue.associate { it.id to analysis(it.id, if (it.id == 3L) 170f else 120f) }
        val ordered = AutomixQueueSelector.buildSmartQueue(queue, 0, { analyses[it] }, setOf(1L, 4L, 5L))
        assertEquals(2L, ordered[1].id)
        val blockStart = ordered.indexOfFirst { it.id == 4L }
        assertEquals(listOf(4L, 5L, 6L), ordered.drop(blockStart).take(3).map { it.id })
        assertEquals(queue.size, ordered.size)
    }

    @Test fun partialCacheKeepsEveryTrackAndContinuesAfterUnknownTrack() {
        val queue = (1L..6L).map { song(it) }
        val analyses = queue.filter { it.id != 2L }.associate { it.id to analysis(it.id) }
        val ordered = AutomixQueueSelector.buildSmartQueue(queue, 0, { analyses[it] })
        assertEquals(queue.map { it.id }.sorted(), ordered.map { it.id }.sorted())
        assertEquals(queue, AutomixQueueSelector.buildSmartQueue(queue, -1, { analyses[it] }))
        assertEquals(queue, AutomixQueueSelector.buildSmartQueue(queue, 0, { null }))
    }
}

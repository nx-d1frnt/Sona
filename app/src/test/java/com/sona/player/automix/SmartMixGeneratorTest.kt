package com.sona.player.automix

import com.sona.player.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMixGeneratorTest {

    private fun mockSong(id: Long, title: String, artist: String, durationMs: Long = 200_000L): Song {
        val cls = Class.forName("sun.misc.Unsafe")
        val unsafe = cls.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val song = cls.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Song::class.java) as Song
        val fields = mapOf(
            "id" to id,
            "title" to title,
            "artist" to artist,
            "album" to "Test Album",
            "durationMs" to durationMs
        )
        for ((name, value) in fields) {
            Song::class.java.getDeclaredField(name).apply {
                isAccessible = true
                set(song, value)
            }
        }
        return song
    }

    private fun mockAnalysis(
        songId: Long,
        bpm: Float,
        energy: Float,
        keyIndex: Int = 0, // C major
        vocalSections: List<VocalSection> = emptyList()
    ): TrackAnalysis {
        return TrackAnalysis(
            songId = songId,
            bpm = bpm,
            energy = energy,
            cueInMs = 0L,
            mixOutMs = 180_000L,
            crossfadeDurationMs = 10_000L,
            durationMs = 200_000L,
            keyIndex = keyIndex,
            keyConfidence = 0.9f,
            keyAnalyzed = true,
            featureAnalysisAttempted = true,
            loudnessAnalyzed = true,
            vocalSections = vocalSections
        )
    }

    @Test
    fun testVibeFilteringHighEnergy() {
        val s1 = mockSong(1L, "Energy Track", "Artist A")
        val s2 = mockSong(2L, "Chill Track", "Artist B")
        val a1 = mockAnalysis(1L, bpm = 128f, energy = 0.85f)
        val a2 = mockAnalysis(2L, bpm = 85f, energy = 0.3f)

        val analyses = mapOf(1L to a1, 2L to a2)
        val filtered = SmartMixGenerator.filterTracksForVibe(
            AutomixVibe.HIGH_ENERGY,
            listOf(s1, s2)
        ) { analyses[it] }

        assertEquals(1, filtered.size)
        assertEquals(1L, filtered.first().first.id)
    }

    @Test
    fun testVibeFilteringChill() {
        val s1 = mockSong(1L, "Upbeat Song", "Artist A")
        val s2 = mockSong(2L, "Mellow Beats", "Artist B")
        val a1 = mockAnalysis(1L, bpm = 130f, energy = 0.8f)
        val a2 = mockAnalysis(2L, bpm = 90f, energy = 0.35f)

        val analyses = mapOf(1L to a1, 2L to a2)
        val filtered = SmartMixGenerator.filterTracksForVibe(
            AutomixVibe.CHILL,
            listOf(s1, s2)
        ) { analyses[it] }

        assertEquals(1, filtered.size)
        assertEquals(2L, filtered.first().first.id)
    }

    @Test
    fun testVibeFilteringInstrumental() {
        val s1 = mockSong(1L, "Vocal Hit", "Singer A")
        val s2 = mockSong(2L, "Pure Instrumental", "Producer B")
        // Vocal presence covering 60 seconds
        val a1 = mockAnalysis(1L, bpm = 120f, energy = 0.6f, vocalSections = listOf(VocalSection(10_000L, 70_000L, 0.85f)))
        // Very little vocals
        val a2 = mockAnalysis(2L, bpm = 120f, energy = 0.6f, vocalSections = listOf(VocalSection(10_000L, 15_000L, 0.4f)))

        val analyses = mapOf(1L to a1, 2L to a2)
        val filtered = SmartMixGenerator.filterTracksForVibe(
            AutomixVibe.INSTRUMENTAL,
            listOf(s1, s2)
        ) { analyses[it] }

        assertTrue(filtered.any { it.first.id == 2L })
    }

    @Test
    fun testHarmonicChainingPrefersCompatibleKeysAndBpm() {
        val s1 = mockSong(1L, "Track 1", "Artist 1") // 124 BPM, C Major (0)
        val s2 = mockSong(2L, "Clashing Track", "Artist 2") // 160 BPM, F# Major (6)
        val s3 = mockSong(3L, "Compatible Track", "Artist 3") // 125 BPM, G Major (1) - 1 fifth away

        val a1 = mockAnalysis(1L, bpm = 124f, energy = 0.70f, keyIndex = 0)
        val a2 = mockAnalysis(2L, bpm = 160f, energy = 0.30f, keyIndex = 6)
        val a3 = mockAnalysis(3L, bpm = 125f, energy = 0.72f, keyIndex = 1)

        val pool = listOf(s1 to a1, s2 to a2, s3 to a3)
        val chain = SmartMixGenerator.chainTracks(s1 to a1, pool, maxTracks = 3)

        assertEquals(3, chain.size)
        assertEquals(1L, chain[0].id)
        // Compatible track (125 BPM, G major) should come before clashing track (160 BPM)
        assertEquals(3L, chain[1].id)
        assertEquals(2L, chain[2].id)
    }

    @Test
    fun testArtistVarietyPenalty() {
        val s1 = mockSong(1L, "Song 1", "Daft Punk")
        val s2 = mockSong(2L, "Song 2", "Daft Punk")
        val s3 = mockSong(3L, "Song 3", "Justice")

        // s2 and s3 have identical BPM and Key
        val a1 = mockAnalysis(1L, bpm = 126f, energy = 0.75f, keyIndex = 0)
        val a2 = mockAnalysis(2L, bpm = 126f, energy = 0.75f, keyIndex = 0)
        val a3 = mockAnalysis(3L, bpm = 126f, energy = 0.75f, keyIndex = 0)

        val pool = listOf(s1 to a1, s2 to a2, s3 to a3)
        val chain = SmartMixGenerator.chainTracks(s1 to a1, pool, maxTracks = 3)

        assertEquals(3, chain.size)
        assertEquals(1L, chain[0].id)
        // Justice (s3) should be chosen over second Daft Punk track (s2) due to artist variety penalty
        assertEquals(3L, chain[1].id)
        assertEquals(2L, chain[2].id)
    }

    @Test
    fun testGenerateSeedMixStartsAnchor() {
        val seed = mockSong(42L, "Seed Song", "Anchor Artist")
        val other1 = mockSong(1L, "Other 1", "Artist B")
        val other2 = mockSong(2L, "Other 2", "Artist C")

        val aSeed = mockAnalysis(42L, bpm = 120f, energy = 0.6f)
        val a1 = mockAnalysis(1L, bpm = 122f, energy = 0.62f)
        val a2 = mockAnalysis(2L, bpm = 124f, energy = 0.65f)

        val map = mapOf(42L to aSeed, 1L to a1, 2L to a2)
        val mix = SmartMixGenerator.generateSeedMix(seed, listOf(seed, other1, other2), { map[it] })

        assertEquals(3, mix.size)
        assertEquals(42L, mix.first().id)
    }
}

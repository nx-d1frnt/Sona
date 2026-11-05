package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class YamnetVocalDetectorTest {

    @Test
    fun vocalScoreDetectsVocalClasses() {
        val scores = FloatArray(521)
        // Set speech/singing indices (e.g. index 0, 1, 2)
        scores[0] = 0.85f
        scores[10] = 0.99f // non-vocal class
        val score = YamnetVocalDetector.vocalScore(scores)
        assertEquals(0.85f, score, 0.001f)
    }

    @Test
    fun vocalScoreHandlesEmptyOrZero() {
        val scores = FloatArray(521)
        val score = YamnetVocalDetector.vocalScore(scores)
        assertEquals(0f, score, 0.001f)
    }

    @Test
    fun vocalScoreCoercesNaNAndBounds() {
        val scores = FloatArray(521)
        scores[0] = Float.NaN
        scores[1] = 1.5f
        val score = YamnetVocalDetector.vocalScore(scores)
        assertEquals(1.0f, score, 0.001f)
    }

    @Test
    fun trackAnalysisComputesVocalPercentageCorrectly() {
        val analysisWithVocals = TrackAnalysis(
            songId = 1L,
            bpm = 120f,
            energy = 0.5f,
            cueInMs = 0L,
            mixOutMs = 180_000L,
            crossfadeDurationMs = 10_000L,
            durationMs = 200_000L,
            vocalSections = listOf(
                VocalSection(0L, 10_000L, 0.85f),    // 10s detected vocal
                VocalSection(10_000L, 20_000L, 0.05f) // 10s non-vocal
            )
        )
        // 10s out of 20s sampled = 50%
        assertEquals(50, analysisWithVocals.vocalPercentage)

        val mixedVocalAnalysis = TrackAnalysis(
            songId = 2L,
            bpm = 120f,
            energy = 0.5f,
            cueInMs = 0L,
            mixOutMs = 180_000L,
            crossfadeDurationMs = 10_000L,
            durationMs = 200_000L,
            vocalSections = listOf(
                VocalSection(0L, 10_000L, 0.65f) // Detected activity in mixed music
            )
        )
        // Confidence is not duration: all sampled audio contains detected activity.
        assertEquals(100, mixedVocalAnalysis.vocalPercentage)

        val instrumentalAnalysis = TrackAnalysis(
            songId = 3L,
            bpm = 120f,
            energy = 0.5f,
            cueInMs = 0L,
            mixOutMs = 180_000L,
            crossfadeDurationMs = 10_000L,
            durationMs = 200_000L,
            vocalSections = listOf(
                VocalSection(0L, 10_000L, 0.05f),
                VocalSection(10_000L, 20_000L, 0.08f)
            )
        )
        assertEquals(0, instrumentalAnalysis.vocalPercentage)

        val unanalyzedAnalysis = TrackAnalysis(
            songId = 4L,
            bpm = 120f,
            energy = 0.5f,
            cueInMs = 0L,
            mixOutMs = 180_000L,
            crossfadeDurationMs = 10_000L,
            durationMs = 200_000L,
            vocalSections = emptyList()
        )
        assertNull(unanalyzedAnalysis.vocalPercentage)
    }
    @Test
    fun vocalClassesMatchBundledModelLabels() {
        val model = listOf(File("src/main/assets/models/yamnet.tflite"),
            File("app/src/main/assets/models/yamnet.tflite")).first { it.exists() }
        val labels = ZipFile(model).use { zip ->
            zip.getInputStream(zip.getEntry("yamnet_label_list.txt")).bufferedReader().use { it.readLines() }
        }
        for (label in listOf("Speech", "Singing", "Choir", "Rapping", "Humming", "Vocal music", "A capella")) {
            val scores = FloatArray(labels.size)
            scores[labels.indexOf(label)] = 0.8f
            assertEquals(label, 0.8f, YamnetVocalDetector.vocalScore(scores), 0.001f)
        }
        for (label in listOf("Music", "Musical instrument")) {
            val scores = FloatArray(labels.size)
            scores[labels.indexOf(label)] = 0.99f
            assertEquals(label, 0f, YamnetVocalDetector.vocalScore(scores), 0.001f)
        }
    }

    @Test
    fun vocalCoverageUsesTimeAndLeavesUnsampledAudioUnknown() {
        assertEquals(0.75, VocalActivity.ratio(listOf(
            VocalSection(0, 30_000, 0.6f),
            VocalSection(150_000, 160_000, 0.05f)
        ), 200_000)!!, 0.0001)
        assertNull(VocalActivity.ratio(emptyList(), 200_000))
    }

    @Test
    fun vocalCoverageDoesNotDoubleCountOverlappingWindows() {
        assertEquals(0.5, VocalActivity.ratio(listOf(
            VocalSection(0, 20_000, 0.05f),
            VocalSection(0, 10_000, 0.8f),
            VocalSection(5_000, 10_000, 0.8f)
        ), 20_000)!!, 0.0001)
    }

    @Test
    fun vocalCoverageIgnoresInvalidSectionsAndClipsToTrack() {
        assertNull(VocalActivity.ratio(listOf(
            VocalSection(0, 1000, Float.NaN),
            VocalSection(0, 1000, Float.POSITIVE_INFINITY),
            VocalSection(1000, 0, 0.8f)
        ), 20_000))
        assertEquals(0.5, VocalActivity.ratio(listOf(
            VocalSection(-1000, 10_000, 0.8f),
            VocalSection(10_000, 30_000, 0.05f)
        ), 20_000)!!, 0.0001)
        assertEquals(0.0, VocalActivity.ratio(listOf(VocalSection(0, 1000, 0.49f)), 1000)!!, 0.0001)
    }

    @Test
    fun musicTagsCompareVoiceAgainstExplicitNoVoiceEvidence() {
        val scores = FloatArray(50)
        scores[13] = 0.1f // vocal
        scores[21] = 0.01f // no vocals
        assertTrue(MusicVocalDetector.activityScore(scores) > 0.5f)
        scores[21] = 0.4f
        assertTrue(MusicVocalDetector.activityScore(scores) < 0.5f)
        assertEquals(0f, MusicVocalDetector.activityScore(FloatArray(50)), 0.0001f)
        assertEquals(0f, MusicVocalDetector.activityScore(FloatArray(50) { Float.NaN }), 0.0001f)
    }

    @Test
    fun legacyVocalScoresRemainUnknownUntilRefreshed() {
        val legacy = TrackAnalysis(1, 120f, 0.5f, 0, 180_000, 10_000, 200_000,
            vocalSections = listOf(VocalSection(0, 10_000, 0.01f)), vocalAnalysisVersion = 0)
        assertFalse(legacy.hasCurrentVocalAnalysis)
        assertNull(legacy.vocalPercentage)
        assertNull(MusicalCueSelector.vocalRisk(legacy, 0, 1000))
        val refreshed = legacy.copy(vocalSections = listOf(VocalSection(0, 10_000, 0.9f)),
            vocalAnalysisVersion = VocalActivity.VERSION)
        assertTrue(refreshed.hasCurrentVocalAnalysis)
        assertEquals(100, refreshed.vocalPercentage)
    }

    @Test
    fun musicVoiceTagsMatchBundledLabels() {
        val labelsFile = listOf(File("src/main/assets/models/musicnn-labels.txt"),
            File("app/src/main/assets/models/musicnn-labels.txt")).first { it.exists() }
        val labels = labelsFile.readLines()
        assertEquals(50, labels.size)
        for (label in listOf("vocal", "vocals", "singing", "choir", "voice", "male vocal",
            "male voice", "female vocal", "female voice", "choral")) {
            val scores = FloatArray(50)
            scores[labels.indexOf(label)] = 0.2f
            assertTrue(label, MusicVocalDetector.activityScore(scores) > 0.5f)
        }
        for (label in listOf("no vocals", "no vocal", "no voice")) {
            val scores = FloatArray(50)
            scores[labels.indexOf("vocal")] = 0.1f
            scores[labels.indexOf(label)] = 0.4f
            assertTrue(label, MusicVocalDetector.activityScore(scores) < 0.5f)
        }
    }

}

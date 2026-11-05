package com.sona.player.ui.components

import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.BeatSection
import com.sona.player.automix.LoudnessSection
import com.sona.player.automix.TrackAnalysis
import com.sona.player.automix.VocalSection
import org.junit.Assert.*
import org.junit.Test

class TrackDiagnosticsReportTest {
    private val track = TrackAnalysis(1L, 120f, 0.6f, 500L, 25_000L, 8000L, 30_000L)

    @Test
    fun missingVocalMeasurementsRemainUnknownWhileMeasuredSilenceShowsZero() {
        assertTrue(TrackDiagnosticsReport.analysis(track).contains("vocalActivityPercent=unknown"))
        val silent = track.copy(vocalSections = listOf(VocalSection(0, 5000, 0.05f)))
        val report = TrackDiagnosticsReport.analysis(silent)
        assertTrue(report.contains("vocalActivityPercent=0"))
        assertTrue(report.contains("0..5000 ms score=0.05"))
    }

    @Test
    fun legacyScoresAreVisibleWithoutBeingReportedAsCurrentActivity() {
        val legacy = track.copy(vocalAnalysisVersion = 0,
            vocalSections = listOf(VocalSection(0, 5000, 0.9f)))
        val report = TrackDiagnosticsReport.analysis(legacy)
        assertTrue(report.contains("vocalsCurrent=false"))
        assertTrue(report.contains("vocalActivityPercent=unknown"))
        assertTrue(report.contains("0..5000 ms score=0.9"))
    }

    @Test
    fun liveMetricsNeverExtrapolateOutsideMeasuredWindows() {
        val measured = track.copy(
            vocalSections = listOf(VocalSection(0, 5000, 0.8f)),
            beatSections = listOf(BeatSection(0, 5000, 120f, 0.9f, listOf(0, 500, 1000))),
            loudnessSections = listOf(LoudnessSection(0, 5000, -14f, 0.9f))
        )
        fun report(position: Long) = TrackDiagnosticsReport.live(position, 30_000,
            false, 0, 1, "OFF", 0, measured, AutomixTransitionState(), null, null)
        val inside = report(1000)
        assertTrue(inside.contains("localVocalRisk1s=0.8"))
        assertTrue(inside.contains("localBpm=120.0"))
        assertTrue(inside.contains("localLufs=-14.0"))
        val outside = report(10_000)
        assertTrue(outside.contains("localVocalRisk1s=unknown"))
        assertTrue(outside.contains("localBpm=unknown"))
        assertTrue(outside.contains("localLufs=unknown"))
    }
}

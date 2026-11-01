package com.sona.player.automix

import com.sona.player.data.model.Song

/**
 * Acoustic & structural metadata extracted for a local track to power Sona's Automix.
 */
data class TrackAnalysis(
    val songId: Long,
    val bpm: Float,
    val energy: Float,               // Normalized perceptual intensity (0.0f .. 1.0f)
    val cueInMs: Long,               // Optimal start point skipping leading silence / weak intro
    val mixOutMs: Long,              // Optimal point in the outro to start crossfading to next song
    val crossfadeDurationMs: Long,   // Recommended overlap duration (e.g. 6000..10000 ms)
    val durationMs: Long
) {
    /**
     * Normalized fraction (0.0f .. 0.95f) along the track where the Automix Zone begins.
     */
    val automixZoneFraction: Float
        get() = if (durationMs > 0L) {
            (mixOutMs.toFloat() / durationMs.toFloat()).coerceIn(0.65f, 0.94f)
        } else {
            0.85f
        }
}

/**
 * Live state of an active or upcoming Automix transition for the UI.
 */
data class AutomixTransitionState(
    val isEnabled: Boolean = true,
    val isAnalyzing: Boolean = false,
    val currentAnalysis: TrackAnalysis? = null,
    val nextMatchedSong: Song? = null,
    val nextMatchedAnalysis: TrackAnalysis? = null,
    val matchScorePercent: Int = 0,
    val isTransitioning: Boolean = false,
    val transitionProgress: Float = 0f,     // 0.0f (start of mix) .. 1.0f (mix completed)
    val activeTempoRatio: Float = 1.0f      // Current pitch-preserving speed adjustment (e.g. 1.02f)
)

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
    val crossfadeDurationMs: Long,   // Recommended overlap duration (e.g. 8500..13000 ms)
    val durationMs: Long
) {
    /**
     * Normalized fraction (0.0f .. 0.95f) along the track where the Automix Zone begins.
     */
    val automixZoneFraction: Float
        get() = if (durationMs > 0L) {
            (mixOutMs.toFloat() / durationMs.toFloat()).coerceIn(0.62f, 0.94f)
        } else {
            0.82f
        }
}

/**
 * Preserved DJ transition metadata between two consecutive tracks in the queue (fromSongId -> toSongId).
 */
data class SavedTransition(
    val fromSongId: Long,
    val toSongId: Long,
    val fromBpm: Float,
    val toBpm: Float,
    val matchScorePercent: Int,
    val overlapDurationMs: Long,
    val cueInMs: Long,
    val transitionStyle: String,     // e.g., "Bass Swap + LPF", "Harmonic Lock", "Double-Time EQ", "Energy Lift"
    val isCompleted: Boolean = false
) {
    companion object {
        fun key(fromSongId: Long, toSongId: Long): String = "${fromSongId}_${toSongId}"
    }
}

/**
 * Live state of an active or upcoming Automix transition and preserved queue transition chain.
 */
data class AutomixTransitionState(
    val isEnabled: Boolean = true,
    val isAnalyzing: Boolean = false,
    val currentAnalysis: TrackAnalysis? = null,
    val nextMatchedSong: Song? = null,
    val nextMatchedAnalysis: TrackAnalysis? = null,
    val transitionTargetSong: Song? = null, // Track being mixed into during an active transition
    val transitionFromSongId: Long? = null, // Outgoing track ID during an active transition
    val matchScorePercent: Int = 0,
    val isTransitioning: Boolean = false,
    val transitionProgress: Float = 0f,     // 0.0f (start of mix) .. 1.0f (mix completed)
    val activeTempoRatio: Float = 1.0f,     // Current pitch-preserving speed adjustment
    val analyzedTracks: Map<Long, TrackAnalysis> = emptyMap(),
    val savedTransitions: Map<String, SavedTransition> = emptyMap()
)

package com.sona.player.automix

import com.sona.player.data.model.Song

/**
 * Automatically selected DJ transition toolset based on BPM proximity, energy delta, and groove structure.
 */
enum class AutomixTransitionTool(
    val displayName: String,
    val shortBadge: String,
    val toolsSummary: String
) {
    BASS_SWAP_DROP(
        displayName = "Club Bass Swap + LPF Sweep",
        shortBadge = "Bass Swap + LPF",
        toolsSummary = "BPM Sync • 2-Pole Bass Kill • Exp LPF"
    ),
    HIGH_PASS_RISER_SLAM(
        displayName = "High-Pass Riser + Echo Slam",
        shortBadge = "HPF Riser → Drop",
        toolsSummary = "BPM Sync • HPF Build-Up • Dub Echo • Slam Drop"
    ),
    FILTER_ECHO_WASH(
        displayName = "Resonant LPF + Stereo Echo Wash",
        shortBadge = "LPF + Echo Wash",
        toolsSummary = "BPM Sync • Ping-Pong Echo Tail • Deep LPF"
    ),
    HALF_DOUBLE_TIME_BRIDGE(
        displayName = "Half/Double-Time Harmonic Bridge",
        shortBadge = "2x/0.5x Tempo Bridge",
        toolsSummary = "2x/0.5x BPM Lock • Mid Isolator • Echo"
    ),
    SILKY_WARM_ISOLATOR(
        displayName = "Warm 3-Band Isolator Morph",
        shortBadge = "3-Band Isolator",
        toolsSummary = "BPM Sync • Smooth 3-Band EQ Morph"
    )
}

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
    val transitionTool: AutomixTransitionTool = AutomixTransitionTool.BASS_SWAP_DROP,
    val transitionStyle: String = transitionTool.displayName,
    val tempoSyncPercent: Int = 0,   // e.g. +3 or -2 (%) BPM sync applied during transition
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
    val activeTransitionTool: AutomixTransitionTool = AutomixTransitionTool.BASS_SWAP_DROP,
    val matchScorePercent: Int = 0,
    val isTransitioning: Boolean = false,
    val transitionProgress: Float = 0f,     // 0.0f (start of mix) .. 1.0f (mix completed)
    val activeTempoRatio: Float = 1.0f,     // Current pitch-preserving speed adjustment (e.g. 1.03f)
    val analyzedTracks: Map<Long, TrackAnalysis> = emptyMap(),
    val savedTransitions: Map<String, SavedTransition> = emptyMap()
)

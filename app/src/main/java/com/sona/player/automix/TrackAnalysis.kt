package com.sona.player.automix

import com.sona.player.data.model.Song

/**
 * Automix operating mode:
 * - [OFF]: Standard sequential playback without DJ transitions.
 * - [CLASSIC_OUTRO]: Mixes in the musical outro phrase (last 12..18s of the track).
 * - [QUICK_MID_MIX]: Club Megamix / DJ Set mode — finds the post-chorus breakdown near the middle
 *   of the track (44%..64%) and transitions early for a fast-paced continuous mix.
 */
enum class AutomixMode(
    val displayName: String,
    val shortLabel: String
) {
    OFF("Automix Off", "OFF"),
    CLASSIC_OUTRO("Outro Automix", "OUTRO"),
    QUICK_MID_MIX("Mid-Track DJ Mix", "MID DJ")
}

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
    val cueInMs: Long,               // Smart phrase/beat-aligned entry point (skips intros/skits up to 24s)
    val mixOutMs: Long,              // Optimal point in the outro (71%..89%) to start crossfading
    val crossfadeDurationMs: Long,   // Recommended overlap duration (e.g. 8500..13000 ms)
    val durationMs: Long,
    val midMixOutMs: Long = (durationMs * 0.56f).toLong(), // Optimal post-chorus breakdown past the midpoint (50%..67%)
    val deepCueInMs: Long = cueInMs  // Deeper hook/drop entry point (up to 32s) for Mid-Track DJ & high-energy mixes
) {
    /**
     * Returns the optimal entry point (Cue-In) in milliseconds for the incoming track,
     * adapting to the active [AutomixMode] and the outgoing track's energy.
     */
    fun effectiveCueInMs(mode: AutomixMode, outgoingEnergy: Float = 0.6f): Long {
        return when {
            mode == AutomixMode.QUICK_MID_MIX -> deepCueInMs
            outgoingEnergy >= 0.68f && deepCueInMs > cueInMs + 3500L -> deepCueInMs
            else -> cueInMs
        }
    }
    /**
     * Returns the active mix-out timestamp in milliseconds for the chosen [AutomixMode].
     */
    fun effectiveMixOutMs(mode: AutomixMode): Long {
        return when (mode) {
            AutomixMode.QUICK_MID_MIX -> midMixOutMs
            else -> mixOutMs
        }
    }

    /**
     * Normalized fraction along the track where the Automix Zone begins for the given [AutomixMode].
     */
    fun effectiveZoneFraction(mode: AutomixMode): Float {
        if (durationMs <= 0L) return if (mode == AutomixMode.QUICK_MID_MIX) 0.56f else 0.82f
        val targetMs = effectiveMixOutMs(mode)
        return if (mode == AutomixMode.QUICK_MID_MIX) {
            (targetMs.toFloat() / durationMs.toFloat()).coerceIn(0.50f, 0.68f)
        } else {
            (targetMs.toFloat() / durationMs.toFloat()).coerceIn(0.68f, 0.92f)
        }
    }

    val automixZoneFraction: Float
        get() = effectiveZoneFraction(AutomixMode.CLASSIC_OUTRO)
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
    val mode: AutomixMode = AutomixMode.CLASSIC_OUTRO,
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

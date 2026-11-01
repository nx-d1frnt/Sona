package com.sona.player.automix

import com.sona.player.data.model.Song
import kotlin.math.abs
import kotlin.math.min

/**
 * Automix operating mode:
 * - [AUTO_DJ]: Smart Auto DJ mode — automatically decides per track/pair whether to transition
 *   from the post-chorus middle section (Mid-Track DJ Mix) or use the musical outro (Outro Automix).
 * - [CLASSIC_OUTRO]: Mixes in the musical outro phrase (last 12..18s of the track).
 * - [QUICK_MID_MIX]: Club Megamix / DJ Set mode — finds the post-chorus breakdown past the middle
 *   of the track (50%..67%) and transitions early for a fast-paced continuous mix.
 * - [OFF]: Standard sequential playback without DJ transitions.
 */
enum class AutomixMode(
    val displayName: String,
    val shortLabel: String
) {
    AUTO_DJ("Auto Automix", "AUTO"),
    CLASSIC_OUTRO("Outro Automix", "OUTRO"),
    QUICK_MID_MIX("Mid-Track DJ Mix", "MID DJ"),
    OFF("Automix Off", "OFF")
}

/**
 * Queue Shuffle operating mode:
 * - [OFF]: Sequential playback in original playlist/manual order.
 * - [RANDOM]: Standard random shuffle across upcoming tracks.
 * - [SMART]: Harmonic DJ Shuffle — dynamically selects and moves the best BPM & Energy match
 *   one step ahead in the queue.
 */
enum class ShuffleMode(
    val displayName: String,
    val shortLabel: String
) {
    OFF("Shuffle Off", "OFF"),
    RANDOM("Random Shuffle", "SHUFFLE"),
    SMART("Smart DJ Shuffle", "SMART")
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
     * Resolves [AutomixMode.AUTO_DJ] into either [AutomixMode.QUICK_MID_MIX] or [AutomixMode.CLASSIC_OUTRO]
     * based on the outgoing track's duration, energy, mid-track breakdown structure, and harmonic compatibility
     * with the incoming track ([nextAnalysis]).
     */
    fun resolveEffectiveMode(mode: AutomixMode, nextAnalysis: TrackAnalysis? = null): AutomixMode {
        if (mode != AutomixMode.AUTO_DJ) return mode

        // 1. Short tracks (< 2:35) always use Outro Mix so they aren't cut off after ~1 minute
        if (durationMs < 155_000L) return AutomixMode.CLASSIC_OUTRO

        // 2. Ensure there is enough separation between midMixOutMs and mixOutMs
        if (mixOutMs - midMixOutMs < 18_000L) return AutomixMode.CLASSIC_OUTRO

        if (nextAnalysis != null) {
            val directDiff = abs(bpm - nextAnalysis.bpm)
            val halfDiff = abs(bpm - nextAnalysis.bpm * 0.5f)
            val doubleDiff = abs(bpm - nextAnalysis.bpm * 2.0f)
            val minBpmDiff = min(directDiff, min(halfDiff, doubleDiff))

            // High-energy club/pop pair with tight tempo lock -> Mid-Track DJ Mix!
            if (energy >= 0.62f && nextAnalysis.energy >= 0.58f && minBpmDiff <= 8.0f) {
                return AutomixMode.QUICK_MID_MIX
            }
            // Long energetic track (>= 3:15) transitioning into an upbeat track -> Mid-Track DJ Mix!
            if (durationMs >= 195_000L && energy >= 0.54f && nextAnalysis.energy >= 0.50f && minBpmDiff <= 12.0f) {
                return AutomixMode.QUICK_MID_MIX
            }
            // Very long track (>= 3:45) with moderate+ energy -> Mid-Track DJ Mix so it doesn't drag
            if (durationMs >= 225_000L && energy >= 0.48f) {
                return AutomixMode.QUICK_MID_MIX
            }
            return AutomixMode.CLASSIC_OUTRO
        }

        // Fallback before nextAnalysis is ready: use Mid-Track Mix for long & energetic tracks
        return if ((durationMs >= 185_000L && energy >= 0.64f) || (durationMs >= 215_000L && energy >= 0.52f)) {
            AutomixMode.QUICK_MID_MIX
        } else {
            AutomixMode.CLASSIC_OUTRO
        }
    }

    /**
     * Returns the optimal entry point (Cue-In) in milliseconds for the incoming track,
     * adapting to the active [AutomixMode] and the outgoing track's energy.
     */
    fun effectiveCueInMs(
        mode: AutomixMode,
        outgoingEnergy: Float = 0.6f,
        outgoingAnalysis: TrackAnalysis? = null
    ): Long {
        val resolvedMode = outgoingAnalysis?.resolveEffectiveMode(mode, this) ?: mode
        return when {
            resolvedMode == AutomixMode.QUICK_MID_MIX -> deepCueInMs
            outgoingEnergy >= 0.68f && deepCueInMs > cueInMs + 3500L -> deepCueInMs
            else -> cueInMs
        }
    }

    /**
     * Returns the active mix-out timestamp in milliseconds for the chosen [AutomixMode].
     */
    fun effectiveMixOutMs(mode: AutomixMode, nextAnalysis: TrackAnalysis? = null): Long {
        return when (resolveEffectiveMode(mode, nextAnalysis)) {
            AutomixMode.QUICK_MID_MIX -> midMixOutMs
            else -> mixOutMs
        }
    }

    /**
     * Normalized fraction along the track where the Automix Zone begins for the given [AutomixMode].
     */
    fun effectiveZoneFraction(mode: AutomixMode, nextAnalysis: TrackAnalysis? = null): Float {
        val resolvedMode = resolveEffectiveMode(mode, nextAnalysis)
        if (durationMs <= 0L) return if (resolvedMode == AutomixMode.QUICK_MID_MIX) 0.56f else 0.82f
        val targetMs = effectiveMixOutMs(resolvedMode, nextAnalysis)
        return if (resolvedMode == AutomixMode.QUICK_MID_MIX) {
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
    val mode: AutomixMode = AutomixMode.AUTO_DJ,
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

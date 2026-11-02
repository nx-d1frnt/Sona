package com.sona.player.automix

import com.sona.player.data.model.Song

/**
 * Automix operating mode:
 * - [AUTO_DJ]: Uses measured vocal pauses in middle sections, preferring structural boundaries
 *   and otherwise selecting a measured beat in a steady groove.
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
    VOCAL_HANDOFF(
        displayName = "Vocal Handoff", shortBadge = "Vocal Handoff",
        toolsSummary = "Vocal Pause • Instrumental Intro • Level Match"
    ),
    BEAT_CUT(
        displayName = "Short Beat Crossfade", shortBadge = "Beat Cut",
        toolsSummary = "Measured Beat • Short Crossfade • Level Match"
    ),
    ECHO_OUT(
        displayName = "Tempo Echo Out", shortBadge = "Echo Out",
        toolsSummary = "Beat Delay • Dry Cut • Echo Tail • Level Match"
    ),
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
    val crossfadeDurationMs:  Long,   // Recommended overlap duration (e.g. 8500..13000 ms)
    val durationMs: Long,
    val midMixOutMs: Long = (durationMs * 0.56f).toLong(), // Optimal post-chorus breakdown past the midpoint (50%..67%)
    val deepCueInMs: Long = cueInMs, // Deeper hook/drop entry point for explicit DJ mixes
    val midMixConfidence: Float = 0f, // Heuristic strength of a sustained drop; 0 for fallback data
    val beatSections: List<BeatSection> = emptyList(),
    val vocalSections: List<VocalSection> = emptyList(),
    val loudnessSections: List<LoudnessSection> = emptyList(),
    val featureAnalysisAttempted: Boolean = true,
    val loudnessAnalyzed: Boolean = false,
    val keyIndex: Int = -1,           // 0..11 major tonic (C..B), 12..23 minor tonic; -1 unknown
    val keyConfidence: Float = 0f,
    val keyAnalyzed: Boolean = false   // Key pass attempted (a null key may still be a valid result)
) {
    val musicalKey: MusicalKey? get() = MusicalKey.fromIndex(keyIndex, keyConfidence)

    /** All analysis passes for the current schema have been attempted. */
    val isComplete: Boolean get() = loudnessAnalyzed && featureAnalysisAttempted && keyAnalyzed

    /**
     * Resolves [AutomixMode.AUTO_DJ] into either [AutomixMode.QUICK_MID_MIX] or [AutomixMode.CLASSIC_OUTRO]
     * based on the outgoing track's duration, energy, mid-track breakdown structure, and harmonic compatibility
     * with the incoming track ([nextAnalysis]).
     */
    fun resolveEffectiveMode(mode: AutomixMode, nextAnalysis: TrackAnalysis? = null): AutomixMode {
        if (mode != AutomixMode.AUTO_DJ) return mode

        val next = nextAnalysis ?: return AutomixMode.CLASSIC_OUTRO
        if (durationMs < 180_000L || !bpm.isFinite() || !next.bpm.isFinite() ||
            bpm <= 0f || next.bpm <= 0f || !energy.isFinite() || !next.energy.isFinite()
        ) return AutomixMode.CLASSIC_OUTRO

        // A tempo/energy difference changes the transition technique, not whether AUTO
        // is allowed to use a measured middle section.
        return if (autoMidCue() != null) AutomixMode.QUICK_MID_MIX else AutomixMode.CLASSIC_OUTRO
    }

    // Immutable analysis: compute this once, not on every playback-progress tick.
    private val automaticMidCue: Long? by lazy { findAutoMidCue() }
    internal fun autoMidCue(): Long? = automaticMidCue

    private fun findAutoMidCue(): Long? {
        if (durationMs < 180_000L) return null
        val lower = maxOf(90_000L, (durationMs * 0.55).toLong())
        val upper = minOf((durationMs * 0.67).toLong(), mixOutMs - 25_000L,
            durationMs - 15_000L)
        if (upper < lower) return null
        val structural = midMixConfidence.isFinite() && midMixConfidence >= 0.65f && midMixOutMs in lower..upper
        val beats = beatSections.filter { it.confidence >= 0.65f && it.bpm.isFinite() && it.bpm > 0f && it.beatsMs.size >= 6 }
            .flatMap { section -> section.beatsMs.filter { it in section.startMs..section.endMs } }.toSet()
        val candidates = ((lower..upper step 500L).toList() + beats + if (structural) listOf(midMixOutMs) else emptyList())
            .filter { it in lower..upper }
        // AUTO requires a measured vocal pause plus structural or reliable rhythm
        // evidence. Vocal classification alone cannot justify skipping the rest of a song.
        val target = if (structural) midMixOutMs else (durationMs * 0.60).toLong()
        return candidates.filter { cue ->
            val structuralCue = structural && cue in midMixOutMs..midMixOutMs + 8000L
            (cue in beats || structuralCue) && MusicalCueSelector.isVocalPause(this, cue)
        }.minByOrNull { cue ->
            val voice = MusicalCueSelector.vocalRisk(this, cue, cue + 3000L) ?: 0.65f
            voice * 0.45f + kotlin.math.abs(cue - target) / 20_000f * 0.20f -
                if (cue in beats) 0.08f else 0f
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
        val base = when {
            mode == AutomixMode.AUTO_DJ -> cueInMs
            resolvedMode == AutomixMode.QUICK_MID_MIX -> deepCueInMs
            mode != AutomixMode.AUTO_DJ && outgoingEnergy >= 0.68f &&
                deepCueInMs > cueInMs + 3500L -> deepCueInMs
            else -> cueInMs
        }
        val overlap = if (resolvedMode == AutomixMode.QUICK_MID_MIX) 8500L
            else outgoingAnalysis?.crossfadeDurationMs ?: 10_500L
        if (mode == AutomixMode.AUTO_DJ && outgoingAnalysis != null) {
            val position = outgoingAnalysis.autoMidCue() ?: outgoingAnalysis.mixOutMs
            val voice = MusicalCueSelector.vocalRisk(outgoingAnalysis, position, position + 3000L)
            return MusicalCueSelector.djIncoming(this, base, overlap, outgoingVoice = voice ?: 0.65f)
        }
        return MusicalCueSelector.incoming(this, base, overlap)
    }

    /**
     * Returns the active mix-out timestamp in milliseconds for the chosen [AutomixMode].
     */
    fun effectiveMixOutMs(mode: AutomixMode, nextAnalysis: TrackAnalysis? = null): Long {
        val resolved = resolveEffectiveMode(mode, nextAnalysis)
        val early = resolved == AutomixMode.QUICK_MID_MIX
        val base = if (early && mode == AutomixMode.AUTO_DJ) autoMidCue() ?: midMixOutMs
            else if (early) midMixOutMs else mixOutMs
        if (early && mode == AutomixMode.AUTO_DJ) return base
        // Explicit Mid DJ retains its broad search range; AUTO keeps the conservative minimum.
        if (early && mode == AutomixMode.QUICK_MID_MIX) return base
        return MusicalCueSelector.outgoing(this, base, if (early) 8500L else crossfadeDurationMs, early)

    }

    /**
     * Normalized fraction along the track where the Automix Zone begins for the given [AutomixMode].
     */
    fun effectiveZoneFraction(mode: AutomixMode, nextAnalysis: TrackAnalysis? = null): Float {
        val resolvedMode = resolveEffectiveMode(mode, nextAnalysis)
        if (durationMs <= 0L) return if (resolvedMode == AutomixMode.QUICK_MID_MIX) 0.56f else 0.82f
        val targetMs = effectiveMixOutMs(mode, nextAnalysis)
        return if (resolvedMode == AutomixMode.QUICK_MID_MIX) {
            (targetMs.toFloat() / durationMs.toFloat()).coerceIn(0.50f, 0.70f)
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
    val isCompleted: Boolean = false,
    val quality: TransitionQuality? = null
) {
    companion object {
        fun key(fromSongId: Long, toSongId: Long): String = "${fromSongId}_${toSongId}"
    }
}

/**
 * Live state of an active or upcoming Automix transition and preserved queue transition chain.
 */
data class AutomixTransitionState(
    val entryPositionMs: Long = 0L,
    val playedMs: Long = 0L,
    val manualSeek: Boolean = false,
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

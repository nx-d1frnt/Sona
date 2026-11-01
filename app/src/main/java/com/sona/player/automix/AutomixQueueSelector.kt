package com.sona.player.automix

import com.sona.player.data.model.Song
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Selects the best next track "one step ahead" from the remaining unplayed queue
 * based on BPM harmonic proximity (including 0.5x/2x tempo) and energy continuity,
 * and automatically selects the optimal DJ transition toolset for each pair.
 */
object AutomixQueueSelector {

    data class MatchResult(
        val candidateIndexInQueue: Int,
        val song: Song,
        val analysis: TrackAnalysis,
        val matchScorePercent: Int,
        val outgoingTempoRatio: Float, // Clamped to [0.94f .. 1.06f]
        val incomingTempoRatio: Float, // Clamped to [0.94f .. 1.06f]
        val transitionTool: AutomixTransitionTool
    )

    fun findBestNextTrack(
        currentSong: Song,
        currentAnalysis: TrackAnalysis,
        queue: List<Song>,
        currentIndex: Int,
        analysisProvider: (Long) -> TrackAnalysis?
    ): MatchResult? {
        if (currentIndex < 0 || currentIndex + 1 >= queue.size) return null

        var bestIndex = currentIndex + 1
        var bestScore = -1f
        var bestAnalysis: TrackAnalysis? = null

        // Inspect upcoming unplayed songs in the queue (up to 25 candidates ahead)
        val searchLimit = min(queue.size - 1, currentIndex + 25)
        for (idx in (currentIndex + 1)..searchLimit) {
            val candidate = queue[idx]
            if (candidate.id == currentSong.id) continue

            val candidateAnalysis = analysisProvider(candidate.id) ?: continue
            val score = computeCompatibilityScore(currentAnalysis, candidateAnalysis)

            if (score > bestScore) {
                bestScore = score
                bestIndex = idx
                bestAnalysis = candidateAnalysis
            }
        }

        val chosenSong = queue[bestIndex]
        val chosenAnalysis = bestAnalysis ?: analysisProvider(chosenSong.id) ?: return null
        val finalScore = if (bestScore > 0f) bestScore else computeCompatibilityScore(currentAnalysis, chosenAnalysis)

        val (outRatio, inRatio) = computeTempoSyncRatios(
            bpmOut = currentAnalysis.bpm,
            bpmIn = chosenAnalysis.bpm
        )
        val tool = selectOptimalTransitionTool(currentAnalysis, chosenAnalysis)

        return MatchResult(
            candidateIndexInQueue = bestIndex,
            song = chosenSong,
            analysis = chosenAnalysis,
            matchScorePercent = (finalScore * 100f).roundToInt().coerceIn(60, 99),
            outgoingTempoRatio = outRatio,
            incomingTempoRatio = inRatio,
            transitionTool = tool
        )
    }

    fun computeCompatibilityScore(current: TrackAnalysis, candidate: TrackAnalysis): Float {
        val bpmA = current.bpm
        val bpmB = candidate.bpm

        // Compare direct BPM, double-time (2x), and half-time (0.5x)
        val diffDirect = abs(bpmA - bpmB) / bpmA
        val diffDouble = abs(bpmA - (bpmB * 2f)) / bpmA
        val diffHalf = abs(bpmA - (bpmB * 0.5f)) / bpmA

        val minRelBpmDiff = min(diffDirect, min(diffDouble, diffHalf))

        val tempoScore = when {
            minRelBpmDiff <= 0.04f -> 1.0f - (minRelBpmDiff / 0.04f) * 0.10f // 0.90 .. 1.00
            minRelBpmDiff <= 0.10f -> 0.90f - ((minRelBpmDiff - 0.04f) / 0.06f) * 0.25f // 0.65 .. 0.90
            minRelBpmDiff <= 0.22f -> 0.65f - ((minRelBpmDiff - 0.10f) / 0.12f) * 0.35f // 0.30 .. 0.65
            else -> 0.25f
        }

        val energyDiff = abs(current.energy - candidate.energy)
        val energyScore = (1.0f - energyDiff * 1.15f).coerceIn(0.20f, 1.0f)

        return (tempoScore * 0.68f + energyScore * 0.32f).coerceIn(0f, 1f)
    }

    /**
     * Automatically selects the best DJ Transition Toolset based on the acoustic relationship
     * between the outgoing and incoming tracks (BPM delta, 0.5x/2x harmonic ratio, energy lift/drop).
     */
    fun selectOptimalTransitionTool(
        fromAnalysis: TrackAnalysis,
        toAnalysis: TrackAnalysis
    ): AutomixTransitionTool {
        val bpmA = fromAnalysis.bpm
        val bpmB = toAnalysis.bpm
        val directDiff = abs(bpmA - bpmB) / bpmA
        val halfDoubleDiff = min(abs(bpmA - bpmB * 2f), abs(bpmA - bpmB * 0.5f)) / bpmA
        val energyDelta = toAnalysis.energy - fromAnalysis.energy

        return when {
            // 1. Half/Double-Time Harmonic Bridge when tracks are near 2x or 0.5x BPM
            halfDoubleDiff <= 0.06f && directDiff > 0.18f ->
                AutomixTransitionTool.HALF_DOUBLE_TIME_BRIDGE

            // 2. High-Pass Tension Riser + Echo Slam when jumping up in energy or into a fast banger
            energyDelta >= 0.12f || (toAnalysis.energy >= 0.76f && bpmB >= 126f) ->
                AutomixTransitionTool.HIGH_PASS_RISER_SLAM

            // 3. Resonant LPF + Stereo Echo Wash when dropping energy or bridging wider BPM gaps
            energyDelta <= -0.12f || directDiff > 0.065f ->
                AutomixTransitionTool.FILTER_ECHO_WASH

            // 4. Warm 3-Band Isolator Morph for chill / slower melodic tracks
            fromAnalysis.energy < 0.48f && toAnalysis.energy < 0.52f ->
                AutomixTransitionTool.SILKY_WARM_ISOLATOR

            // 5. Classic Club Bass Swap + Exponential LPF Sweep for matched-tempo grooves
            else -> AutomixTransitionTool.BASS_SWAP_DROP
        }
    }

    /**
     * Builds a SavedTransition descriptor between two consecutive tracks in the queue.
     */
    fun buildSavedTransition(
        fromSongId: Long,
        toSongId: Long,
        fromAnalysis: TrackAnalysis,
        toAnalysis: TrackAnalysis,
        mode: AutomixMode = AutomixMode.CLASSIC_OUTRO,
        isCompleted: Boolean = false
    ): SavedTransition {
        val rawScore = computeCompatibilityScore(fromAnalysis, toAnalysis)
        val scorePct = (rawScore * 100f).roundToInt().coerceIn(62, 99)
        val tool = selectOptimalTransitionTool(fromAnalysis, toAnalysis)
        val (_, inRatio) = computeTempoSyncRatios(fromAnalysis.bpm, toAnalysis.bpm)
        val syncPct = ((inRatio - 1.0f) * 100f).roundToInt()
        val targetCueInMs = toAnalysis.effectiveCueInMs(mode, fromAnalysis.energy)

        return SavedTransition(
            fromSongId = fromSongId,
            toSongId = toSongId,
            fromBpm = fromAnalysis.bpm,
            toBpm = toAnalysis.bpm,
            matchScorePercent = scorePct,
            overlapDurationMs = fromAnalysis.crossfadeDurationMs,
            cueInMs = targetCueInMs,
            transitionTool = tool,
            transitionStyle = "${tool.shortBadge} (${tool.toolsSummary})",
            tempoSyncPercent = syncPct,
            isCompleted = isCompleted
        )
    }

    /**
     * Calculates pitch-preserving playback speed ratios (up to ±6%) so two tracks
     * lock BPM during the transition, then glide smoothly back to 1.0x.
     */
    fun computeTempoSyncRatios(bpmOut: Float, bpmIn: Float): Pair<Float, Float> {
        if (bpmOut <= 0f || bpmIn <= 0f) return 1.0f to 1.0f

        val candidates = floatArrayOf(bpmIn, bpmIn * 2f, bpmIn * 0.5f)
        var closestEffectiveBpmIn = bpmIn
        var minDelta = Float.MAX_VALUE

        for (c in candidates) {
            val d = abs(bpmOut - c)
            if (d < minDelta) {
                minDelta = d
                closestEffectiveBpmIn = c
            }
        }

        val relDiff = minDelta / bpmOut
        // If BPMs are within 11%, lock them together (clamped to ±6% max shift per deck)
        if (relDiff > 0.11f) {
            // Even for wider BPM differences, apply a gentle ±3% nudge toward each other
            val nudgeOut = if (closestEffectiveBpmIn > bpmOut) 1.025f else 0.975f
            val nudgeIn = if (bpmOut > closestEffectiveBpmIn) 1.025f else 0.975f
            return nudgeOut to nudgeIn
        }

        // Meet at 60% toward the incoming track's groove, clamped strictly to [0.94f .. 1.06f] (±6%)
        val targetMidBpm = bpmOut * 0.45f + closestEffectiveBpmIn * 0.55f
        val outRatio = (targetMidBpm / bpmOut).coerceIn(0.94f, 1.06f)
        val inRatio = (targetMidBpm / closestEffectiveBpmIn).coerceIn(0.94f, 1.06f)

        return outRatio to inRatio
    }
}

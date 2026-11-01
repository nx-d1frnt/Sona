package com.sona.player.automix

import com.sona.player.data.model.Song
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Selects the best next track "one step ahead" from the remaining unplayed queue
 * based on BPM harmonic proximity (including 0.5x/2x tempo) and energy continuity.
 */
object AutomixQueueSelector {

    data class MatchResult(
        val candidateIndexInQueue: Int,
        val song: Song,
        val analysis: TrackAnalysis,
        val matchScorePercent: Int,
        val outgoingTempoRatio: Float, // Clamped to [0.96f .. 1.04f]
        val incomingTempoRatio: Float  // Clamped to [0.96f .. 1.04f]
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

        return MatchResult(
            candidateIndexInQueue = bestIndex,
            song = chosenSong,
            analysis = chosenAnalysis,
            matchScorePercent = (finalScore * 100f).roundToInt().coerceIn(60, 99),
            outgoingTempoRatio = outRatio,
            incomingTempoRatio = inRatio
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
     * Calculates subtle, pitch-preserving playback speed ratios (max ±4%) so two tracks
     * with close BPMs meet halfway in tempo during the crossfade, then return to 1.0x.
     */
    fun computeTempoSyncRatios(bpmOut: Float, bpmIn: Float): Pair<Float, Float> {
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
        // Only apply tempo sync if the tracks are within 6.5% BPM of each other
        if (relDiff > 0.065f || bpmOut <= 0f || closestEffectiveBpmIn <= 0f) {
            return 1.0f to 1.0f
        }

        // Meet halfway at the average BPM, clamped strictly to [0.96f .. 1.04f] (±4%)
        val targetMidBpm = (bpmOut + closestEffectiveBpmIn) * 0.5f
        val outRatio = (targetMidBpm / bpmOut).coerceIn(0.96f, 1.04f)
        val inRatio = (targetMidBpm / closestEffectiveBpmIn).coerceIn(0.96f, 1.04f)

        return outRatio to inRatio
    }
}

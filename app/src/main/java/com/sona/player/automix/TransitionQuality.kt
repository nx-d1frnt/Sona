package com.sona.player.automix

import kotlin.math.abs
import kotlin.math.roundToInt

/** Heuristic prediction, not a listening rating. Confidence describes local analysis coverage. */
data class TransitionQuality(val scorePercent: Int, val confidencePercent: Int)

internal object TransitionQualityEvaluator {
    fun evaluate(from: TrackAnalysis, to: TrackAnalysis, plan: TransitionPlan): TransitionQuality {
        if (plan.durationMs <= 0 || plan.mixOutMs < 0 || plan.cueInMs < 0 ||
            plan.mixOutMs >= from.durationMs || plan.cueInMs >= to.durationMs ||
            !plan.outgoingTempoRatio.isFinite() || plan.outgoingTempoRatio <= 0 ||
            !plan.incomingTempoRatio.isFinite() || plan.incomingTempoRatio <= 0) {
            return TransitionQuality(0, 0)
        }
        val outBpm = AutomixQueueSelector.localBpm(from, plan.mixOutMs) * plan.outgoingTempoRatio
        val inBpm = AutomixQueueSelector.localBpm(to, plan.cueInMs) * plan.incomingTempoRatio
        val validTempo = outBpm.isFinite() && inBpm.isFinite() && outBpm > 0 && inBpm > 0
        val gap = if (validTempo) minOf(abs(outBpm - inBpm), abs(outBpm - inBpm * 2),
            abs(outBpm - inBpm * 0.5f)) / outBpm else 1f
        // Short cuts and echo tails can bridge different grooves; sustained blends need tempo lock.
        val tempoTolerance = when (plan.tool) {
            AutomixTransitionTool.BEAT_CUT, AutomixTransitionTool.ECHO_OUT -> 0.40f
            else -> 0.12f
        }
        val tempo = if (validTempo) (1f - gap / tempoTolerance).coerceIn(0f, 1f) else 0.3f
        var conflict = 0f
        var knownVocals = 0
        val samples = 12
        for (i in 0 until samples) {
            val elapsed = plan.durationMs * i / samples
            val window = (plan.durationMs / samples).coerceAtLeast(1L)
            val outStart = plan.mixOutMs + (elapsed * plan.outgoingTempoRatio).toLong()
            val inStart = plan.cueInMs + (elapsed * plan.incomingTempoRatio).toLong()
            val outVoice = MusicalCueSelector.vocalRisk(from, outStart,
                outStart + (window * plan.outgoingTempoRatio).toLong().coerceAtLeast(1L))
            val inVoice = MusicalCueSelector.vocalRisk(to, inStart,
                inStart + (window * plan.incomingTempoRatio).toLong().coerceAtLeast(1L))
            if (outVoice != null && inVoice != null) knownVocals++
            var (outGain, inGain) = TransitionEnvelopes.gains(plan.tool, i.toFloat() / samples, plan.handoffFraction)
            // Echo stops feeding fresh outgoing vocals and decays the captured tail.
            if (plan.tool == AutomixTransitionTool.ECHO_OUT) outGain *= 1f - i.toFloat() / samples
            conflict += (outVoice ?: 0.5f) * (inVoice ?: 0.5f) * minOf(outGain, inGain)
        }
        val vocalSafety = (1f - 2f * conflict / samples).coerceIn(0f, 1f)
        fun beatConfidence(track: TrackAnalysis, position: Long) = track.beatSections
            .filter { position in it.startMs..it.endMs && it.bpm.isFinite() && it.bpm > 0 && it.beatsMs.size >= 6 }
            .maxOfOrNull { it.confidence.coerceIn(0f, 1f) } ?: 0f
        val rhythm = minOf(beatConfidence(from, plan.mixOutMs), beatConfidence(to, plan.cueInMs))
        val keyA = from.musicalKey
        val keyB = to.musicalKey
        val keyConfidence = if (keyA != null && keyB != null) minOf(keyA.confidence, keyB.confidence) else 0f
        val harmony = if (keyA != null && keyB != null) MusicalKey.compatibility(keyA, keyB) else 0.5f
        val energy = (1f - abs(from.energy - to.energy)).coerceIn(0f, 1f)
        val availableOut = (from.durationMs - plan.mixOutMs) / plan.outgoingTempoRatio
        val availableIn = (to.durationMs - plan.cueInMs) / plan.incomingTempoRatio
        val coverage = (minOf(availableOut, availableIn) / plan.durationMs).coerceIn(0f, 1f)
        val score = (tempo * 0.35f + vocalSafety * 0.35f + energy * 0.15f + harmony * 0.15f) * coverage
        val confidence = rhythm * 0.45f + knownVocals.toFloat() / samples * 0.40f + keyConfidence * 0.15f
        return TransitionQuality((score * 100).roundToInt().coerceIn(0, 100),
            (confidence * 100).roundToInt().coerceIn(0, 100))
    }
}

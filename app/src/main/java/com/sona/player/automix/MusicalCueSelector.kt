package com.sona.player.automix

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Conservative local cue selection: unknown vocals are never treated as silence. */
internal object MusicalCueSelector {
    fun vocalRisk(track: TrackAnalysis, startMs: Long, endMs: Long): Float? {
        if (endMs <= startMs) return null
        val relevant = track.vocalSections.filter { it.startMs < endMs && it.endMs > startMs && it.score.isFinite() }
        val boundaries = (listOf(startMs, endMs) + relevant.flatMap {
            listOf(it.startMs.coerceAtLeast(startMs), it.endMs.coerceAtMost(endMs))
        }).distinct().sorted()
        var covered = 0L
        var weighted = 0.0
        for ((start, end) in boundaries.zipWithNext()) {
            val score = relevant.filter { it.startMs <= start && it.endMs >= end }.maxOfOrNull { it.score }
            if (score != null) {
                covered += end - start
                weighted += (end - start) * score.toDouble()
            }
        }
        return if (covered >= (endMs - startMs) * 0.85) (weighted / covered).toFloat() else null
    }

    fun beatSection(track: TrackAnalysis, positionMs: Long): BeatSection? = track.beatSections
        .filter { it.confidence >= 0.65f && positionMs in it.startMs..it.endMs && it.bpm.isFinite() && it.bpm > 0 }
        .maxByOrNull { it.confidence }

    fun outgoing(track: TrackAnalysis, baseMs: Long, overlapMs: Long, early: Boolean): Long {
        val minimum = if (early) max(150_000L, (track.durationMs * 0.55).toLong())
            else (track.durationMs * 0.70).toLong()
        val lower = max(minimum, baseMs - 1500L)
        val upper = min(baseMs + 4000L, track.durationMs - overlapMs - 500L)
        return choose(track, baseMs, lower, upper, overlapMs, outgoing = true)
    }

    fun incoming(track: TrackAnalysis, baseMs: Long, overlapMs: Long): Long {
        return choose(track, baseMs, (baseMs - 1500L).coerceAtLeast(0L),
            minOf(baseMs + 5000L, 24_000L, track.durationMs - overlapMs - 1000L), overlapMs, outgoing = false)
    }

    private fun choose(track: TrackAnalysis, baseMs: Long, lower: Long, upper: Long,
                       overlapMs: Long, outgoing: Boolean): Long {
        if (upper < lower || track.vocalSections.isEmpty() && track.beatSections.isEmpty()) return baseMs
        val beats = track.beatSections.filter { it.confidence >= 0.65f }
            .flatMap { it.beatsMs }.filter { it in lower..upper }.toSet()
        val candidates = ((lower..upper step 500L).toList() + beats + baseMs).filter { it in lower..upper }
        return candidates.minByOrNull { cue ->
            val end = cue + (overlapMs * if (outgoing) 1.0 else 0.75).toLong()
            val risk = vocalRisk(track, cue, end) ?: 0.5f
            // Preserve context: moving several seconds requires a meaningful reduction in vocal activity.
            risk + abs(cue - baseMs) / 5000f * 0.15f - if (cue in beats) 0.10f else 0f
        } ?: baseMs
    }

    fun tempoRatios(from: TrackAnalysis, to: TrackAnalysis, mode: AutomixMode): Pair<Float, Float> {
        val outPosition = from.effectiveMixOutMs(mode, to)
        val inPosition = to.effectiveCueInMs(mode, from.energy, from)
        val outBeat = beatSection(from, outPosition) ?: return 1f to 1f
        val inBeat = beatSection(to, inPosition) ?: return 1f to 1f
        val ratios = AutomixQueueSelector.computeTempoSyncRatios(outBeat.bpm, inBeat.bpm)
        val a = outBeat.bpm * ratios.first
        val b = inBeat.bpm * ratios.second
        val relativeDiff = minOf(abs(a - b), abs(a - b * 2), abs(a - b * 0.5f)) / a
        return if (relativeDiff <= 0.01f) ratios else 1f to 1f
    }

    fun isMeasuredBeat(track: TrackAnalysis, positionMs: Long): Boolean =
        beatSection(track, positionMs)?.beatsMs?.any { abs(it - positionMs) <= 60L } == true

    fun nextBeatForStart(track: TrackAnalysis, positionMs: Long, notBeforeMs: Long): Long? {
        val section = beatSection(track, positionMs) ?: return null
        return section.beatsMs.firstOrNull { it >= notBeforeMs && it <= positionMs + 1500L }
    }
}

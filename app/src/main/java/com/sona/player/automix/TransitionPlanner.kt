package com.sona.player.automix

import kotlin.math.*

internal data class TransitionPlan(
    val tool: AutomixTransitionTool,
    val mixOutMs: Long,
    val cueInMs: Long,
    val durationMs: Long,
    val outgoingTempoRatio: Float = 1f,
    val incomingTempoRatio: Float = 1f,
    val echoDelayMs: Float = 320f,
    val handoffFraction: Float = 0.5f,
    val outgoingGain: Float = 1f,
    val incomingGain: Float = 1f
)

/** One decision for queue previews and playback, including optional manual-skip position. */
internal object TransitionPlanner {
    private data class Key(val fromId: Long, val toId: Long, val mode: AutomixMode)
    private data class Cached(val from: TrackAnalysis, val to: TrackAnalysis, val plan: TransitionPlan)
    private val previews = java.util.LinkedHashMap<Key, Cached>(32, 0.75f, true)

    fun plan(from: TrackAnalysis, to: TrackAnalysis, mode: AutomixMode,
             customDurationMs: Long? = null, actualOutPositionMs: Long? = null): TransitionPlan {
        if (customDurationMs != null || actualOutPositionMs != null) {
            return computePlan(from, to, mode, customDurationMs, actualOutPositionMs)
        }
        val key = Key(from.songId, to.songId, mode)
        synchronized(previews) {
            previews[key]?.takeIf { it.from === from && it.to === to }?.let { return it.plan }
        }
        val result = computePlan(from, to, mode)
        synchronized(previews) {
            previews[key] = Cached(from, to, result)
            if (previews.size > 128) previews.remove(previews.keys.first())
        }
        return result
    }

    private fun computePlan(from: TrackAnalysis, to: TrackAnalysis, mode: AutomixMode,
             customDurationMs: Long? = null, actualOutPositionMs: Long? = null): TransitionPlan {
        val resolved = from.resolveEffectiveMode(mode, to)
        val baseDuration = customDurationMs?.coerceIn(800L, 13_000L)
            ?: if (resolved == AutomixMode.QUICK_MID_MIX) 8500L else from.crossfadeDurationMs.coerceIn(8500L, 13_000L)
        val defaultOut = actualOutPositionMs ?: from.effectiveMixOutMs(mode, to)
        val baseOut = if (actualOutPositionMs != null) defaultOut else pairMixOut(from, to, mode, defaultOut, baseDuration)
        val baseIn = to.effectiveCueInMs(mode, from.energy, from)
        val rawOut = actualOutPositionMs ?: if (resolved == AutomixMode.QUICK_MID_MIX) baseOut else from.mixOutMs
        val outBeat = rhythmSection(from, baseOut)
        val inBeat = rhythmSection(to, baseIn)
        val bpm = outBeat?.bpm
        val period = if (bpm != null) 60_000f / bpm else null
        val gap = if (outBeat != null && inBeat != null) {
            minOf(abs(outBeat.bpm - inBeat.bpm), abs(outBeat.bpm - inBeat.bpm * 2),
                abs(outBeat.bpm - inBeat.bpm * 0.5f)) / outBeat.bpm
        } else null
        val ratios = MusicalCueSelector.tempoRatiosAt(from, to, baseOut, baseIn)
        var tool = AutomixQueueSelector.selectOptimalTransitionTool(from, to)
        var out = baseOut
        var cue = baseIn
        var duration = baseDuration
        var handoff = 0.5f

        // Whole-track handoff: preserve the outgoing vocal while a measured instrumental intro enters.
        val outgoingVoice = MusicalCueSelector.vocalRisk(from, rawOut, rawOut + 1000L)
        if (outgoingVoice != null && outgoingVoice >= 0.35f && (gap == null || gap <= 0.12f)) {
            val voiceEnd = (rawOut + 1000L..rawOut + 6000L step 250L).firstOrNull {
                (MusicalCueSelector.vocalRisk(from, it, it + 1000L) ?: 1f) < 0.20f
            }
            if (voiceEnd != null) {
                val introLeadMs = ((voiceEnd - rawOut + 750L) * ratios.second / ratios.first).toLong()
                val instrumentalCue = (0L..minOf(baseIn + 5000L, 24_000L, to.durationMs - baseDuration) step 250L)
                    .firstOrNull { (MusicalCueSelector.vocalRisk(to, it, it + introLeadMs) ?: 1f) < 0.20f }
                if (instrumentalCue != null && rawOut >= 0 && rawOut + baseDuration < from.durationMs) {
                    tool = AutomixTransitionTool.VOCAL_HANDOFF
                    out = rawOut
                    cue = instrumentalCue
                    handoff = ((voiceEnd - rawOut).toFloat() / ratios.first / baseDuration).coerceIn(0.15f, 0.70f)
                }
            }
        }
        if (tool != AutomixTransitionTool.VOCAL_HANDOFF) {
            val outVoice = MusicalCueSelector.vocalRisk(from, baseOut, baseOut + 2000L)
            val inVoice = MusicalCueSelector.vocalRisk(to, baseIn, baseIn + 2000L)
            if (outBeat != null && inBeat != null &&
                (gap!! > 0.06f || abs(from.energy - to.energy) >= 0.18f) && outVoice != null && outVoice < 0.35f &&
                inVoice != null && inVoice < 0.35f) {
                tool = AutomixTransitionTool.BEAT_CUT
                duration = ((period ?: 500f) * 2).toLong().coerceIn(1000L, 2000L)
                cue = nearestBeat(to, baseIn) ?: baseIn
                out = if (actualOutPositionMs == null) nearestBeat(from, baseOut) ?: baseOut else baseOut
            } else if (period != null && ((gap != null && gap > 0.06f) ||
                    (outVoice != null && outVoice > 0.35f && inVoice != null && inVoice > 0.35f))) {
                tool = AutomixTransitionTool.ECHO_OUT
                duration = (period * 6).toLong().coerceIn(2500L, 4500L)
            }
        }
        // Echo Out bridges grooves at natural speed. No Sonic parameter drain may synthesize
        // an EOF tail before the real media item ends.
        val finalRatios = if (tool == AutomixTransitionTool.ECHO_OUT) 1f to 1f
            else MusicalCueSelector.tempoRatiosAt(from, to, out, cue)
        return TransitionPlan(tool, out, cue, duration,
            finalRatios.first, finalRatios.second,
            echoDelayMs = if (period != null) period * if (from.energy >= 0.8f) 0.5f else 1f else 320f,
            handoffFraction = handoff,
            outgoingGain = LoudnessNormalizer.gain(from, out), incomingGain = LoudnessNormalizer.gain(to, cue))
    }

    /** Re-evaluate the outgoing window for this particular incoming vocal, not just the song alone. */
    private fun pairMixOut(from: TrackAnalysis, to: TrackAnalysis, mode: AutomixMode,
                           base: Long, overlap: Long): Long {
        val cue = to.effectiveCueInMs(mode, from.energy, from)
        val incomingVoice = MusicalCueSelector.vocalRisk(to, cue, cue + 3000L) ?: return base
        if (incomingVoice < 0.35f) return base // Leave room for vocal handoff into an instrumental intro.
        val early = from.resolveEffectiveMode(mode, to) == AutomixMode.QUICK_MID_MIX
        val lower = maxOf(base - 6000L, if (early) maxOf(90_000L, (from.durationMs * 0.52).toLong())
            else (from.durationMs * 0.70).toLong())
        val upper = minOf(base + 6000L, from.durationMs - overlap - 500L,
            if (early) (from.durationMs * 0.70).toLong() else from.durationMs)
        if (upper < lower) return base
        fun score(position: Long): Float {
            val voice = MusicalCueSelector.vocalRisk(from, position, position + 3000L) ?: return Float.POSITIVE_INFINITY
            return voice * incomingVoice + abs(position - base) / 6000f * 0.12f
        }
        val candidates = ((lower..upper step 500L).toList() + base).filter { cue ->
            !early || mode != AutomixMode.AUTO_DJ ||
                ((MusicalCueSelector.vocalRisk(from, cue, cue + 1000L) ?: 1f) < 0.25f &&
                    (MusicalCueSelector.vocalRisk(from, cue, cue + 3000L) ?: 1f) < 0.35f)
        }
        val best = candidates.minByOrNull { score(it) } ?: base
        return if (score(best) < score(base)) best else base
    }

    // Style selection tolerates moderate rhythm evidence; tempo stretching still uses
    // MusicalCueSelector's stricter 0.65 threshold and residual-tempo check.
    private fun rhythmSection(track: TrackAnalysis, position: Long): BeatSection? = track.beatSections
        .filter { it.confidence >= 0.45f && it.bpm.isFinite() && it.bpm > 0 &&
            position in it.startMs..it.endMs && it.beatsMs.size >= 6 }
        .maxByOrNull { it.confidence }

    private fun nearestBeat(track: TrackAnalysis, position: Long): Long? =
        rhythmSection(track, position)?.beatsMs
            ?.filter { it >= position && it <= position + 750L }?.minOrNull()
}

/** Pure gain envelopes, shared with the audio-engine regression tests. */
internal object TransitionEnvelopes {
    fun gains(tool: AutomixTransitionTool, progress: Float, handoff: Float = 0.5f): Pair<Float, Float> {
        val t = progress.coerceIn(0f, 1f)
        val angle = PI * 0.5
        return when (tool) {
            AutomixTransitionTool.VOCAL_HANDOFF -> {
                val boundary = handoff.coerceIn(0.15f, 0.7f)
                val exit = ((t - boundary) / 0.22f).coerceIn(0f, 1f)
                val incoming = if (t < boundary) 0.12f * (t / boundary)
                    else 0.12f + 0.88f * sin(((t - boundary) / (1 - boundary)) * angle).toFloat()
                cos(exit * angle).toFloat().coerceAtLeast(0f) to incoming
            }
            AutomixTransitionTool.ECHO_OUT -> 1f to sin(t * angle).toFloat() // dry/wet exit is inside DSP
            AutomixTransitionTool.BEAT_CUT, AutomixTransitionTool.SILKY_WARM_ISOLATOR ->
                cos(t * angle).toFloat().coerceAtLeast(0f) to sin(t * angle).toFloat()
            AutomixTransitionTool.HIGH_PASS_RISER_SLAM -> {
                val out = if (t < 0.48f) 1f - t / 0.48f * 0.15f else {
                    val tail = ((t - 0.48f) / 0.52f).coerceIn(0f, 1f)
                    0.85f * (1 - tail).pow(2)
                }
                val incoming = when {
                    t < 0.45f -> t / 0.45f * 0.72f
                    t < 0.52f -> 0.72f + (t - 0.45f) / 0.07f * 0.28f
                    else -> 1f
                }
                out to incoming
            }
            else -> {
                val out = if (t < 0.45f) 1f - t / 0.45f * 0.12f else
                    0.88f * cos(((t - 0.45f) / 0.55f).coerceIn(0f, 1f) * angle).toFloat()
                val incoming = when {
                    t < 0.44f -> sin(t / 0.44f * angle).toFloat() * 0.84f
                    t < 0.54f -> 0.84f + (t - 0.44f) / 0.10f * 0.16f
                    else -> 1f
                }
                out.coerceAtLeast(0f) to incoming
            }
        }
    }
}

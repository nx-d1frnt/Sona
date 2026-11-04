package com.sona.player.automix

import com.sona.player.data.model.Song
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a greedy queue chain using track compatibility and predicted quality of each actual plan.
 * Each link inspects up to 25 upcoming candidates; analysis and planning use cached audio features.
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
        analysisProvider: (Long) -> TrackAnalysis?,
        mode: AutomixMode = AutomixMode.AUTO_DJ,
        entryPositionMs: Long = 0L,
        canSelectCandidate: (Int) -> Boolean = { true }
    ): MatchResult? {
        if (currentIndex < 0 || currentIndex + 1 >= queue.size) return null

        val recentArtists = HashSet<String>(3)
        for (i in (currentIndex - 2).coerceAtLeast(0)..currentIndex) {
            normalizeArtist(queue.getOrNull(i)?.artist)?.let { recentArtists.add(it) }
        }
        val currentArtist = normalizeArtist(currentSong.artist)
        val currentBpm = localBpm(currentAnalysis, currentAnalysis.mixOutMs)

        data class ScoredCandidate(
            val index: Int,
            val song: Song,
            val analysis: TrackAnalysis,
            val rawScore: Float,
            val selectionScore: Float
        )

        val candidates = ArrayList<ScoredCandidate>()
        // Inspect upcoming unplayed songs in the queue (up to 25 candidates ahead)
        val searchLimit = min(queue.size - 1, currentIndex + 25)
        for (idx in (currentIndex + 1)..searchLimit) {
            val candidate = queue[idx]
            if (candidate.id == currentSong.id || !canSelectCandidate(idx)) continue

            val candidateAnalysis = analysisProvider(candidate.id) ?: continue
            val rawScore = computeCompatibilityScore(currentAnalysis, candidateAnalysis, currentBpm)
            val candidateArtist = normalizeArtist(candidate.artist)
            val artistPenalty = when {
                candidateArtist != null && candidateArtist == currentArtist -> 0.14f
                candidateArtist != null && candidateArtist in recentArtists -> 0.07f
                else -> 0f
            }
            val selectionScore = rawScore * 0.4f - artistPenalty
            candidates += ScoredCandidate(idx, candidate, candidateAnalysis, rawScore, selectionScore)
        }

        val plannedCandidates = candidates
            .sortedWith(compareByDescending<ScoredCandidate> { it.selectionScore }.thenBy { it.song.id })
            .take(6)
            .map { candidate ->
                val plan = TransitionPlanner.plan(currentAnalysis, candidate.analysis, mode, entryPositionMs = entryPositionMs)
                val quality = plan.quality!!
                val confidence = quality.confidencePercent / 100f
                val planScore = quality.scorePercent / 100f * (0.65f + confidence * 0.35f)
                candidate.copy(selectionScore = candidate.selectionScore + planScore * 0.6f)
            }
        val chosen = if (plannedCandidates.isNotEmpty()) {
            val maxSel = plannedCandidates.maxOf { it.selectionScore }
            // Keep top candidates within 4.5% of the best score (up to 4 tracks) and pick
            // deterministically using the current song & queue context to prevent greedy loops
            // over the same "universal" tracks while keeping queue previews stable.
            val topPool = plannedCandidates
                .filter { it.selectionScore >= maxSel - 0.045f }
                .sortedWith(compareByDescending<ScoredCandidate> { it.selectionScore }.thenBy { it.song.id })
                .take(4)
            val seed = (currentSong.id * 31L + currentIndex * 17L).toInt()
            val pickIdx = Math.floorMod(seed, topPool.size)
            topPool[pickIdx]
        } else null

        val bestIndex = chosen?.index ?: (currentIndex + 1)
        val chosenSong = chosen?.song ?: queue[bestIndex]
        val chosenAnalysis = chosen?.analysis ?: analysisProvider(chosenSong.id) ?: return null
        val finalScore = chosen?.rawScore ?: computeCompatibilityScore(currentAnalysis, chosenAnalysis)

        val plan = TransitionPlanner.plan(currentAnalysis, chosenAnalysis, mode, entryPositionMs = entryPositionMs)

        return MatchResult(
            candidateIndexInQueue = bestIndex,
            song = chosenSong,
            analysis = chosenAnalysis,
            matchScorePercent = (finalScore * 100f).roundToInt().coerceIn(0, 100),
            outgoingTempoRatio = plan.outgoingTempoRatio,
            incomingTempoRatio = plan.incomingTempoRatio,
            transitionTool = plan.tool
        )
    }

    /** Greedy chain over cached analyses. Locked adjacencies move as indivisible blocks. */
    fun buildSmartQueue(
        queue: List<Song>, currentIndex: Int, analysisProvider: (Long) -> TrackAnalysis?,
        lockedNextForSongIds: Set<Long> = emptySet(), mode: AutomixMode = AutomixMode.AUTO_DJ,
        entryPositionMs: Long = 0L,
        maxLinks: Int = Int.MAX_VALUE,
        onMove: (fromIndex: Int, toIndex: Int, count: Int) -> Unit = { _, _, _ -> }
    ): List<Song> {
        if (currentIndex !in queue.indices || maxLinks <= 0) return queue
        val result = queue.toMutableList()
        var entry = entryPositionMs
        val endExclusive = (currentIndex.toLong() + maxLinks).coerceAtMost(result.lastIndex.toLong()).toInt()
        for (index in currentIndex until endExclusive) {
            val current = result[index]
            val analysis = analysisProvider(current.id)
            if (analysis != null && current.id !in lockedNextForSongIds) {
                val match = findBestNextTrack(current, analysis, result, index, analysisProvider,
                    mode, entry, canSelectCandidate = { candidate ->
                        result[candidate - 1].id !in lockedNextForSongIds
                    })
                if (match != null && match.candidateIndexInQueue != index + 1) {
                    val start = match.candidateIndexInQueue
                    var end = start
                    while (end < result.lastIndex && result[end].id in lockedNextForSongIds) end++
                    val block = result.subList(start, end + 1).toList()
                    result.subList(start, end + 1).clear()
                    result.addAll(index + 1, block)
                    onMove(start, index + 1, block.size)
                }
            }
            val next = analysisProvider(result[index + 1].id)
            entry = if (analysis != null && next != null)
                TransitionPlanner.plan(analysis, next, mode, entryPositionMs = entry).cueInMs else 0L
        }
        return result
    }

    private fun normalizeArtist(artist: String?): String? {
        val cleaned = artist?.trim()?.lowercase() ?: return null
        if (cleaned.isEmpty() || cleaned == "<unknown>" || cleaned == "unknown artist" || cleaned == "various artists") {
            return null
        }
        return cleaned
    }

    /**
     * Tempo measured where the mix actually happens (outgoing exit / incoming entry), falling back
     * to the nearest confident beat section and finally the whole-track estimate.
     */
    internal fun localBpm(track: TrackAnalysis, positionMs: Long): Float {
        var bestInsideBpm: Float? = null
        var bestInsideConf = -1f
        var bestNearBpm: Float? = null
        var minDistance = Long.MAX_VALUE

        for (section in track.beatSections) {
            val bpm = section.bpm
            if (!bpm.isFinite() || bpm <= 0f) continue
            val conf = section.confidence
            if (conf >= 0.45f && positionMs in section.startMs..section.endMs) {
                if (conf > bestInsideConf) {
                    bestInsideConf = conf
                    bestInsideBpm = bpm
                }
            } else if (conf >= 0.65f) {
                val dist = minOf(abs(positionMs - section.startMs), abs(positionMs - section.endMs))
                if (dist <= 15_000L && dist < minDistance) {
                    minDistance = dist
                    bestNearBpm = bpm
                }
            }
        }
        return bestInsideBpm ?: bestNearBpm ?: track.bpm
    }

    private const val TEMPO_WEIGHT = 0.58f
    private const val ENERGY_WEIGHT = 0.24f
    private const val KEY_WEIGHT = 0.18f

    fun computeCompatibilityScore(
        current: TrackAnalysis,
        candidate: TrackAnalysis,
        precomputedBpmA: Float? = null
    ): Float {
        val bpmA = precomputedBpmA ?: localBpm(current, current.mixOutMs)
        val bpmB = localBpm(candidate, candidate.cueInMs)
        if (!bpmA.isFinite() || bpmA <= 0f || !bpmB.isFinite() || bpmB <= 0f) return 0.3f

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

        // Harmonic (Camelot) compatibility, weighted by how sure we are of both keys.
        // Unknown keys drop out of the score instead of counting as a clash.
        val keyA = current.musicalKey
        val keyB = candidate.musicalKey
        val keyWeight = if (keyA != null && keyB != null) KEY_WEIGHT * minOf(keyA.confidence, keyB.confidence) else 0f
        val keyScore = if (keyA != null && keyB != null) MusicalKey.compatibility(keyA, keyB) else 0f

        return ((tempoScore * TEMPO_WEIGHT + energyScore * ENERGY_WEIGHT + keyScore * keyWeight) /
            (TEMPO_WEIGHT + ENERGY_WEIGHT + keyWeight)).coerceIn(0f, 1f)
    }

    /**
     * Automatically selects the best DJ Transition Toolset based on the acoustic relationship
     * between the outgoing and incoming tracks (BPM delta, 0.5x/2x harmonic ratio, energy lift/drop,
     * and rhythm confidence for live/drifting-tempo tracks).
     */
    fun selectOptimalTransitionTool(
        fromAnalysis: TrackAnalysis,
        toAnalysis: TrackAnalysis
    ): AutomixTransitionTool {
        // Only fall back to a soft equal-power isolator morph when a track has explicitly
        // measured beat sections that all show very unstable/drifting tempo (< 0.35f confidence,
        // e.g. rubato, live jazz, beatless ambient). Normal or unmeasured beatSections proceed
        // to full DJ transitions.
        val outMaxBeatConf = fromAnalysis.beatSections.maxOfOrNull { it.confidence }
        val inMaxBeatConf = toAnalysis.beatSections.maxOfOrNull { it.confidence }
        if ((outMaxBeatConf != null && outMaxBeatConf < 0.35f) ||
            (inMaxBeatConf != null && inMaxBeatConf < 0.35f)
        ) {
            return AutomixTransitionTool.SILKY_WARM_ISOLATOR
        }

        val bpmA = localBpm(fromAnalysis, fromAnalysis.mixOutMs)
        val bpmB = localBpm(toAnalysis, toAnalysis.cueInMs)
        val directDiff = abs(bpmA - bpmB) / bpmA.coerceAtLeast(1f)
        val halfDoubleDiff = min(abs(bpmA - bpmB * 2f), abs(bpmA - bpmB * 0.5f)) / bpmA.coerceAtLeast(1f)
        val energyDelta = toAnalysis.energy - fromAnalysis.energy

        return when {
            // 0. Vinyl Brake / Tape Stop when transitioning from high energy down to chill with wide tempo gap
            fromAnalysis.energy >= 0.65f && toAnalysis.energy <= 0.48f && directDiff > 0.15f ->
                AutomixTransitionTool.VINYL_BRAKE

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
        isCompleted: Boolean = false,
        entryPositionMs: Long = 0L
    ): SavedTransition {
        val rawScore = computeCompatibilityScore(fromAnalysis, toAnalysis)
        val scorePct = (rawScore * 100f).roundToInt().coerceIn(0, 100)
        val plan = TransitionPlanner.plan(fromAnalysis, toAnalysis, mode, entryPositionMs = entryPositionMs)
        val tool = plan.tool
        val syncPct = ((plan.incomingTempoRatio - 1.0f) * 100f).roundToInt()

        return SavedTransition(
            fromSongId = fromSongId,
            toSongId = toSongId,
            fromBpm = fromAnalysis.bpm,
            toBpm = toAnalysis.bpm,
            matchScorePercent = scorePct,
            overlapDurationMs = plan.durationMs,
            cueInMs = plan.cueInMs,
            transitionTool = tool,
            transitionStyle = "${tool.shortBadge} (${tool.toolsSummary})",
            tempoSyncPercent = syncPct,
            isCompleted = isCompleted,
            quality = plan.quality
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

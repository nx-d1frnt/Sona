package com.sona.player.automix

/** Energy-envelope heuristic, not a vocal/phrase classifier or a calibrated probability. */
internal object MidTrackTransitionDetector {
    data class Result(val positionMs: Long, val confidence: Float = 0f)

    /**
     * Searches the middle-to-upper region of the track (50%..67%) for the natural post-chorus
     * energy step-down or breakdown phrase boundary so Mid-Track DJ Mode always transitions
     * past the halfway mark (>= 50%) and adapts uniquely to every track's structure.
     */
    fun detect(
        coreFrames: FloatArray,
        coreStartMs: Long,
        frameDurationMs: Long,
        estimatedBpm: Float,
        durationMs: Long,
        mixOutMs: Long
    ): Result {
        val minMidMs = (durationMs * 0.50f).toLong().coerceAtLeast(20_000L)
        val maxMidMs = (durationMs * 0.67f).toLong()
            .coerceAtMost((mixOutMs - 7000L).coerceAtLeast(minMidMs))

        if (coreFrames.size < 80 || maxMidMs <= minMidMs) {
            return Result(((minMidMs + maxMidMs) / 2L).coerceIn(minMidMs, maxMidMs.coerceAtLeast(minMidMs)))
        }

        // Group 25ms frames into 500ms musical phrase blocks (20 frames per block)
        val blockSize = (500L / frameDurationMs).toInt().coerceAtLeast(4)
        val numBlocks = coreFrames.size / blockSize
        if (numBlocks < 8) return Result((minMidMs + maxMidMs) / 2L)

        val blockRms = FloatArray(numBlocks)
        val blockGroove = FloatArray(numBlocks)
        for (b in 0 until numBlocks) {
            var rmsSum = 0f
            var fluxSum = 0f
            val offset = b * blockSize
            for (k in 0 until blockSize) {
                val cur = coreFrames[offset + k]
                val prev = if (offset + k > 0) coreFrames[offset + k - 1] else cur
                rmsSum += cur
                if (cur > prev) fluxSum += (cur - prev)
            }
            blockRms[b] = rmsSum / blockSize
            // Combine sustained RMS energy + percussive transient flux
            blockGroove[b] = blockRms[b] * 0.60f + (fluxSum / blockSize) * 2.0f
        }

        val maxGroove = (blockGroove.maxOrNull() ?: 0.1f).coerceAtLeast(0.01f)
        val barMs = ((60_000f / estimatedBpm.coerceIn(75f, 170f)) * 4f).toLong().coerceIn(1400L, 3000L)

        // Evaluate every phrase boundary block inside [minMidMs .. maxMidMs] for:
        // 1) Strongest post-chorus step-down (3.5s chorus window before b -> 3.0s bridge/breakdown window after b)
        // 2) Local breakdown valley contrast (dip after a high-energy peak)
        var bestBoundaryBlock = -1
        var bestBoundaryScore = -Float.MAX_VALUE
        var bestConfidence = 0f
        val lookback = 7  // 3.5s before
        val lookahead = 6 // 3.0s after

        for (b in 3 until (numBlocks - 2)) {
            val blockTimeMs = coreStartMs + (b * 500L)
            if (blockTimeMs < minMidMs || blockTimeMs > maxMidMs + barMs) continue

            var beforeSum = 0f
            var beforeCount = 0
            for (k in 1..lookback) {
                if (b - k >= 0) {
                    beforeSum += blockGroove[b - k]
                    beforeCount++
                }
            }
            var afterSum = 0f
            var afterCount = 0
            for (k in 0 until lookahead) {
                if (b + k < numBlocks) {
                    afterSum += blockGroove[b + k]
                    afterCount++
                }
            }

            val beforeAvg = if (beforeCount > 0) beforeSum / beforeCount else blockGroove[b]
            val afterAvg = if (afterCount > 0) afterSum / afterCount else blockGroove[b]

            // Step-down contrast: positive when a high-energy chorus drops into a bridge/verse/breakdown
            val stepDownContrast = (beforeAvg - afterAvg) / maxGroove
            // Valley depth right at [b, b+1] relative to the preceding section
            val localDip = (beforeAvg - ((blockGroove[b] + blockGroove[(b + 1).coerceAtMost(numBlocks - 1)]) * 0.5f)) / maxGroove
            // Bonus if the preceding section was genuinely an energetic chorus (>= 65% of maxGroove)
            val chorusBonus = if (beforeAvg >= maxGroove * 0.65f) 0.18f else 0f

            val totalScore = stepDownContrast * 1.4f + localDip * 0.9f + chorusBonus
            if (totalScore > bestBoundaryScore) {
                bestBoundaryScore = totalScore
                bestBoundaryBlock = b
                // Confidence requires a full 3.5s before / 3s after, strong preceding audio,
                // and a sustained drop. A local valley or the chorus bonus alone is insufficient.
                val sustained = afterCount == lookahead &&
                    (0 until lookahead).all { blockGroove[b + it] <= beforeAvg * 0.80f }
                bestConfidence = if (beforeCount == lookback && sustained &&
                    beforeAvg >= maxOf(0.04f, maxGroove * 0.65f)
                ) {
                    (((beforeAvg - afterAvg) / beforeAvg) / 0.50f).coerceIn(0f, 1f)
                } else 0f
            }
        }

        if (bestBoundaryBlock < 0) {
            return Result((minMidMs + maxMidMs) / 2L)
        }

        // Start the transition ~1 bar before the post-chorus boundary so the incoming track layers seamlessly
        val candidateMs = coreStartMs + (bestBoundaryBlock * 500L) - barMs
        val positionMs = candidateMs.coerceIn(minMidMs, maxMidMs)
        // A clamped timestamp no longer refers to the measured boundary.
        return Result(positionMs, if (positionMs == candidateMs) bestConfidence else 0f)
    }

}

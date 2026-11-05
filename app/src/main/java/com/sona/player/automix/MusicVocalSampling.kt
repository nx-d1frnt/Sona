package com.sona.player.automix

/** Avoid redundant overlapping contexts only inside confidently vocal passages. */
internal object MusicVocalSampling {
    suspend fun scores(count: Int, infer: suspend (Int) -> Float): FloatArray {
        if (count == 0) return FloatArray(0)
        val scores = FloatArray(count)
        for (index in 0 until count step 2) scores[index] = infer(index)
        if (count % 2 == 0) scores[count - 1] = infer(count - 1)
        for (index in 1 until count - 1 step 2) {
            val left = scores[index - 1]
            val right = scores[index + 1]
            scores[index] = if (left >= 0.75f && right >= 0.75f) {
                // Reuse strong evidence conservatively: this can keep a brief pause
                // classified as vocal, but cannot manufacture a new instrumental gap.
                maxOf(left, right)
            } else {
                // Always probe quiet/uncertain contexts, including apparent instrumentals,
                // so a short voice entry between weak neighbours still gets measured.
                infer(index)
            }
        }
        return scores
    }
}

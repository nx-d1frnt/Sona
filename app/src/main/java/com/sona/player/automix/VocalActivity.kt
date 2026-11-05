package com.sona.player.automix

/** Converts model confidence into estimated activity coverage over measured audio only. */
internal object VocalActivity {
    const val VERSION = 2
    // Relative music vocal evidence and YAMNet speech confidence are hints, not probabilities.
    private const val ACTIVITY_THRESHOLD = 0.5f

    fun ratio(sections: List<VocalSection>, durationMs: Long): Double? {
        if (durationMs <= 0L) return null
        val valid = sections.filter { it.score.isFinite() }.mapNotNull {
            val start = it.startMs.coerceIn(0L, durationMs)
            val end = it.endMs.coerceIn(0L, durationMs)
            if (end > start) it.copy(startMs = start, endMs = end) else null
        }
        val measuredMs = coveredMs(valid)
        if (measuredMs == 0L) return null
        val vocalMs = coveredMs(valid.filter { it.score >= ACTIVITY_THRESHOLD })
        return vocalMs.toDouble() / measuredMs
    }

    // Intro/core/outro windows can overlap on short tracks. Count each millisecond once.
    private fun coveredMs(sections: List<VocalSection>): Long {
        var total = 0L
        var end = 0L
        for (section in sections.sortedBy { it.startMs }) {
            total += (section.endMs - maxOf(end, section.startMs)).coerceAtLeast(0L)
            end = maxOf(end, section.endMs)
        }
        return total
    }
}

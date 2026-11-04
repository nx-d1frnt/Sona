package com.sona.player.automix

/** Only the measured window is valid; do not extrapolate beats across an entire song. */
data class BeatSection(
    val startMs: Long,
    val endMs: Long,
    val bpm: Float,
    val confidence: Float,
    val beatsMs: List<Long>,
    val downbeatPhase: Int = 0
) {
    /** Returns all timestamps in [beatsMs] identified as Bar 1 / downbeats (every 4 beats). */
    val downbeatsMs: List<Long>
        get() = beatsMs.filterIndexed { index, _ -> (index % 4) == (downbeatPhase % 4) }

    /** Returns phrase boundaries (every 16 beats / 4 bars) starting from the first downbeat. */
    val phraseBoundaries16Ms: List<Long>
        get() = beatsMs.filterIndexed { index, _ -> (index % 16) == (downbeatPhase % 16) }

    /** Returns phrase boundaries (every 32 beats / 8 bars) starting from the first downbeat. */
    val phraseBoundaries32Ms: List<Long>
        get() = beatsMs.filterIndexed { index, _ -> (index % 32) == (downbeatPhase % 32) }
}

/** YAMNet class score, not a calibrated probability that a segment is instrumental. */
data class VocalSection(val startMs: Long, val endMs: Long, val score: Float)

/** Local mono loudness estimate and observed sample peak, not full-track integrated LUFS. */
data class LoudnessSection(val startMs: Long, val endMs: Long, val lufs: Float, val samplePeak: Float)

internal data class AudioWindow(val startMs: Long, val samples: FloatArray, val sampleRate: Int = 16_000) {
    val endMs: Long get() = startMs + samples.size * 1000L / sampleRate

    fun rmsEnvelope(frameMs: Int): FloatArray {
        val frameSize = sampleRate * frameMs / 1000
        if (frameSize <= 0) return FloatArray(0)
        return FloatArray(samples.size / frameSize) { frame ->
            var squares = 0.0
            for (i in frame * frameSize until (frame + 1) * frameSize) {
                squares += samples[i].toDouble() * samples[i]
            }
            kotlin.math.sqrt(squares / frameSize).toFloat()
        }
    }
}

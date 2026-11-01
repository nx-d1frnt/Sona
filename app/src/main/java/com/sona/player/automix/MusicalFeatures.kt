package com.sona.player.automix

/** Only the measured window is valid; do not extrapolate beats across an entire song. */
data class BeatSection(
    val startMs: Long,
    val endMs: Long,
    val bpm: Float,
    val confidence: Float,
    val beatsMs: List<Long>
)

/** YAMNet class score, not a calibrated probability that a segment is instrumental. */
data class VocalSection(val startMs: Long, val endMs: Long, val score: Float)

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

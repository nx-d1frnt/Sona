package com.sona.player.automix

import kotlin.math.*

/** Windowed mono K-weighted estimate with 400ms / 100ms absolute and relative gating.
 * Follows the BS.1770 weighting/gating design; not a full-track stereo/true-peak measurement.
 */
internal object LoudnessAnalyzer {
    fun analyze(window: AudioWindow): LoudnessSection? {
        val rate = window.sampleRate
        val block = rate * 400 / 1000
        val hop = rate / 10
        if (window.samples.size < block || rate < 8000) return null
        val shelf = shelf(rate)
        val highPass = highPass(rate)
        val history = DoubleArray(block)
        val powers = mutableListOf<Double>()
        var sum = 0.0
        var peak = 0f
        for (i in window.samples.indices) {
            val sample = window.samples[i]
            if (!sample.isFinite()) return null
            peak = max(peak, abs(sample))
            val weighted = highPass.process(shelf.process(sample.toDouble()))
            val power = weighted * weighted
            sum += power - history[i % block]
            history[i % block] = power
            if (i + 1 >= block && (i + 1 - block) % hop == 0) powers += (sum / block).coerceAtLeast(0.0)
        }
        val absolute = powers.filter { lufs(it) > -70.0 }
        if (absolute.isEmpty()) return null
        val relativeGate = lufs(absolute.average()) - 10.0
        val gated = absolute.filter { lufs(it) >= relativeGate }
        val value = lufs(gated.average())
        return if (value.isFinite()) LoudnessSection(window.startMs, window.endMs, value.toFloat(), peak) else null
    }

    private fun lufs(power: Double) = if (power > 0) -0.691 + 10 * log10(power) else Double.NEGATIVE_INFINITY

    // Bilinear K-weighting prototypes (De Man): frequency/Q chosen to match BS.1770 at 48 kHz.
    private fun shelf(rate: Int): Biquad {
        val k = tan(PI * 1681.9744509555319 / rate)
        val q = 0.7071752369554193
        val vh = 10.0.pow(3.99984385397 / 20)
        val vb = vh.pow(0.499666774155)
        val d = 1 + k / q + k * k
        return Biquad((vh + vb * k / q + k * k) / d, 2 * (k * k - vh) / d,
            (vh - vb * k / q + k * k) / d, 2 * (k * k - 1) / d, (1 - k / q + k * k) / d)
    }

    private fun highPass(rate: Int): Biquad {
        val k = tan(PI * 38.13547087602444 / rate)
        val q = 0.5003270373238773
        val d = 1 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / d, (1 - k / q + k * k) / d)
    }

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
}

internal object LoudnessNormalizer {
    fun gain(track: TrackAnalysis?, positionMs: Long? = null): Float {
        val core = track?.loudnessSections?.minByOrNull {
            abs((it.startMs + it.endMs) / 2 - (track.durationMs * 0.60).toLong())
        }
        val section = if (positionMs == null) core else track?.loudnessSections
            ?.firstOrNull { positionMs in it.startMs until it.endMs } ?: core
        return gain(section)
    }

    fun gain(section: LoudnessSection?): Float {
        if (section == null || !section.lufs.isFinite() || section.lufs < -55f ||
            !section.samplePeak.isFinite() || section.samplePeak <= 0f) return 1f
        val correctionDb = (-18f - section.lufs).coerceIn(-12f, 6f)
        // Leave sample headroom; the linked limiter in the DSP also catches unmeasured peaks.
        return min(10.0.pow(correctionDb / 20.0).toFloat(), 0.89f / section.samplePeak).coerceIn(0.1f, 2f)
    }
}

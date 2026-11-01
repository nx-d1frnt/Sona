package com.sona.player.automix

import kotlin.math.*

/** Local beat grid from multi-band spectral onsets, autocorrelation and measured onset peaks. */
internal object BeatTracker {
    private const val FFT_SIZE = 512
    private const val HOP = 160 // 10 ms at 16 kHz

    fun analyze(window: AudioWindow): BeatSection? {
        if (window.sampleRate != 16_000 || window.samples.size < 16_000 * 5) return null
        val novelty = spectralOnsets(window.samples)
        val mean = novelty.average().toFloat()
        val peak = novelty.maxOrNull() ?: return null
        if (peak < 0.001f) return null
        for (i in novelty.indices) novelty[i] = (novelty[i] - mean * 1.3f).coerceAtLeast(0f)
        val power = novelty.sumOf { it.toDouble() * it }
        if (power <= 1e-8) return null

        // 70..180 BPM; tempo remains uncertain when half/double-time pulses are indistinguishable.
        val minLag = 33
        val maxLag = 86
        val correlation = DoubleArray(maxLag + 1)
        var bestLag = minLag
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var powerA = 0.0
            var powerB = 0.0
            for (i in lag until novelty.size) {
                sum += novelty[i].toDouble() * novelty[i - lag]
                powerA += novelty[i].toDouble() * novelty[i]
                powerB += novelty[i - lag].toDouble() * novelty[i - lag]
            }
            correlation[lag] = if (powerA * powerB > 1e-12) sum / sqrt(powerA * powerB) else 0.0
            if (correlation[lag] > correlation[bestLag]) bestLag = lag
        }
        if (correlation[bestLag] < 0.18) return null
        var periodMs = bestLag * 10.0
        if (bestLag > minLag && bestLag < maxLag) {
            val a = correlation[bestLag - 1]
            val b = correlation[bestLag]
            val c = correlation[bestLag + 1]
            val denominator = a - 2 * b + c
            if (abs(denominator) > 1e-9) periodMs += (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5) * 10
        }

        val peaks = mutableListOf<Double>()
        val strengths = mutableListOf<Float>()
        for (i in 2 until novelty.size - 2) {
            if (novelty[i] < peak * 0.12f || novelty[i] < novelty[i - 1] || novelty[i] <= novelty[i + 1]) continue
            // FFT windows are centered. Compensate their half-window when mapping to source time.
            val time = i * 10.0 + FFT_SIZE * 500.0 / window.sampleRate
            if (peaks.isNotEmpty() && time - peaks.last() < 160) {
                if (novelty[i] > strengths.last()) {
                    peaks[peaks.lastIndex] = time
                    strengths[strengths.lastIndex] = novelty[i]
                }
            } else {
                peaks += time
                strengths += novelty[i]
            }
        }
        if (peaks.size < 6) return null

        // Refine the period from nearby measured inter-onset intervals; avoids integer-lag BPM drift.
        val intervals = peaks.zipWithNext { a, b -> b - a }.filter { abs(it - periodMs) < periodMs * 0.12 }.sorted()
        if (intervals.size >= 4) periodMs = intervals[intervals.size / 2]
        val tolerance = min(65.0, periodMs * 0.14)
        var bestBeats = emptyList<Double>()
        var bestQuality = 0.0
        for (origin in peaks.take(24)) {
            val first = origin - floor(origin / periodMs) * periodMs
            val matched = mutableListOf<Double>()
            var expected = first
            var total = 0
            while (expected <= novelty.size * 10.0) {
                total++
                val actual = peaks.minByOrNull { abs(it - expected) }
                if (actual != null && abs(actual - expected) <= tolerance) matched += actual
                expected += periodMs
            }
            val coverage = matched.size.toDouble() / total.coerceAtLeast(1)
            val precision = matched.size.toDouble() / peaks.size
            val quality = coverage * 0.7 + precision * 0.3
            if (quality > bestQuality) {
                bestQuality = quality
                bestBeats = matched
            }
        }
        if (bestBeats.size < 6) return null
        val confidence = (bestQuality * min(1.0, correlation[bestLag] / 0.65)).toFloat().coerceIn(0f, 1f)
        return BeatSection(window.startMs, window.endMs, (60_000 / periodMs).toFloat(), confidence,
            bestBeats.distinct().map { window.startMs + it.roundToLong() })
    }

    private fun spectralOnsets(samples: FloatArray): FloatArray {
        val count = (samples.size - FFT_SIZE) / HOP + 1
        val result = FloatArray(count)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val previous = DoubleArray(FFT_SIZE / 2)
        val hann = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }
        for (frame in 0 until count) {
            for (i in 0 until FFT_SIZE) {
                real[i] = samples[frame * HOP + i] * hann[i]
                imaginary[i] = 0.0
            }
            fft(real, imaginary)
            var flux = 0.0
            for (bin in 2 until 160) { // ~62..5000 Hz; bass weighted for kick attacks.
                val magnitude = ln(1 + hypot(real[bin], imaginary[bin]))
                flux += max(0.0, magnitude - previous[bin]) * if (bin < 8) 1.5 else 1.0
                previous[bin] = magnitude
            }
            if (frame > 0) result[frame] = (flux / 158).toFloat()
        }
        return result
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val temp = real[i]; real[i] = real[j]; real[j] = temp
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val wrStep = cos(angle)
            val wiStep = sin(angle)
            for (start in 0 until n step length) {
                var wr = 1.0
                var wi = 0.0
                for (offset in 0 until length / 2) {
                    val a = start + offset
                    val b = a + length / 2
                    val tr = real[b] * wr - imaginary[b] * wi
                    val ti = real[b] * wi + imaginary[b] * wr
                    real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                    real[a] += tr; imaginary[a] += ti
                    val nextWr = wr * wrStep - wi * wiStep
                    wi = wr * wiStep + wi * wrStep
                    wr = nextWr
                }
            }
            length *= 2
        }
    }
}

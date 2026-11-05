package com.sona.player.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Estimated tonic (0 = C .. 11 = B) and mode, with a 0..1 heuristic confidence. */
data class MusicalKey(val tonic: Int, val minor: Boolean, val confidence: Float) {
    /** Camelot wheel number 1..12 (e.g. C major = 8B, A minor = 8A). */
    val camelotNumber: Int get() = (tonic * 7 + if (minor) 4 else 7) % 12 + 1
    val camelotLabel: String get() = "$camelotNumber${if (minor) 'A' else 'B'}"

    /** Packed form for storage: 0..11 major, 12..23 minor. */
    val index: Int get() = tonic + if (minor) 12 else 0

    companion object {
        fun fromIndex(index: Int, confidence: Float): MusicalKey? =
            if (index in 0..23) MusicalKey(index % 12, index >= 12, confidence.coerceIn(0f, 1f)) else null

        /** DJ harmonic-mixing compatibility on the Camelot wheel, 0..1. */
        fun compatibility(a: MusicalKey, b: MusicalKey): Float {
            val raw = abs(a.camelotNumber - b.camelotNumber)
            val distance = minOf(raw, 12 - raw)
            val sameMode = a.minor == b.minor
            return when {
                distance == 0 && sameMode -> 1.0f   // Same key
                distance == 0 -> 0.9f               // Relative major/minor
                distance == 1 && sameMode -> 0.9f   // Adjacent (fifth up/down)
                distance == 1 -> 0.6f               // Diagonal
                distance == 2 && sameMode -> 0.55f  // "Energy boost" move
                else -> 0.3f
            }
        }
    }
}

/**
 * Key estimation using Temperley profiles adapted for modern popular music with spectral
 * peak extraction and tuning compensation.
 *
 * Classical Krumhansl-Schmuckler profiles have an inherent mathematical bias towards minor keys
 * and frequently misclassify popular music chord progressions (such as I-iii-vi-IV) as the mediant
 * minor key (e.g. E Major misclassified as G# Minor). Temperley profiles equalize major/minor mean
 * weights and balance natural-minor scale degrees. Peak picking ensures only sinusoidal partials
 * contribute to chroma, eliminating broadband noise and skirt smear.
 */
internal object KeyDetector {
    private const val FFT_SIZE = 4096 // ~3.9 Hz bins at 16 kHz
    private const val HOP = 4096
    private const val MIN_HZ = 130.81 // C3: below this, 3.9 Hz bins approach or exceed a semitone (~7.3 Hz)
    private const val MAX_HZ = 2_100.0

    private val MAJOR = doubleArrayOf(5.0, 2.0, 3.5, 2.0, 4.5, 4.0, 2.0, 4.5, 2.0, 3.5, 1.5, 4.0)
    private val MINOR = doubleArrayOf(5.0, 2.0, 3.5, 4.5, 2.0, 4.0, 2.0, 4.5, 3.5, 2.0, 4.0, 1.5)

    private val hann = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }

    /** Adds this window's chroma to [chroma] (size 12). Returns the number of frames used. */
    fun accumulate(window: AudioWindow, chroma: DoubleArray): Int {
        require(chroma.size == 12)
        val samples = window.samples
        if (samples.size < FFT_SIZE) return 0
        val binHz = window.sampleRate.toDouble() / FFT_SIZE
        val firstBin = (MIN_HZ / binHz).toInt().coerceAtLeast(2)
        val lastBin = (MAX_HZ / binHz).toInt().coerceAtMost(FFT_SIZE / 2 - 2)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val magnitudes = DoubleArray(lastBin + 2)
        val frame = DoubleArray(12)
        val framesMidi = ArrayList<DoubleArray>()
        val framesWeight = ArrayList<DoubleArray>()
        var offset = 0
        while (offset + FFT_SIZE <= samples.size) {
            var energy = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = samples[offset + i].toDouble()
                energy += s * s
                real[i] = s * hann[i]
                imaginary[i] = 0.0
            }
            offset += HOP
            if (energy / FFT_SIZE < 1e-6) continue // Skip silence
            Fft.transform(real, imaginary)
            for (bin in (firstBin - 1)..(lastBin + 1)) {
                magnitudes[bin] = ln(1.0 + sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]))
            }
            val midiList = DoubleArray(lastBin - firstBin + 1)
            val weightList = DoubleArray(lastBin - firstBin + 1)
            var count = 0
            for (bin in firstBin..lastBin) {
                val mag = magnitudes[bin]
                if (mag <= 1e-6) continue
                val prev = magnitudes[bin - 1]
                val next = magnitudes[bin + 1]
                // Only retain spectral peaks (local maxima) to reject broadband noise and side lobes
                if (mag < prev || mag < next) continue
                // Parabolic peak interpolation refines sub-bin frequency and enables tuning estimation
                val denom = prev - 2.0 * mag + next
                val delta = if (abs(denom) > 1e-9) (0.5 * (prev - next) / denom).coerceIn(-0.5, 0.5) else 0.0
                val freq = (bin + delta) * binHz
                midiList[count] = 69.0 + 12.0 * ln(freq / 440.0) / ln(2.0)
                weightList[count] = mag
                count++
            }
            if (count > 0) {
                framesMidi.add(if (count == midiList.size) midiList else midiList.copyOf(count))
                framesWeight.add(if (count == weightList.size) weightList else weightList.copyOf(count))
            }
        }
        if (framesMidi.isEmpty()) return 0

        // Estimate global tuning offset in [-0.5 .. +0.5] semitones via circular mean of fractional MIDI pitch
        var sinSum = 0.0
        var cosSum = 0.0
        for (f in framesMidi.indices) {
            val midis = framesMidi[f]
            val weights = framesWeight[f]
            for (k in midis.indices) {
                val m = midis[k]
                val frac = m - m.roundToInt() // [-0.5 .. +0.5]
                val angle = 2.0 * PI * frac
                sinSum += weights[k] * kotlin.math.sin(angle)
                cosSum += weights[k] * cos(angle)
            }
        }
        val tuningOffset = if (sinSum * sinSum + cosSum * cosSum > 1e-9) {
            ( kotlin.math.atan2(sinSum, cosSum) / (2.0 * PI) ).coerceIn(-0.45, 0.45)
        } else 0.0

        var used = 0
        for (f in framesMidi.indices) {
            val midis = framesMidi[f]
            val weights = framesWeight[f]
            frame.fill(0.0)
            for (k in midis.indices) {
                val corrected = midis[k] - tuningOffset
                // Soft bilinear interpolation across adjacent pitch classes prevents boundary jitter
                val lower = kotlin.math.floor(corrected)
                val frac = corrected - lower
                val pc0 = ((lower.toInt() % 12) + 12) % 12
                val pc1 = (pc0 + 1) % 12
                frame[pc0] += weights[k] * (1.0 - frac)
                frame[pc1] += weights[k] * frac
            }
            // Per-frame normalisation so loud passages don't dominate the profile.
            val total = frame.sum()
            if (total <= 1e-9) continue
            for (pc in 0 until 12) chroma[pc] += frame[pc] / total
            used++
        }
        return used
    }

    fun estimate(chroma: DoubleArray, frames: Int): MusicalKey? {
        if (frames < 20 || chroma.sum() <= 1e-9) return null
        var best = -2.0
        var bestIndex = -1
        for (index in 0 until 24) {
            val profile = if (index < 12) MAJOR else MINOR
            val tonic = index % 12
            val r = pearson(chroma) { pc -> profile[(pc - tonic + 12) % 12] }
            if (r > best) { best = r; bestIndex = index }
        }
        if (bestIndex < 0 || best < 0.3) return null
        val confidence = ((best - 0.3) / 0.5).toFloat().coerceIn(0f, 1f)
        return MusicalKey.fromIndex(bestIndex, confidence)
    }

    private inline fun pearson(x: DoubleArray, y: (Int) -> Double): Double {
        val meanX = x.average()
        var meanY = 0.0
        for (i in 0 until 12) meanY += y(i)
        meanY /= 12
        var cov = 0.0
        var varX = 0.0
        var varY = 0.0
        for (i in 0 until 12) {
            val dx = x[i] - meanX
            val dy = y(i) - meanY
            cov += dx * dy; varX += dx * dx; varY += dy * dy
        }
        return if (varX * varY > 1e-12) cov / sqrt(varX * varY) else 0.0
    }
}

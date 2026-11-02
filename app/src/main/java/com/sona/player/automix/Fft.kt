package com.sona.player.automix

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place iterative radix-2 FFT shared by the analysis passes. [real].size must be a power of two. */
internal object Fft {
    fun transform(real: DoubleArray, imaginary: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val temp = real[i]; real[i] = real[j]; real[j] = temp
                val tempI = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = tempI
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

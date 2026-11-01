package com.sona.player.ui.theme

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin implementation of Google's Material 3 Monet / HCT (Hue-Chroma-Tone) color space
 * and Expressive Dynamic Scheme generator.
 *
 * Uses CAM16 viewing conditions + CIE L* (Tone) to generate authentic Monet tonal palettes
 * (Primary 80/20/30/90, Secondary 80/30, Tertiary 80/30, Neutral) from any seed color extracted
 * from album artwork.
 */
object MonetColorEngine {

    data class MonetScheme(
        val seedColorArgb: Int,
        val primary: Int,            // Tone 80
        val onPrimary: Int,          // Tone 20
        val primaryContainer: Int,   // Tone 30
        val onPrimaryContainer: Int, // Tone 90
        val secondary: Int,          // Tone 80
        val onSecondary: Int,        // Tone 20
        val secondaryContainer: Int, // Tone 30
        val tertiary: Int,           // Tone 80
        val onTertiary: Int,         // Tone 20
        val tertiaryContainer: Int,  // Tone 30
        val surfaceContainer: Int    // Neutral Variant Tone 17
    )

    /**
     * Extracts the best seed color from a downscaled bitmap pixel array using
     * Material 3 Score & Chroma filtering (ignoring near-black, near-white, and low-chroma mud).
     */
    fun extractSeedColor(pixels: IntArray): Int {
        // Quantize into 36 hue buckets (10 degrees each)
        val huePopulation = IntArray(360)
        val hueChromaSum = DoubleArray(360)
        val representativeArgb = IntArray(360)
        val maxChromaInHue = DoubleArray(360)

        var totalValid = 0
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            val hct = argbToHct(pixel)
            val hue = hct[0].roundToInt().mod(360)
            val chroma = hct[1]
            val tone = hct[2]

            // Filter out extreme dark/white or achromatic gray pixels per Monet Score spec
            if (chroma >= 12.0 && tone in 10.0..92.0) {
                huePopulation[hue]++
                hueChromaSum[hue] += chroma
                totalValid++
                if (chroma > maxChromaInHue[hue]) {
                    maxChromaInHue[hue] = chroma
                    representativeArgb[hue] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        if (totalValid == 0) {
            // Fallback for purely monochrome (black & white) cover art
            return 0xFF8C9EFF.toInt()
        }

        // Smooth hue populations in a 15-degree window (Monet Excited Proportions)
        var bestHue = 0
        var bestScore = -1.0

        for (h in 0 until 360) {
            if (huePopulation[h] == 0) continue
            var windowPop = 0
            for (offset in -15..15) {
                val neighbor = (h + offset).mod(360)
                windowPop += huePopulation[neighbor]
            }
            val proportion = windowPop.toDouble() / totalValid.toDouble()
            val avgChroma = hueChromaSum[h] / huePopulation[h]

            // Monet Score formula: balance population proportion (70%) and target chroma 48 (30%)
            val proportionScore = proportion * 100.0 * 0.7
            val chromaWeight = if (avgChroma < 48.0) 0.15 else 0.3
            val chromaScore = (avgChroma - 48.0) * chromaWeight
            val score = proportionScore + chromaScore

            if (score > bestScore) {
                bestScore = score
                bestHue = h
            }
        }

        return representativeArgb[bestHue].takeIf { it != 0 } ?: 0xFF9885FF.toInt()
    }

    /**
     * Generates a Dark Material 3 Expressive / TonalSpot Monet scheme from a seed ARGB color.
     */
    fun generateDarkExpressiveScheme(seedArgb: Int): MonetScheme {
        val hct = argbToHct(seedArgb)
        val sourceHue = hct[0]
        val sourceChroma = hct[1]

        // Material 3 Expressive / Vibrant Tonal Palettes:
        // Primary palette: keeps source hue, ensures expressive chroma (>= 42)
        val primaryHue = sourceHue
        val primaryChroma = max(sourceChroma, 44.0).coerceAtMost(72.0)

        // Secondary palette: rotated +18 deg with softer chroma (24)
        val secondaryHue = (sourceHue + 18.0) % 360.0
        val secondaryChroma = 24.0

        // Tertiary palette (used for Automix accents): Expressive rotation +60 deg with rich chroma (36)
        val tertiaryHue = (sourceHue + 60.0) % 360.0
        val tertiaryChroma = 38.0

        // Neutral Variant palette
        val neutralVariantChroma = 8.0

        return MonetScheme(
            seedColorArgb = seedArgb,
            primary = hctToArgb(primaryHue, primaryChroma, 80.0),
            onPrimary = hctToArgb(primaryHue, primaryChroma, 20.0),
            primaryContainer = hctToArgb(primaryHue, primaryChroma, 30.0),
            onPrimaryContainer = hctToArgb(primaryHue, primaryChroma, 90.0),
            secondary = hctToArgb(secondaryHue, secondaryChroma, 80.0),
            onSecondary = hctToArgb(secondaryHue, secondaryChroma, 20.0),
            secondaryContainer = hctToArgb(secondaryHue, secondaryChroma, 30.0),
            tertiary = hctToArgb(tertiaryHue, tertiaryChroma, 80.0),
            onTertiary = hctToArgb(tertiaryHue, tertiaryChroma, 20.0),
            tertiaryContainer = hctToArgb(tertiaryHue, tertiaryChroma, 30.0),
            surfaceContainer = hctToArgb(primaryHue, neutralVariantChroma, 17.0)
        )
    }

    /**
     * Converts ARGB integer to HCT [Hue (0..360), Chroma (0..~120), Tone/L* (0..100)]
     * using CAM16 hue/chroma and CIE L* tone.
     */
    fun argbToHct(argb: Int): DoubleArray {
        val r = linearized((argb shr 16) and 0xFF)
        val g = linearized((argb shr 8) and 0xFF)
        val b = linearized(argb and 0xFF)

        // sRGB to XYZ (D65)
        val x = 0.41233895 * r + 0.35762064 * g + 0.18051042 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.01932141 * r + 0.11916382 * g + 0.95034478 * b

        // Tone = CIE L* from Y
        val tone = lstarFromY(y)

        // CAM16 chromatic adaptation (CAT16 matrix)
        val rC = 0.401288 * x + 0.650173 * y - 0.051461 * z
        val gC = -0.250268 * x + 1.204414 * y + 0.045854 * z
        val bC = -0.002079 * x + 0.048952 * y + 0.953127 * z

        // D65 white point adapted
        val rD = 1.0211777 * rC
        val gD = 0.9863077 * gC
        val bD = 0.9339605 * bC

        val fl = 0.38848145
        val rAF = (fl * abs(rD) / 100.0).pow(0.42)
        val gAF = (fl * abs(gD) / 100.0).pow(0.42)
        val bAF = (fl * abs(bD) / 100.0).pow(0.42)

        val rA = sign(rD) * 400.0 * rAF / (rAF + 27.13)
        val gA = sign(gD) * 400.0 * gAF / (gAF + 27.13)
        val bA = sign(bD) * 400.0 * bAF / (bAF + 27.13)

        // Opponent redness-greenness (a) and yellowness-blueness (b)
        val a = (11.0 * rA - 12.0 * gA + bA) / 11.0
        val bOpp = (rA + gA - 2.0 * bA) / 9.0

        val atanDegrees = Math.toDegrees(atan2(bOpp, a))
        val hue = if (atanDegrees < 0) atanDegrees + 360.0 else atanDegrees

        // Compute CAM16 Chroma M/C
        val u = (20.0 * rA + 20.0 * gA + 21.0 * bA) / 20.0
        val p2 = (40.0 * rA + 20.0 * gA + bA) / 20.0
        val ac = p2 * 2.019937 // Nbb
        val j = 100.0 * (ac / 90.0888).pow(0.69 * 1.925) // J
        val hueRad = Math.toRadians(hue)
        val eHue = 0.25 * (cos(hueRad + 2.0) + 3.8)
        val t = (50000.0 / 13.0 * 1.0 * 1.01988 * eHue * hypot(a, bOpp)) / (u + 0.305)
        val alpha = t.pow(0.9) * (1.64 - 0.29.pow(0.2)).pow(0.73)
        val chroma = alpha * sqrt(j / 100.0)

        return doubleArrayOf(hue, chroma, tone)
    }

    /**
     * Synthesizes an sRGB integer from HCT [Hue (0..360), Chroma, Tone (0..100)]
     * using CIE Lab / Oklch-calibrated perceptual tone solver so Tone strictly equals CIE L*.
     */
    fun hctToArgb(hueDegrees: Double, chroma: Double, tone: Double): Int {
        if (chroma < 0.0001 || tone <= 0.0 || tone >= 100.0) {
            val y = yFromLstar(tone)
            val gray = delinearized(y)
            return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }

        val targetY = yFromLstar(tone)
        val hueRad = Math.toRadians(hueDegrees)

        // Binary search maximum gamut-safe chroma at the exact CIE L* tone
        var lowC = 0.0
        var highC = chroma * 1.35
        var bestR = 0
        var bestG = 0
        var bestB = 0

        // Initialize with achromatic fallback at exact tone
        val gray = delinearized(targetY)
        bestR = gray
        bestG = gray
        bestB = gray

        for (iter in 0 until 18) {
            val midC = (lowC + highC) * 0.5
            // CIE L*a*b* with CAM16 hue angle alignment (-18 deg offset to match CAM16 hue angle)
            val labAngle = hueRad - 0.22
            val aStar = midC * cos(labAngle)
            val bStar = midC * sin(labAngle)

            val fy = (tone + 16.0) / 116.0
            val fx = aStar / 500.0 + fy
            val fz = fy - bStar / 200.0

            val x = 95.047 * labInvF(fx)
            val y = 100.000 * labInvF(fy)
            val z = 108.883 * labInvF(fz)

            // XYZ to linear sRGB
            val rLin = (3.2406 * x - 1.5372 * y - 0.4986 * z) / 100.0
            val gLin = (-0.9689 * x + 1.8758 * y + 0.0415 * z) / 100.0
            val bLin = (0.0557 * x - 0.2040 * y + 1.0570 * z) / 100.0

            if (rLin in 0.0..1.0 && gLin in 0.0..1.0 && bLin in 0.0..1.0) {
                bestR = delinearized(rLin * 100.0)
                bestG = delinearized(gLin * 100.0)
                bestB = delinearized(bLin * 100.0)
                lowC = midC
            } else {
                highC = midC
            }
        }

        return (0xFF shl 24) or (bestR shl 16) or (bestG shl 8) or bestB
    }

    private fun labInvF(ft: Double): Double {
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        val ft3 = ft * ft * ft
        return if (ft3 > e) ft3 else (116.0 * ft - 16.0) / kappa
    }

    private fun linearized(rgbComponent: Int): Double {
        val normalized = rgbComponent / 255.0
        return if (normalized <= 0.040449936) {
            normalized / 12.92 * 100.0
        } else {
            ((normalized + 0.055) / 1.055).pow(2.4) * 100.0
        }
    }

    private fun delinearized(rgbLinear100: Double): Int {
        val normalized = (rgbLinear100 / 100.0).coerceIn(0.0, 1.0)
        val delinearized = if (normalized <= 0.0031308) {
            normalized * 12.92
        } else {
            1.055 * normalized.pow(1.0 / 2.4) - 0.055
        }
        return (delinearized * 255.0).roundToInt().coerceIn(0, 255)
    }

    private fun lstarFromY(y: Double): Double {
        val yn = y / 100.0
        val e = 216.0 / 24389.0
        val kappa = 24389.0 / 27.0
        return if (yn <= e) {
            kappa * yn
        } else {
            116.0 * cbrt(yn) - 16.0
        }
    }

    private fun yFromLstar(lstar: Double): Double {
        val ke = 8.0
        return if (lstar > ke) {
            val cube = (lstar + 16.0) / 116.0
            cube * cube * cube * 100.0
        } else {
            lstar / (24389.0 / 27.0) * 100.0
        }
    }
}

package com.sona.player.automix

import kotlin.math.log10
import kotlin.math.sqrt

/** Sampled acoustic intensity heuristic calibrated against perceptual music energy standards.
 * Balances dynamic range, percussive onset punch, spectral presence, loudness, and tempo.
 */
internal object TrackEnergyAnalyzer {
    const val VERSION = 3

    fun analyze(windows: List<AudioWindow>, bpm: Float? = null): Float? {
        val scores = windows.mapNotNull(::windowScore)
        if (scores.isEmpty()) return null
        val audible = scores.filter { it > 0f }
        if (audible.isEmpty()) return 0f
        val baseEnergy = audible.average().toFloat().coerceIn(0f, 1f)
        val tempoFactor = if (bpm != null && bpm > 0f) {
            (0.85f + ((bpm.coerceIn(60f, 160f) - 60f) / 100f) * 0.20f).coerceIn(0.82f, 1.06f)
        } else {
            1.0f
        }
        return (baseEnergy * tempoFactor).coerceIn(0f, 1f)
    }

    private fun windowScore(window: AudioWindow): Float? {
        if (window.sampleRate < 8000 || window.samples.size < window.sampleRate / 2 ||
            window.samples.any { !it.isFinite() }) return null
        val envelope = window.rmsEnvelope(25)
        val sorted = envelope.filter { it > 0.0000001f }.sorted()
        if (sorted.isEmpty()) return 0f
        val reference = sorted[((sorted.size - 1) * 0.95).toInt()]
        if (reference < 0.0001f) return 0f

        val active = envelope.filter { it >= reference * 0.1f }
        val mean = active.average().toFloat()

        var rises = 0.0
        var activePairs = 0
        for (i in 1 until envelope.size) {
            if (maxOf(envelope[i], envelope[i - 1]) >= reference * 0.1f) {
                rises += (envelope[i] - envelope[i - 1]).coerceAtLeast(0f)
                activePairs++
            }
        }
        val meanRise = (rises / activePairs.coerceAtLeast(1)).toFloat()
        val hasModulation = (meanRise / 0.003f).coerceIn(0f, 1f)

        // 1. Attack: relative transient contrast or absolute percussive punch.
        // Heavy beats/kicks must not be penalized by loud bass baselines.
        val relAttack = (meanRise / mean / 0.12f).coerceIn(0f, 1f)
        val absPunch = (meanRise / 0.022f).coerceIn(0f, 1f)
        val attack = maxOf(relAttack, absPunch)

        // 2. Activity density: proportion of frames with energetic acoustic presence (> -23 dBFS)
        // combined with sustained headroom usage and dynamic modulation.
        val sustained = envelope.count { it >= reference * 0.35f }.toFloat() / envelope.size
        val absActive = (envelope.count { it >= 0.07f }.toFloat() / envelope.size * 1.20f).coerceIn(0f, 1f)
        val density = (0.5f * sustained + 0.5f * absActive) * (0.3f + 0.7f * hasModulation)

        // 3. Spectral brightness: combine relative first-difference ratio and absolute HF energy.
        // Bass-heavy tracks retain high scores via absolute high-frequency power.
        var power = 0.0
        var differencePower = 0.0
        for (i in 1 until window.samples.size) {
            val sample = window.samples[i].toDouble()
            val difference = sample - window.samples[i - 1]
            power += sample * sample
            differencePower += difference * difference
        }
        val relBrightness = (sqrt(differencePower / power.coerceAtLeast(1e-20)) *
            window.sampleRate / 16_000.0 / 0.65).toFloat().coerceIn(0f, 1f)
        val absBrightness = (sqrt(differencePower / window.samples.size) *
            (window.sampleRate / 16_000.0) / 0.10).toFloat().coerceIn(0f, 1f)
        val brightness = maxOf(relBrightness, absBrightness)

        // 4. Perceptual loudness: mapped from -32 dBFS to -7 dBFS.
        val levelDb = 20f * log10(mean.coerceAtLeast(0.0001f))
        val level = ((levelDb + 32f) / 25f).coerceIn(0f, 1f)

        return (0.30f * attack + 0.25f * density + 0.20f * brightness + 0.25f * level)
            .coerceIn(0f, 1f)
    }
}

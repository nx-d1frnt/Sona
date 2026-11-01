package com.sona.player.automix

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI

/**
 * Unconditionally stable, zero-lag stereo DJ Isolator EQ & Filter AudioProcessor for Sona's Automix.
 *
 * Uses a 1-pole state-variable RC isolator topology (2 multiplications per sample, 0% chance of NaN/instability):
 * - Low-Pass filter stage for smooth high-frequency warm fade-out on the outgoing track.
 * - Sub-bass isolator stage (< 260 Hz) for clean DJ "Bass Swap" at the midpoint of the transition.
 */
@OptIn(UnstableApi::class)
class AutomixEqAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var isEqActive: Boolean = false

    // 0.0f = full bass (no cut), 0.92f = deep DJ bass cut (-22 dB below 260 Hz)
    @Volatile
    private var targetBassCut: Float = 0f

    // 1.0f = full highs (20 kHz), 0.14f = warm muffled low-pass filter (~1.4 kHz)
    @Volatile
    private var targetHighPassAlpha: Float = 1f

    private var currentBassCut: Float = 0f
    private var currentLpfAlpha: Float = 1f

    // Per-channel filter states (Left = 0, Right = 1)
    private val lpfState = FloatArray(2)
    private val bassState = FloatArray(2)

    /**
     * Updates the DJ EQ filter state for the outgoing track at normalized transition progress [0..1].
     */
    fun updateOutgoingEq(progress: Float) {
        val t = progress.coerceIn(0f, 1f)

        // Phase 1 (0.0 .. 0.42): Holds full bass foundation
        // Phase 2 (0.42 .. 0.60): DJ Bass Swap! Smoothly cuts bass up to 88%
        targetBassCut = when {
            t < 0.42f -> 0f
            t < 0.60f -> ((t - 0.42f) / 0.18f) * 0.88f
            else -> 0.88f
        }.coerceIn(0f, 0.90f)

        // Phase 3 (0.48 .. 1.00): Warm Low-Pass filter sweep rolling off highs as track fades out
        targetHighPassAlpha = when {
            t < 0.48f -> 1.0f
            else -> {
                val frac = (t - 0.48f) / 0.52f
                (1.0f - frac * 0.82f).coerceIn(0.16f, 1.0f)
            }
        }
        isEqActive = true
    }

    /**
     * Updates the DJ EQ filter state for the incoming track at normalized transition progress [0..1].
     */
    fun updateIncomingEq(progress: Float) {
        val t = progress.coerceIn(0f, 1f)

        // Phase 1 (0.0 .. 0.42): Enters with bass cut (85%) so kicks/sub-bass don't clash
        // Phase 2 (0.42 .. 0.58): DJ Bass Swap! Full bass smoothly drops in (cut -> 0%)
        targetBassCut = when {
            t < 0.42f -> 0.85f - (t / 0.42f) * 0.15f
            t < 0.58f -> 0.70f * (1f - ((t - 0.42f) / 0.16f))
            else -> 0f
        }.coerceIn(0f, 0.90f)

        // Highs start slightly warm (0.55) and open up to 1.0 (full clarity) by t = 0.35
        targetHighPassAlpha = when {
            t < 0.35f -> 0.55f + (t / 0.35f) * 0.45f
            else -> 1.0f
        }.coerceIn(0.20f, 1.0f)

        isEqActive = (t < 0.62f)
    }

    /**
     * Resets EQ to flat bit-transparent bypass.
     */
    fun resetEq() {
        isEqActive = false
        targetBassCut = 0f
        targetHighPassAlpha = 1f
        currentBassCut = 0f
        currentLpfAlpha = 1f
        lpfState[0] = 0f; lpfState[1] = 0f
        bassState[0] = 0f; bassState[1] = 0f
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT &&
            inputAudioFormat.channelCount in 1..2
        ) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Fast bit-perfect bypass when not in an active EQ transition
        if (!isEqActive && currentBassCut < 0.01f && currentLpfAlpha > 0.99f) {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channels = inputAudioFormat.channelCount.coerceIn(1, 2)
        val frameSize = 2 * channels
        val frameCount = remaining / frameSize
        val byteCount = frameCount * frameSize

        val outputBuffer = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())

        val sampleRate = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        // One-pole alpha for ~250 Hz bass crossover: alpha = 2*PI*fc / (2*PI*fc + sr)
        val wc = (2.0 * PI * 250.0).toFloat()
        val bassAlpha = (wc / (wc + sampleRate)).coerceIn(0.005f, 0.25f)

        // Smooth parameter interpolation per buffer to prevent clicks
        val targetCut = targetBassCut
        val targetLpf = targetHighPassAlpha

        for (frame in 0 until frameCount) {
            currentBassCut += (targetCut - currentBassCut) * 0.004f
            currentLpfAlpha += (targetLpf - currentLpfAlpha) * 0.004f

            for (ch in 0 until channels) {
                val rawShort = inputBuffer.short
                val x = rawShort * (1f / 32768f)

                // 1. Warm Low-Pass stage (controls high frequency roll-off)
                val lpf = lpfState[ch] + currentLpfAlpha * (x - lpfState[ch])
                lpfState[ch] = lpf

                // 2. Sub-bass extraction stage (< 250 Hz)
                val subBass = bassState[ch] + bassAlpha * (lpf - bassState[ch])
                bassState[ch] = subBass

                // 3. Subtract isolated sub-bass according to DJ Bass Cut level
                val y = lpf - (currentBassCut * subBass)

                val outInt = (y * 32767f).toInt().coerceIn(-32768, 32767)
                outputBuffer.putShort(outInt.toShort())
            }
        }

        // Consume any trailing odd bytes so DefaultAudioSink never stalls
        if (inputBuffer.hasRemaining()) {
            inputBuffer.position(inputBuffer.limit())
        }

        outputBuffer.flip()
    }

    override fun onFlush() {
        lpfState[0] = 0f; lpfState[1] = 0f
        bassState[0] = 0f; bassState[1] = 0f
    }

    override fun onReset() {
        resetEq()
    }
}

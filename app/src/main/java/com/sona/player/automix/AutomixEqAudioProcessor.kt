package com.sona.player.automix

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time stereo Biquad Filter & DJ Isolator EQ AudioProcessor for Sona's Automix.
 *
 * Performs a club-grade DJ EQ Transition:
 * 1. Incoming track enters with a High-Pass Filter (~320 Hz, Bass Cut) to prevent muddy kick/bass clashes.
 * 2. At the transition midpoint (t = 0.45 .. 0.58), executes a smooth "Bass Swap":
 *    incoming HPF sweeps down to 20 Hz (full bass drops in) while outgoing HPF sweeps up to 340 Hz.
 * 3. In the second half (t = 0.55 .. 1.0), outgoing Low-Pass Filter sweeps down to 1.6 kHz for a warm filter tail.
 */
@OptIn(UnstableApi::class)
class AutomixEqAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var isEqActive: Boolean = false

    @Volatile
    private var targetHpfHz: Float = 20f

    @Volatile
    private var targetLpfHz: Float = 20000f

    private var currentHpfHz: Float = 20f
    private var currentLpfHz: Float = 20000f

    // Biquad state variables for up to 2 channels (Left = 0, Right = 1)
    private val hpfX1 = FloatArray(2)
    private val hpfX2 = FloatArray(2)
    private val hpfY1 = FloatArray(2)
    private val hpfY2 = FloatArray(2)

    private val lpfX1 = FloatArray(2)
    private val lpfX2 = FloatArray(2)
    private val lpfY1 = FloatArray(2)
    private val lpfY2 = FloatArray(2)

    // Biquad normalized coefficients
    private var hpfB0 = 1f
    private var hpfB1 = 0f
    private var hpfB2 = 0f
    private var hpfA1 = 0f
    private var hpfA2 = 0f

    private var lpfB0 = 1f
    private var lpfB1 = 0f
    private var lpfB2 = 0f
    private var lpfA1 = 0f
    private var lpfA2 = 0f

    /**
     * Updates the DJ EQ filter state for the outgoing track at normalized transition progress [0..1].
     */
    fun updateOutgoingEq(progress: Float) {
        val t = progress.coerceIn(0f, 1f)
        // Phase 1 (0.0 .. 0.40): Full bass, full highs
        // Phase 2 (0.40 .. 0.60): Bass Swap! HPF sweeps from 20 Hz -> 340 Hz
        val hpf = when {
            t < 0.40f -> 20f
            t < 0.62f -> 20f + ((t - 0.40f) / 0.22f) * 320f
            else -> 340f
        }
        // Phase 3 (0.50 .. 1.00): Warm Low-Pass filter sweep from 19 kHz down to 1.5 kHz
        val lpf = when {
            t < 0.50f -> 19500f
            else -> {
                val frac = (t - 0.50f) / 0.50f
                // Exponential-like frequency sweep feels natural to human hearing
                19500f * (1f - frac) * (1f - frac) + 1500f
            }
        }
        targetHpfHz = hpf.coerceIn(20f, 600f)
        targetLpfHz = lpf.coerceIn(1200f, 20000f)
        isEqActive = true
    }

    /**
     * Updates the DJ EQ filter state for the incoming track at normalized transition progress [0..1].
     */
    fun updateIncomingEq(progress: Float) {
        val t = progress.coerceIn(0f, 1f)
        // Phase 1 (0.0 .. 0.42): Bass is cut (HPF ~320 Hz -> 240 Hz) so kicks don't clash
        // Phase 2 (0.42 .. 0.58): Bass Swap! HPF drops from 240 Hz -> 20 Hz (full sub-bass drops in)
        val hpf = when {
            t < 0.42f -> 320f - (t / 0.42f) * 80f
            t < 0.58f -> 240f * (1f - ((t - 0.42f) / 0.16f)) + 20f
            else -> 20f
        }
        // Incoming track starts with a gentle high-frequency sheen opening from 6.5 kHz -> 20 kHz
        val lpf = when {
            t < 0.35f -> 6500f + (t / 0.35f) * 13500f
            else -> 20000f
        }
        targetHpfHz = hpf.coerceIn(20f, 600f)
        targetLpfHz = lpf.coerceIn(1200f, 20000f)
        isEqActive = (t < 0.65f)
    }

    /**
     * Resets EQ to flat bit-transparent bypass.
     */
    fun resetEq() {
        isEqActive = false
        targetHpfHz = 20f
        targetLpfHz = 20000f
        currentHpfHz = 20f
        currentLpfHz = 20000f
        clearFilterState()
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

        val outputBuffer = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())

        if (!isEqActive && currentHpfHz <= 22f && currentLpfHz >= 19500f) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val sampleRate = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)
        val channels = inputAudioFormat.channelCount.coerceIn(1, 2)
        val frameCount = remaining / (2 * channels)

        // Smoothly slew current cutoff frequencies toward targets to prevent zipper noise
        currentHpfHz += (targetHpfHz - currentHpfHz) * 0.25f
        currentLpfHz += (targetLpfHz - currentLpfHz) * 0.25f

        recalculateBiquadCoefficients(sampleRate, currentHpfHz, currentLpfHz)

        for (frame in 0 until frameCount) {
            for (ch in 0 until channels) {
                val rawShort = inputBuffer.short
                val x = rawShort / 32768f

                // 1. Apply 2nd-order Butterworth High-Pass Filter (Bass Cut)
                val yHpf = hpfB0 * x + hpfB1 * hpfX1[ch] + hpfB2 * hpfX2[ch] -
                        hpfA1 * hpfY1[ch] - hpfA2 * hpfY2[ch]
                hpfX2[ch] = hpfX1[ch]
                hpfX1[ch] = x
                hpfY2[ch] = hpfY1[ch]
                hpfY1[ch] = yHpf

                // 2. Apply 2nd-order Butterworth Low-Pass Filter (High Roll-off)
                val yLpf = lpfB0 * yHpf + lpfB1 * lpfX1[ch] + lpfB2 * lpfX2[ch] -
                        lpfA1 * lpfY1[ch] - lpfA2 * lpfY2[ch]
                lpfX2[ch] = lpfX1[ch]
                lpfX1[ch] = yHpf
                lpfY2[ch] = lpfY1[ch]
                lpfY1[ch] = yLpf

                val outSample = (yLpf * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                outputBuffer.putShort(outSample)
            }
        }

        outputBuffer.flip()
    }

    private fun recalculateBiquadCoefficients(
        sampleRate: Float,
        hpfCutoff: Float,
        lpfCutoff: Float
    ) {
        val q = 0.7071f // Butterworth Q (maximally flat passband)

        // High-Pass Biquad
        val w0Hpf = (2.0 * PI * hpfCutoff.coerceIn(18f, sampleRate * 0.45f) / sampleRate).toFloat()
        val cosHpf = cos(w0Hpf)
        val sinHpf = sin(w0Hpf)
        val alphaHpf = sinHpf / (2f * q)
        val a0Hpf = 1f + alphaHpf

        hpfB0 = ((1f + cosHpf) * 0.5f) / a0Hpf
        hpfB1 = -(1f + cosHpf) / a0Hpf
        hpfB2 = ((1f + cosHpf) * 0.5f) / a0Hpf
        hpfA1 = (-2f * cosHpf) / a0Hpf
        hpfA2 = (1f - alphaHpf) / a0Hpf

        // Low-Pass Biquad
        val w0Lpf = (2.0 * PI * lpfCutoff.coerceIn(200f, sampleRate * 0.45f) / sampleRate).toFloat()
        val cosLpf = cos(w0Lpf)
        val sinLpf = sin(w0Lpf)
        val alphaLpf = sinLpf / (2f * q)
        val a0Lpf = 1f + alphaLpf

        lpfB0 = ((1f - cosLpf) * 0.5f) / a0Lpf
        lpfB1 = (1f - cosLpf) / a0Lpf
        lpfB2 = ((1f - cosLpf) * 0.5f) / a0Lpf
        lpfA1 = (-2f * cosLpf) / a0Lpf
        lpfA2 = (1f - alphaLpf) / a0Lpf
    }

    private fun clearFilterState() {
        for (i in 0..1) {
            hpfX1[i] = 0f; hpfX2[i] = 0f; hpfY1[i] = 0f; hpfY2[i] = 0f
            lpfX1[i] = 0f; lpfX2[i] = 0f; lpfY1[i] = 0f; lpfY2[i] = 0f
        }
    }

    override fun onFlush() {
        clearFilterState()
    }

    override fun onReset() {
        resetEq()
    }
}

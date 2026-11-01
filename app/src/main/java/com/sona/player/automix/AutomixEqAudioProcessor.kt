package com.sona.player.automix

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow

/**
 * Unconditionally stable, zero-lag stereo 2-Pole DJ Isolator & Filter Sweep AudioProcessor for Sona's Automix.
 *
 * Implements a 3-stage club DJ mixer transition:
 * 1. High-Pass Entry Filter + 2-Pole Bass Kill (< 240 Hz, 12 dB/oct) on the incoming track so its
 *    vocals, hi-hats, and synths float cleanly over the outgoing track's beat without kick clashing.
 * 2. Tight DJ "Bass Swap" at t = 0.44..0.54 where the outgoing sub-bass is killed at the exact
 *    moment the incoming sub-bass & kick drum drop in with full punch.
 * 3. Logarithmic 2-Pole Low-Pass Filter Sweep (14 kHz -> 260 Hz, 12 dB/oct) on the outgoing track
 *    during the second half of the mix, creating a signature warm "underwater" club filter tail.
 */
@OptIn(UnstableApi::class)
class AutomixEqAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var isEqActive: Boolean = false

    // 0.0f = full bass (no cut), 0.96f = deep 2-pole DJ isolator bass kill (-26 dB below 240 Hz)
    @Volatile
    private var targetBassCut: Float = 0f

    // Low-pass cutoff frequency in Hz (20000 Hz = open, 260 Hz = deep underwater filter sweep)
    @Volatile
    private var targetLpfCutoffHz: Float = 20000f

    // High-pass cut amount for incoming track intro (0.0f = off, 0.85f = crisp high-pass filter)
    @Volatile
    private var targetHighPassMix: Float = 0f

    private var currentBassCut: Float = 0f
    private var currentLpfAlpha: Float = 1f
    private var currentHpfMix: Float = 0f

    // 2-pole cascaded RC filter states per channel (Left = 0, Right = 1)
    // Cascading two 1-pole stages gives a steep 12 dB/octave DJ slope while remaining 100% immune to NaN/instability
    private val lpfStage1 = FloatArray(2)
    private val lpfStage2 = FloatArray(2)
    private val bassStage1 = FloatArray(2)
    private val bassStage2 = FloatArray(2)
    private val hpfStage1 = FloatArray(2)

    /**
     * Updates the DJ EQ filter state for the outgoing track at normalized transition progress [0..1].
     */
    fun updateOutgoingEq(progress: Float) {
        val t = progress.coerceIn(0f, 1f)

        // Phase 1 (0.0 .. 0.42): Outgoing track holds 100% of the bass foundation
        // Phase 2 (0.42 .. 0.54): Rapid DJ Bass Swap! Cuts outgoing bass to 95% kill
        targetBassCut = when {
            t < 0.42f -> 0f
            t < 0.54f -> ((t - 0.42f) / 0.12f) * 0.95f
            else -> 0.95f
        }.coerceIn(0f, 0.96f)

        // Phase 3 (0.38 .. 1.00): Exponential Low-Pass DJ Filter Sweep (15,000 Hz -> 260 Hz)
        // Exponential mapping ensures the sweep moves right through the human midrange (4 kHz -> 300 Hz)
        targetLpfCutoffHz = when {
            t < 0.38f -> 18000f
            else -> {
                val frac = ((t - 0.38f) / 0.62f).coerceIn(0f, 1f)
                // Exponential sweep: 15000 * (260 / 15000)^frac
                (15000.0 * (260.0 / 15000.0).pow(frac.toDouble())).toFloat().coerceIn(260f, 18000f)
            }
        }

        targetHighPassMix = 0f
        isEqActive = true
    }

    /**
     * Updates the DJ EQ filter state for the incoming track at normalized transition progress [0..1].
     */
    fun updateIncomingEq(progress: Float) {
        val t = progress.coerceIn(0f, 1f)

        // Phase 1 (0.0 .. 0.44): Enters with deep 94% bass kill so kicks & sub-bass never clash
        // Phase 2 (0.44 .. 0.54): DJ Bass Swap! Full bass drops in cleanly (94% -> 0% cut)
        targetBassCut = when {
            t < 0.44f -> 0.94f
            t < 0.54f -> 0.94f * (1f - ((t - 0.44f) / 0.10f))
            else -> 0f
        }.coerceIn(0f, 0.96f)

        // Phase 1 (0.0 .. 0.40): Crisp High-Pass filter sweep (removes < 550 Hz mud on entry, then opens up)
        targetHighPassMix = when {
            t < 0.40f -> (1f - (t / 0.40f)) * 0.78f
            else -> 0f
        }.coerceIn(0f, 0.85f)

        targetLpfCutoffHz = 20000f
        isEqActive = (t < 0.58f)
    }

    /**
     * Resets EQ to flat bit-transparent bypass.
     */
    fun resetEq() {
        isEqActive = false
        targetBassCut = 0f
        targetLpfCutoffHz = 20000f
        targetHighPassMix = 0f
        currentBassCut = 0f
        currentLpfAlpha = 1f
        currentHpfMix = 0f
        lpfStage1[0] = 0f; lpfStage1[1] = 0f
        lpfStage2[0] = 0f; lpfStage2[1] = 0f
        bassStage1[0] = 0f; bassStage1[1] = 0f
        bassStage2[0] = 0f; bassStage2[1] = 0f
        hpfStage1[0] = 0f; hpfStage1[1] = 0f
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
        if (!isEqActive && currentBassCut < 0.01f && currentLpfAlpha > 0.98f && currentHpfMix < 0.01f) {
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

        // 1. Sub-bass 2-pole crossover alpha (~240 Hz)
        val wcBass = (2.0 * PI * 240.0).toFloat()
        val bassAlpha = (wcBass / (wcBass + sampleRate)).coerceIn(0.005f, 0.25f)

        // 2. High-pass entry crossover alpha (~520 Hz)
        val wcHpf = (2.0 * PI * 520.0).toFloat()
        val hpfAlpha = (wcHpf / (wcHpf + sampleRate)).coerceIn(0.01f, 0.35f)

        // 3. Target Low-Pass filter alpha from exponential cutoff frequency
        val cutoffHz = targetLpfCutoffHz.coerceIn(240f, 20000f)
        val targetAlpha = if (cutoffHz >= 17500f) {
            1.0f
        } else {
            val wcLpf = (2.0 * PI * cutoffHz).toFloat()
            (wcLpf / (wcLpf + sampleRate)).coerceIn(0.02f, 1.0f)
        }

        val targetCut = targetBassCut
        val targetHpf = targetHighPassMix

        for (frame in 0 until frameCount) {
            currentBassCut += (targetCut - currentBassCut) * 0.005f
            currentLpfAlpha += (targetAlpha - currentLpfAlpha) * 0.005f
            currentHpfMix += (targetHpf - currentHpfMix) * 0.005f

            for (ch in 0 until channels) {
                val rawShort = inputBuffer.short
                val x = rawShort * (1f / 32768f)

                // Stage A: 2-Pole Low-Pass DJ Filter Sweep (12 dB/oct warm roll-off on outgoing track)
                val lp1 = lpfStage1[ch] + currentLpfAlpha * (x - lpfStage1[ch])
                lpfStage1[ch] = lp1
                val lp2 = lpfStage2[ch] + currentLpfAlpha * (lp1 - lpfStage2[ch])
                lpfStage2[ch] = lp2

                // Stage B: 2-Pole Sub-Bass & Kick Isolator (< 240 Hz)
                val b1 = bassStage1[ch] + bassAlpha * (lp2 - bassStage1[ch])
                bassStage1[ch] = b1
                val subBass = bassStage2[ch] + bassAlpha * (b1 - bassStage2[ch])
                bassStage2[ch] = subBass

                // Subtract isolated bass according to DJ Bass Swap curve
                var y = lp2 - (currentBassCut * subBass)

                // Stage C: High-Pass Intro Filter (cleans low-mid mud < 520 Hz on incoming track entry)
                val hpLp = hpfStage1[ch] + hpfAlpha * (y - hpfStage1[ch])
                hpfStage1[ch] = hpLp
                if (currentHpfMix > 0.005f) {
                    val highPassed = y - hpLp
                    y = y * (1f - currentHpfMix) + highPassed * currentHpfMix
                }

                // Slight makeup gain (1.12x) when band-filtered so mid/high elements cut through the mix
                val filterMakeup = 1f + (currentBassCut * 0.12f)
                val outInt = (y * filterMakeup * 32767f).toInt().coerceIn(-32768, 32767)
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
        lpfStage1[0] = 0f; lpfStage1[1] = 0f
        lpfStage2[0] = 0f; lpfStage2[1] = 0f
        bassStage1[0] = 0f; bassStage1[1] = 0f
        bassStage2[0] = 0f; bassStage2[1] = 0f
        hpfStage1[0] = 0f; hpfStage1[1] = 0f
    }

    override fun onReset() {
        resetEq()
    }
}

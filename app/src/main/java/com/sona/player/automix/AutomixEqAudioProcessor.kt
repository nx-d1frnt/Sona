package com.sona.player.automix

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.PI
import kotlin.math.pow

/**
 * Stereo DJ DSP engine for Sona's Automix:
 * Tempo changes are handled separately by ExoPlayer's pitch-preserving Sonic processor.
 * - 2-Pole State-Variable Sub-Bass Isolator (< 240 Hz, 12 dB/oct) for tight DJ Bass Swaps.
 * - Exponential 2-Pole Low-Pass Filter Sweep (15 kHz -> 240 Hz) for warm "underwater" outro tails.
 * - High-Pass Tension Riser Sweep (< 650 Hz) for energy-lift drop transitions.
 * - Stereo Ping-Pong Dub Echo / Delay Wash (tempo-synced feedback loop) for atmospheric transitions.
 */
@OptIn(UnstableApi::class)
class AutomixEqAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var isDspActive: Boolean = false

    // 0.0f = full bass (no cut), 0.96f = deep 2-pole DJ isolator bass kill (-26 dB below 240 Hz)
    @Volatile
    private var targetBassCut: Float = 0f

    // Low-pass cutoff frequency in Hz (20000 Hz = open, 240 Hz = deep underwater filter sweep)
    @Volatile
    private var targetLpfCutoffHz: Float = 20000f

    // High-pass cut amount (0.0f = off, 0.90f = crisp DJ high-pass tension riser)
    @Volatile
    private var targetHighPassMix: Float = 0f

    // Stereo ping-pong dub echo wet mix (0.0f = dry, 0.42f = lush rhythmic echo tail)
    @Volatile
    private var targetEchoMix: Float = 0f

    @Volatile
    private var resetRequested = false

    private data class Normalization(val gain: Float, val rampMs: Long)
    @Volatile private var normalization = Normalization(1f, 0L)
    @Volatile private var targetEchoDelayMs = 320f
    @Volatile private var targetDryMix = 1f
    @Volatile private var targetEchoFeed = 1f
    @Volatile private var echoOutActive = false
    private var currentDryMix = 1f
    private var currentNormalizationGain = 1f
    private var appliedNormalization: Normalization? = null
    private var normalizationFramesRemaining = 0L
    private var normalizationStep = 0f
    private var limiterGain = 1f
    private var echoTailPending = false
    private var generatingTail = false
    private val frameOutput = FloatArray(2)

    fun setNormalizationGain(gain: Float, rampMs: Long = 1500L) {
        val safe = gain.takeIf { it.isFinite() }?.coerceIn(0.1f, 2f) ?: 1f
        if (safe != normalization.gain) normalization = Normalization(safe, rampMs.coerceAtLeast(0L))
    }

    fun configureEcho(delayMs: Float) {
        targetEchoDelayMs = delayMs.takeIf { it.isFinite() }?.coerceIn(100f, 1000f) ?: 320f
    }

    private var currentBassCut: Float = 0f
    private var currentLpfAlpha: Float = 1f
    private var currentHpfMix: Float = 0f
    private var currentEchoMix: Float = 0f

    // 2-pole cascaded RC filter states per channel (Left = 0, Right = 1)
    private val lpfStage1 = FloatArray(2)
    private val lpfStage2 = FloatArray(2)
    private val bassStage1 = FloatArray(2)
    private val bassStage2 = FloatArray(2)
    private val hpfStage1 = FloatArray(2)

    // Sized on the audio thread for up to 1 second of tempo-synced delay at the actual rate.
    private var delayFrames = 16384
    private var delayMask = delayFrames - 1
    private var echoBufferL = FloatArray(delayFrames)
    private var echoBufferR = FloatArray(delayFrames)
    private var echoWritePos: Int = 0
    private var echoLpL: Float = 0f
    private var echoLpR: Float = 0f

    /**
     * Updates the outgoing track's DSP parameters at normalized transition progress [0..1]
     * according to the automatically selected [AutomixTransitionTool].
     */
    fun updateOutgoingEq(
        progress: Float,
        tool: AutomixTransitionTool = AutomixTransitionTool.BASS_SWAP_DROP
    ) {
        val t = progress.coerceIn(0f, 1f)
        targetDryMix = 1f
        targetEchoFeed = 1f
        echoOutActive = tool == AutomixTransitionTool.ECHO_OUT

        when (tool) {
            AutomixTransitionTool.VOCAL_HANDOFF, AutomixTransitionTool.BEAT_CUT -> {
                // Whole-track level envelopes preserve vocal timbre; no stems or deep filter sweep.
                targetBassCut = 0f
                targetHighPassMix = 0f
                targetLpfCutoffHz = 20000f
                targetEchoMix = 0f
            }
            AutomixTransitionTool.ECHO_OUT -> {
                targetBassCut = 0f
                targetHighPassMix = 0f
                targetLpfCutoffHz = 20000f
                targetDryMix = (1f - t / 0.40f).coerceIn(0f, 1f)
                targetEchoFeed = targetDryMix
                targetEchoMix = when {
                    t < 0.20f -> t / 0.20f * 0.60f
                    t < 0.55f -> 0.60f
                    else -> 0.60f * ((1f - t) / 0.45f).coerceIn(0f, 1f).pow(2)
                }
            }
            AutomixTransitionTool.HIGH_PASS_RISER_SLAM -> {
                // Sweeps outgoing track upward into an airy High-Pass Riser + Stereo Echo before the Beat Drop!
                targetBassCut = (t / 0.42f).coerceIn(0f, 0.96f)
                targetHighPassMix = when {
                    t < 0.15f -> 0f
                    else -> ((t - 0.15f) / 0.65f * 0.88f).coerceIn(0f, 0.88f)
                }
                targetLpfCutoffHz = if (t < 0.65f) 18000f else {
                    val frac = ((t - 0.65f) / 0.35f).coerceIn(0f, 1f)
                    (14000.0 * (800.0 / 14000.0).pow(frac.toDouble())).toFloat()
                }
                targetEchoMix = when {
                    t < 0.25f -> 0f
                    t < 0.65f -> ((t - 0.25f) / 0.40f) * 0.36f
                    else -> 0.36f
                }
            }

            AutomixTransitionTool.FILTER_ECHO_WASH -> {
                // Deep Resonant Low-Pass Sweep + Lush Stereo Ping-Pong Dub Echo tail
                targetBassCut = when {
                    t < 0.35f -> 0f
                    t < 0.56f -> ((t - 0.35f) / 0.21f) * 0.94f
                    else -> 0.94f
                }
                targetLpfCutoffHz = when {
                    t < 0.25f -> 18000f
                    else -> {
                        val frac = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                        (14000.0 * (240.0 / 14000.0).pow(frac.toDouble())).toFloat()
                    }
                }
                targetHighPassMix = 0f
                targetEchoMix = when {
                    t < 0.20f -> 0f
                    t < 0.55f -> ((t - 0.20f) / 0.35f) * 0.42f
                    else -> 0.42f
                }
            }

            AutomixTransitionTool.HALF_DOUBLE_TIME_BRIDGE -> {
                // Mid-focused band isolator + rhythmic stereo delay to lock 0.5x/2x grooves
                targetBassCut = when {
                    t < 0.40f -> 0f
                    t < 0.54f -> ((t - 0.40f) / 0.14f) * 0.94f
                    else -> 0.94f
                }
                targetHighPassMix = if (t < 0.35f) 0f else ((t - 0.35f) / 0.65f * 0.55f).coerceIn(0f, 0.55f)
                targetLpfCutoffHz = if (t < 0.42f) 18000f else {
                    val frac = ((t - 0.42f) / 0.58f).coerceIn(0f, 1f)
                    (14000.0 * (380.0 / 14000.0).pow(frac.toDouble())).toFloat()
                }
                targetEchoMix = if (t < 0.30f) 0f else 0.28f
            }

            AutomixTransitionTool.SILKY_WARM_ISOLATOR -> {
                // Long, velvety 3-stage isolator morph with gentle warm low-pass roll-off
                targetBassCut = when {
                    t < 0.32f -> 0f
                    t < 0.66f -> ((t - 0.32f) / 0.34f) * 0.90f
                    else -> 0.90f
                }
                targetLpfCutoffHz = when {
                    t < 0.25f -> 18000f
                    else -> {
                        val frac = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
                        (12000.0 * (360.0 / 12000.0).pow(frac.toDouble())).toFloat()
                    }
                }
                targetHighPassMix = 0f
                targetEchoMix = 0.10f * t
            }

            AutomixTransitionTool.BASS_SWAP_DROP -> {
                // Classic Club Bass Swap at t=0.42..0.54 + Exponential LPF Sweep (15kHz -> 260Hz)
                targetBassCut = when {
                    t < 0.42f -> 0f
                    t < 0.54f -> ((t - 0.42f) / 0.12f) * 0.95f
                    else -> 0.95f
                }.coerceIn(0f, 0.96f)

                targetLpfCutoffHz = when {
                    t < 0.38f -> 18000f
                    else -> {
                        val frac = ((t - 0.38f) / 0.62f).coerceIn(0f, 1f)
                        (15000.0 * (260.0 / 15000.0).pow(frac.toDouble())).toFloat().coerceIn(260f, 18000f)
                    }
                }
                targetHighPassMix = 0f
                targetEchoMix = if (t > 0.45f) 0.16f else 0f
            }
        }

        isDspActive = true
    }

    /**
     * Updates the incoming track's DSP parameters at normalized transition progress [0..1]
     * according to the automatically selected [AutomixTransitionTool].
     */
    fun updateIncomingEq(
        progress: Float,
        tool: AutomixTransitionTool = AutomixTransitionTool.BASS_SWAP_DROP
    ) {
        val t = progress.coerceIn(0f, 1f)
        targetEchoMix = 0f
        targetDryMix = 1f
        targetEchoFeed = 1f
        echoOutActive = false

        when (tool) {
            AutomixTransitionTool.VOCAL_HANDOFF, AutomixTransitionTool.BEAT_CUT,
            AutomixTransitionTool.ECHO_OUT -> {
                targetBassCut = 0f
                targetHighPassMix = 0f
                targetLpfCutoffHz = 20000f
            }
            AutomixTransitionTool.HIGH_PASS_RISER_SLAM -> {
                // Holds bass back until t=0.46, then SLAMS 100% full sub-bass & kick in over 0.06t!
                targetBassCut = when {
                    t < 0.46f -> 0.96f
                    t < 0.52f -> 0.96f * (1f - ((t - 0.46f) / 0.06f))
                    else -> 0f
                }
                targetHighPassMix = when {
                    t < 0.44f -> (1f - (t / 0.44f)) * 0.82f
                    else -> 0f
                }
                targetLpfCutoffHz = 20000f
            }

            AutomixTransitionTool.SILKY_WARM_ISOLATOR -> {
                // Gradual, warm bass & mid entry
                targetBassCut = when {
                    t < 0.32f -> 0.90f
                    t < 0.66f -> 0.90f * (1f - ((t - 0.32f) / 0.34f))
                    else -> 0f
                }
                targetHighPassMix = when {
                    t < 0.30f -> (1f - (t / 0.30f)) * 0.55f
                    else -> 0f
                }
                targetLpfCutoffHz = if (t < 0.25f) 4500f + (t / 0.25f) * 14000f else 20000f
            }

            else -> {
                // Phase 1 (0.0 .. 0.44): Enters with 94% bass kill + High-Pass so kicks never clash
                // Phase 2 (0.44 .. 0.54): DJ Bass Swap! Full bass drops in cleanly
                targetBassCut = when {
                    t < 0.44f -> 0.94f
                    t < 0.54f -> 0.94f * (1f - ((t - 0.44f) / 0.10f))
                    else -> 0f
                }.coerceIn(0f, 0.96f)

                targetHighPassMix = when {
                    t < 0.40f -> (1f - (t / 0.40f)) * 0.78f
                    else -> 0f
                }.coerceIn(0f, 0.85f)

                targetLpfCutoffHz = 20000f
            }
        }

        isDspActive = t < 0.98f
    }

    /**
     * Resets transition effects while keeping persistent per-track normalization.
     */
    fun resetEq() {
        isDspActive = false
        targetBassCut = 0f
        targetLpfCutoffHz = 20000f
        targetHighPassMix = 0f
        targetEchoMix = 0f
        targetDryMix = 1f
        targetEchoFeed = 1f
        echoOutActive = false
        // Normalization is a persistent track level, independent of transition EQ reset.
        // Mutable filter/delay history belongs to the audio thread.
        resetRequested = true
    }

    private fun resetSignalState() {
        currentBassCut = 0f
        currentLpfAlpha = 1f
        currentHpfMix = 0f
        currentEchoMix = 0f
        currentDryMix = 1f
        limiterGain = 1f
        echoTailPending = false
        lpfStage1[0] = 0f; lpfStage1[1] = 0f
        lpfStage2[0] = 0f; lpfStage2[1] = 0f
        bassStage1[0] = 0f; bassStage1[1] = 0f
        bassStage2[0] = 0f; bassStage2[1] = 0f
        hpfStage1[0] = 0f; hpfStage1[1] = 0f
        echoLpL = 0f; echoLpR = 0f
        echoBufferL.fill(0f)
        echoBufferR.fill(0f)
        echoWritePos = 0
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
        if (resetRequested) {
            resetRequested = false
            resetSignalState()
        }
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Fast bit-perfect bypass when not in an active transition
        if (!isDspActive &&
            currentBassCut < 0.005f &&
            currentLpfAlpha > 0.985f &&
            currentHpfMix < 0.005f &&
            currentEchoMix < 0.005f &&
            abs(normalization.gain - 1f) < 0.0001f && abs(currentNormalizationGain - 1f) < 0.0001f &&
            limiterGain > 0.9999f && targetDryMix > 0.9999f
        ) {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channels = inputAudioFormat.channelCount.coerceIn(1, 2)
        val frameSize = 2 * channels
        val frameCount = remaining / frameSize
        if (frameCount <= 0) {
            inputBuffer.position(inputBuffer.limit())
            return
        }

        val outputBuffer = replaceOutputBuffer(frameCount * frameSize).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())

        val sampleRate = inputAudioFormat.sampleRate.toFloat().coerceAtLeast(8000f)

        // 1. Sub-bass 2-pole crossover alpha (~240 Hz)
        val wcBass = (2.0 * PI * 240.0).toFloat()
        val bassAlpha = (wcBass / (wcBass + sampleRate)).coerceIn(0.005f, 0.25f)

        // 2. High-pass crossover alpha (~560 Hz)
        val wcHpf = (2.0 * PI * 560.0).toFloat()
        val hpfAlpha = (wcHpf / (wcHpf + sampleRate)).coerceIn(0.01f, 0.35f)

        // 3. Target Low-Pass filter alpha from exponential cutoff frequency
        val cutoffHz = targetLpfCutoffHz.coerceIn(240f, 20000f)
        val targetAlpha = if (cutoffHz >= 17500f) {
            1.0f
        } else {
            val wcLpf = (2.0 * PI * cutoffHz).toFloat()
            (wcLpf / (wcLpf + sampleRate)).coerceIn(0.02f, 1.0f)
        }

        val delaySamples = (sampleRate * targetEchoDelayMs / 1000f).toInt().coerceIn(1, delayFrames - 1)
        val level = normalization
        if (level !== appliedNormalization) {
            appliedNormalization = level
            normalizationFramesRemaining = (sampleRate * level.rampMs / 1000.0).toLong()
            normalizationStep = if (normalizationFramesRemaining > 0L)
                (level.gain - currentNormalizationGain) / normalizationFramesRemaining else 0f
        }
        val limiterRelease = (1.0 - kotlin.math.exp(-1.0 / (sampleRate * 0.12))).toFloat()
        val dryTarget = targetDryMix
        val feedTarget = targetEchoFeed

        val targetCut = targetBassCut
        val targetHpf = targetHighPassMix
        val targetEcho = targetEchoMix

        for (frame in 0 until frameCount) {
            currentBassCut += (targetCut - currentBassCut) * 0.004f
            currentLpfAlpha += (targetAlpha - currentLpfAlpha) * 0.004f
            currentHpfMix += (targetHpf - currentHpfMix) * 0.004f
            currentEchoMix += (targetEcho - currentEchoMix) * 0.004f
            currentDryMix += (dryTarget - currentDryMix) * 0.004f
            if (normalizationFramesRemaining > 0L) {
                currentNormalizationGain += normalizationStep
                normalizationFramesRemaining--
                if (normalizationFramesRemaining == 0L) currentNormalizationGain = level.gain
            } else currentNormalizationGain = level.gain

            // Read delayed stereo samples for Ping-Pong Dub Echo
            val readIdx = (echoWritePos - delaySamples) and delayMask
            val delayedL = echoBufferL[readIdx]
            val delayedR = echoBufferR[readIdx]
            echoLpL += 0.28f * (delayedL - echoLpL)
            echoLpR += 0.28f * (delayedR - echoLpR)

            var processedL = 0f
            var processedR = 0f
            val tailFade = if (generatingTail) {
                val remaining = 1f - frame.toFloat() / frameCount
                remaining * remaining
            } else 1f

            for (ch in 0 until channels) {
                val x = inputBuffer.short * (1f / 32768f)

                // Stage A: 2-Pole Low-Pass DJ Filter Sweep (12 dB/oct warm roll-off)
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

                // Stage C: High-Pass Filter (for incoming entry or outgoing Tension Riser)
                val hpLp = hpfStage1[ch] + hpfAlpha * (y - hpfStage1[ch])
                hpfStage1[ch] = hpLp
                if (currentHpfMix > 0.005f) {
                    val highPassed = y - hpLp
                    y = y * (1f - currentHpfMix) + highPassed * currentHpfMix
                }

                // Stage D: Add Stereo Ping-Pong Dub Echo Wash
                val echoSignal = if (ch == 0) echoLpL else echoLpR
                val withEcho = y * currentDryMix + currentEchoMix * echoSignal * tailFade

                val filterMakeup = 1f + (currentBassCut * 0.12f)
                val finalSample = withEcho * filterMakeup * currentNormalizationGain

                if (ch == 0) processedL = y else processedR = y

                frameOutput[ch] = finalSample
            }

            // Instant linked stereo attack + slow release catches unmeasured peaks without channel imbalance.
            var peak = 0f
            for (ch in 0 until channels) peak = max(peak, abs(frameOutput[ch]))
            val allowed = if (peak > 0.95f) 0.95f / peak else 1f
            limiterGain = if (allowed < limiterGain) allowed else if (allowed - limiterGain < 0.001f) allowed
                else limiterGain + (allowed - limiterGain) * limiterRelease
            for (ch in 0 until channels) {
                outputBuffer.putShort((frameOutput[ch] * limiterGain * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }

            // Write into cross-stereo ping-pong delay line with warm 38% feedback
            if (channels == 1) processedR = processedL
            echoBufferL[echoWritePos] = (processedL * feedTarget + echoLpR * 0.38f).coerceIn(-1.0f, 1.0f)
            echoBufferR[echoWritePos] = (processedR * feedTarget + echoLpL * 0.38f).coerceIn(-1.0f, 1.0f)
            echoWritePos = (echoWritePos + 1) and delayMask

        }

        // Consume any trailing odd bytes so DefaultAudioSink never stalls
        if (inputBuffer.hasRemaining()) {
            inputBuffer.position(inputBuffer.limit())
        }

        outputBuffer.flip()
    }

    override fun onQueueEndOfStream() {
        echoTailPending = echoOutActive && currentEchoMix > 0.01f
    }

    override fun getOutput(): ByteBuffer {
        // Preserve the last ordinary buffer before generating the finite zero-input echo tail.
        if (!hasPendingOutput() && echoTailPending) {
            echoTailPending = false
            if (echoOutActive) {
                val rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
                val frames = (rate * (targetEchoDelayMs * 4).coerceAtMost(2000f) / 1000).toInt()
                val silence = ByteBuffer.allocateDirect(frames * inputAudioFormat.channelCount * 2)
                generatingTail = true
                try { queueInput(silence) } finally { generatingTail = false }
            }
        }
        return super.getOutput()
    }

    override fun isEnded(): Boolean = !echoTailPending && super.isEnded()

    override fun onFlush() {
        val rate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        val capacity = Integer.highestOneBit((rate * 1.1).toInt().coerceAtLeast(1)) shl 1
        if (capacity != delayFrames) {
            delayFrames = capacity
            delayMask = capacity - 1
            echoBufferL = FloatArray(capacity)
            echoBufferR = FloatArray(capacity)
        }
        resetSignalState()
        appliedNormalization = null
    }

    override fun onReset() {
        resetEq()
        resetSignalState()
        resetRequested = false
        // Engine-supplied track gain survives decoder resets and sample-rate changes.
        currentNormalizationGain = normalization.gain
        appliedNormalization = null
    }
}

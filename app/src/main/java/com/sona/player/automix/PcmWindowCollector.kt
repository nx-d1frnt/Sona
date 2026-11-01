package com.sona.player.automix

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Timestamp-clipped stereo/multichannel PCM -> mono 16 kHz. Keeps resampling phase across buffers. */
internal class PcmWindowCollector(private val startMs: Long, private val durationMs: Long) {
    private val output = FloatArray((durationMs * 16).toInt())
    private var size = 0
    private var sourceRate = 0
    private var inputFrame = 0L
    private var nextOutputFrame = 0.0
    private var firstTimeUs: Long? = null
    private var lastTimeUs: Long? = null
    private var previous = 0f
    private var lowPass = 0f
    private var lowPass2 = 0f

    fun append(pcm: ByteBuffer, presentationTimeUs: Long, sampleRate: Int, channels: Int, floatPcm: Boolean) {
        require(sampleRate > 0 && channels in 1..8)
        if (sourceRate == 0) sourceRate = sampleRate
        require(sourceRate == sampleRate) { "Sample rate changed inside analysis window" }
        val bytesPerSample = if (floatPcm) 4 else 2
        val frameBytes = bytesPerSample * channels
        pcm.order(ByteOrder.LITTLE_ENDIAN)
        val frames = pcm.remaining() / frameBytes
        val origin = pcm.position()
        val startUs = startMs * 1000L
        val endUs = (startMs + durationMs) * 1000L
        // A two-pole low-pass limits aliasing when reducing high sample rates.
        val alpha = (1.0 - kotlin.math.exp(-2.0 * Math.PI * 6000.0 / sampleRate)).toFloat()
        for (frame in 0 until frames) {
            val timeUs = presentationTimeUs + frame * 1_000_000L / sampleRate
            if (timeUs < startUs || timeUs >= endUs || size == output.size) continue
            if (firstTimeUs == null) firstTimeUs = timeUs
            lastTimeUs?.let { require(timeUs - it <= maxOf(2000L, 2_000_000L / sampleRate)) {
                "Discontinuous PCM timestamps"
            } }
            lastTimeUs = timeUs
            var mono = 0f
            for (channel in 0 until channels) {
                val index = origin + frame * frameBytes + channel * bytesPerSample
                val value = if (floatPcm) pcm.getFloat(index) else pcm.getShort(index) / 32768f
                mono += if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
            }
            mono /= channels
            lowPass += alpha * (mono - lowPass)
            lowPass2 += alpha * (lowPass - lowPass2)
            val filtered = if (sampleRate > 16_000) lowPass2 else mono
            if (inputFrame == 0L) previous = filtered
            while (nextOutputFrame <= inputFrame && size < output.size) {
                val fraction = (nextOutputFrame - (inputFrame - 1)).toFloat().coerceIn(0f, 1f)
                output[size++] = previous + fraction * (filtered - previous)
                nextOutputFrame += sampleRate / 16_000.0
            }
            previous = filtered
            inputFrame++
        }
        pcm.position(pcm.limit())
    }

    fun window() = AudioWindow(firstTimeUs?.div(1000L) ?: startMs, output.copyOf(size))
}

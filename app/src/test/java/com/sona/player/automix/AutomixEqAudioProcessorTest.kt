package com.sona.player.automix

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class AutomixEqAudioProcessorTest {
    private fun processor(channels: Int = 2) = AutomixEqAudioProcessor().apply {
        configure(AudioProcessor.AudioFormat(48_000, channels, C.ENCODING_PCM_16BIT))
        flush()
    }

    private fun process(processor: AutomixEqAudioProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        assertEquals(input.limit(), input.position())
        val output = processor.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(output.remaining()).also { output.get(it) }
    }

    @Test fun bypassPreservesPcmExactly() {
        val samples = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE, -1, 0, 1, 1234)
        assertArrayEquals(samples, process(processor(), samples))
    }

    @Test fun everyToolPreservesFrameCountForMonoAndStereo() {
        for (channels in 1..2) for (tool in AutomixTransitionTool.entries) {
            val processor = processor(channels)
            val samples = ShortArray(4096 * channels) { (sin(it * 0.1) * 12000).toInt().toShort() }
            processor.updateOutgoingEq(0.6f, tool)
            assertEquals(samples.size, process(processor, samples).size)
            processor.updateIncomingEq(0.4f, tool)
            assertEquals(samples.size, process(processor, samples).size)
        }
    }

    @Test fun outputDoesNotDependOnInputBufferBoundaries() {
        val samples = ShortArray(8192) { (sin(it * 0.13) * 12000).toInt().toShort() }
        val whole = processor().apply { updateOutgoingEq(0.7f, AutomixTransitionTool.FILTER_ECHO_WASH) }
        val chunked = processor().apply { updateOutgoingEq(0.7f, AutomixTransitionTool.FILTER_ECHO_WASH) }
        val expected = process(whole, samples)
        val actual = samples.toList().chunked(256).flatMap {
            process(chunked, it.toShortArray()).toList()
        }.toShortArray()
        assertArrayEquals(expected, actual)
    }

    @Test fun eqDoesNotChangeToneFrequency() {
        val processor = processor(1).apply { updateIncomingEq(0.8f) }
        val samples = ShortArray(48_000) { (sin(2 * PI * 440 * it / 48_000) * 10000).toInt().toShort() }
        val output = process(processor, samples)
        val crossings = (4801 until output.size).count { output[it - 1] <= 0 && output[it] > 0 }
        assertEquals(440.0, crossings / 0.9, 2.0)
    }

    @Test fun sonicChangesDurationWithoutChangingPitch() {
        for (speed in listOf(0.94f, 1.06f)) {
            val eq = processor(1).apply { updateIncomingEq(0.8f) }
            val samples = ShortArray(48_000) {
                (sin(2 * PI * 440 * it / 48_000) * 10000).toInt().toShort()
            }
            val filtered = process(eq, samples)
            val sonic = SonicAudioProcessor().apply {
                setSpeed(speed)
                setPitch(1f)
                configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
                flush()
            }
            val input = ByteBuffer.allocateDirect(filtered.size * 2).order(ByteOrder.nativeOrder())
            filtered.forEach { input.putShort(it) }
            input.flip()
            sonic.queueInput(input)
            val outputSamples = mutableListOf<Short>()
            fun drain() {
                val output = sonic.output.order(ByteOrder.nativeOrder()).asShortBuffer()
                while (output.hasRemaining()) outputSamples += output.get()
            }
            drain()
            sonic.queueEndOfStream()
            drain()
            assertEquals(48_000.0 / speed, outputSamples.size.toDouble(), 240.0)
            val start = 4800
            val end = outputSamples.size - 4800
            val crossings = (start + 1 until end).count {
                outputSamples[it - 1] <= 0 && outputSamples[it] > 0
            }
            val measuredHz = crossings * 48_000.0 / (end - start)
            assertEquals(440.0, measuredHz, 2.0)
        }
    }

    @Test fun resetRemovesPreviousTracksEcho() {
        val processor = processor().apply { updateOutgoingEq(0.6f, AutomixTransitionTool.FILTER_ECHO_WASH) }
        process(processor, ShortArray(8192) { 12000 })
        processor.resetEq()
        processor.updateOutgoingEq(0.6f, AutomixTransitionTool.FILTER_ECHO_WASH)
        val silence = ShortArray(48_000)
        assertArrayEquals(silence, process(processor, silence))
    }

    @Test fun flushRemovesPreviousTracksEcho() {
        val processor = processor().apply { updateOutgoingEq(0.6f, AutomixTransitionTool.FILTER_ECHO_WASH) }
        process(processor, ShortArray(8192) { 12000 })
        processor.flush()
        val silence = ShortArray(48_000)
        assertArrayEquals(silence, process(processor, silence))
    }
}

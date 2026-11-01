package com.sona.player.automix

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmWindowCollectorTest {
    private fun pcm(samples: ShortArray) = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        samples.forEach { putShort(it) }; flip()
    }

    @Test fun clipsPrerollUsingTimestamps() {
        val collector = PcmWindowCollector(1000, 100)
        collector.append(pcm(ShortArray(4000) { 16000 }), 900_000L, 16_000, 1, false)
        val window = collector.window()
        assertEquals(1000L, window.startMs)
        assertEquals(1600, window.samples.size)
        assertEquals(16000 / 32768f, window.samples.first(), 0.00001f)
    }

    @Test fun averagesBothStereoChannelsInsteadOfOnlySamplingLeft() {
        val stereo = ShortArray(3200) { if (it % 2 == 0) 20000 else -20000 }
        val collector = PcmWindowCollector(0, 100)
        collector.append(pcm(stereo), 0, 16_000, 2, false)
        assertTrue(collector.window().samples.all { it == 0f })
    }

    @Test fun resamplingIsIndependentOfDecoderBufferBoundaries() {
        val input = ShortArray(4410) { (it % 1000).toShort() }
        val whole = PcmWindowCollector(0, 100)
        whole.append(pcm(input), 0, 44_100, 1, false)
        val chunks = PcmWindowCollector(0, 100)
        chunks.append(pcm(input.copyOfRange(0, 2205)), 0, 44_100, 1, false)
        chunks.append(pcm(input.copyOfRange(2205, 4410)), 50_000L, 44_100, 1, false)
        assertEquals(1600, whole.window().samples.size)
        assertArrayEquals(whole.window().samples, chunks.window().samples, 0f)
    }

    @Test fun supportsFloatPcmAndRetainsActualWindowStart() {
        val input = ByteBuffer.allocate(1600 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1600) { input.putFloat(0.25f) }; input.flip()
        val collector = PcmWindowCollector(1000, 200)
        collector.append(input, 1_025_000L, 16_000, 1, true)
        assertEquals(1025L, collector.window().startMs)
        assertEquals(0.25f, collector.window().samples.first(), 0f)
    }
    @Test fun shortDecoderGapPreservesTimelineInsteadOfDiscardingWindow() {
        val collector = PcmWindowCollector(0, 200)
        collector.append(pcm(ShortArray(800) { 16000 }), 0, 16_000, 1, false)
        collector.append(pcm(ShortArray(800) { 16000 }), 75_000, 16_000, 1, false)
        val samples = collector.window().samples
        assertEquals(2000, samples.size)
        assertTrue(samples.sliceArray(800 until 1200).all { it == 0f })
        assertTrue(samples[1200] > 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun largeTimestampGapsRemainRejected() {
        val collector = PcmWindowCollector(0, 1000)
        collector.append(pcm(ShortArray(800)), 0, 16_000, 1, false)
        collector.append(pcm(ShortArray(800)), 500_000, 16_000, 1, false)
    }

}

package com.sona.player.automix

import android.content.Context
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Music-specific voice/no-voice evidence from the ISC-licensed MTT_musicnn tagger. */
internal class MusicVocalDetector(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(SAMPLE_COUNT * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(50 * 4).order(ByteOrder.nativeOrder())
    private val inputFloats = input.asFloatBuffer()
    private val outputFloats = output.asFloatBuffer()
    private val scores = FloatArray(50)
    var lastInferenceCount: Int = 0
        private set

    init {
        val model = context.assets.openFd("models/musicnn-vocals.tflite").use { asset ->
            FileInputStream(asset.fileDescriptor).use {
                it.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().apply {
            setNumThreads(minOf(4, Runtime.getRuntime().availableProcessors()))
            // Measured faster than XNNPACK for this particular graph on both test phones.
            setUseXNNPACK(false)
        })
        require(interpreter.getInputTensor(0).dataType() == DataType.FLOAT32)
        require(interpreter.getInputTensor(0).numElements() == SAMPLE_COUNT)
        require(interpreter.getOutputTensor(0).dataType() == DataType.FLOAT32)
        require(interpreter.getOutputTensor(0).numElements() == 50)
    }

    // Called under the owning YAMNet detector's inference mutex.
    suspend fun analyze(window: AudioWindow): List<VocalSection> {
        if (window.samples.isEmpty()) return emptyList()
        val lastOffset = (window.samples.size - SAMPLE_COUNT).coerceAtLeast(0)
        val offsets = (0..lastOffset step HOP).toMutableList()
        if (offsets.last() != lastOffset) offsets += lastOffset
        lastInferenceCount = 0
        val activity = MusicVocalSampling.scores(offsets.size) { index ->
            currentCoroutineContext().ensureActive()
            val offset = offsets[index]
            val count = minOf(SAMPLE_COUNT, window.samples.size - offset)
            var squareSum = 0.0
            for (i in offset until offset + count) squareSum += window.samples[i].toDouble() * window.samples[i]
            // Skip inference too: learned tag biases are not voice in digital silence.
            if (squareSum / count < 1e-8) return@scores 0f
            inputFloats.clear()
            inputFloats.put(window.samples, offset, count)
            repeat(SAMPLE_COUNT - count) { inputFloats.put(0f) }
            input.rewind()
            output.rewind()
            interpreter.run(input, output)
            lastInferenceCount++
            outputFloats.rewind()
            outputFloats.get(scores)
            activityScore(scores)
        }
        return offsets.mapIndexed { index, offset ->
            val endSample = offsets.getOrNull(index + 1) ?: window.samples.size
            VocalSection(window.startMs + offset * 1000L / 16_000,
                window.startMs + endSample * 1000L / 16_000, activity[index])
        }
    }

    override fun close() = interpreter.close()

    companion object {
        private const val SAMPLE_COUNT = 48_000
        private const val HOP = 24_000
        // MTT tag indices; see musicnn-labels.txt and tools/export_musicnn.py.
        private val VOICE = intArrayOf(13, 19, 20, 27, 35, 36, 39, 40, 48, 49)
        private val NO_VOICE = intArrayOf(21, 28, 44)

        internal fun activityScore(scores: FloatArray): Float {
            fun maximum(indices: IntArray) = indices.maxOf {
                scores.getOrNull(it)?.takeIf { value -> value.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            }
            val voice = maximum(VOICE)
            val instrumental = maximum(NO_VOICE)
            // These are independent sparse tags, not mutually exclusive probabilities.
            // Compare both kinds of evidence; the prior keeps tiny scores inconclusive.
            return voice / (voice + instrumental + 0.01f)
        }
    }
}

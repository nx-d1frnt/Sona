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

/** Offline AudioSet singing/speech/rap detection. Scores are hints, not source separation. */
internal class YamnetVocalDetector(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val sampleCount: Int
    private val scoreCount: Int

    init {
        val model = context.assets.openFd("models/yamnet.tflite").use { asset ->
            FileInputStream(asset.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(1))
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        require(inputTensor.dataType() == DataType.FLOAT32 && outputTensor.dataType() == DataType.FLOAT32)
        sampleCount = inputTensor.numElements()
        scoreCount = outputTensor.numElements()
        require(sampleCount == 15_600 && scoreCount == 521 && interpreter.outputTensorCount == 1)
        input = ByteBuffer.allocateDirect(sampleCount * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(scoreCount * 4).order(ByteOrder.nativeOrder())
    }

    suspend fun analyze(window: AudioWindow): List<VocalSection> {
        require(window.sampleRate == 16_000)
        val result = mutableListOf<VocalSection>()
        // Non-overlapping 0.975s windows keep lookahead inexpensive; unknown tails remain unknown.
        for (offset in 0..(window.samples.size - sampleCount) step sampleCount) {
            currentCoroutineContext().ensureActive()
            input.clear()
            for (i in offset until offset + sampleCount) input.putFloat(window.samples[i])
            input.rewind()
            output.clear()
            interpreter.run(input, output)
            output.rewind()
            val scores = FloatArray(scoreCount) { output.float }
            result += VocalSection(window.startMs + offset * 1000L / 16_000,
                window.startMs + (offset + sampleCount) * 1000L / 16_000, vocalScore(scores))
        }
        return result
    }

    override fun close() = interpreter.close()

    companion object {
        // Fixed AudioSet indices in the bundled YAMNet class map; see assets/models/README.md.
        internal fun vocalScore(scores: FloatArray): Float {
            val indices = intArrayOf(0, 1, 2, 3, 5, 12, 24, 25, 26, 27, 28, 29, 30, 31, 32, 249, 250)
            return indices.maxOf { scores.getOrElse(it) { 0f }.takeIf(Float::isFinite) ?: 0f }.coerceIn(0f, 1f)
        }
    }
}

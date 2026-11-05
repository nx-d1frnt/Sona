package com.sona.player.automix

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Offline YAMNet speech + musicnn singing/rap detection. Scores are hints, not source separation. */
internal class YamnetVocalDetector(context: Context) : Closeable {
    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val inFloat: FloatBuffer
    private val outFloat: FloatBuffer
    private val scoresArray: FloatArray
    private val sampleCount: Int
    private val scoreCount: Int
    private val mutex = Mutex()
    private val musicDetector: MusicVocalDetector

    init {
        val model = context.assets.openFd("models/yamnet.tflite").use { asset ->
            FileInputStream(asset.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            }
        }
        val options = Interpreter.Options().apply {
            val cores = Runtime.getRuntime().availableProcessors()
            setNumThreads(minOf(4, maxOf(2, cores / 2)))
        }
        interpreter = Interpreter(model, options)
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)
        require(inputTensor.dataType() == DataType.FLOAT32 && outputTensor.dataType() == DataType.FLOAT32)
        sampleCount = inputTensor.numElements()
        scoreCount = outputTensor.numElements()
        require(sampleCount == 15_600 && scoreCount == 521 && interpreter.outputTensorCount == 1)
        input = ByteBuffer.allocateDirect(sampleCount * 4).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(scoreCount * 4).order(ByteOrder.nativeOrder())
        inFloat = input.asFloatBuffer()
        outFloat = output.asFloatBuffer()
        scoresArray = FloatArray(scoreCount)
        musicDetector = try {
            MusicVocalDetector(context)
        } catch (failure: Throwable) {
            interpreter.close()
            throw failure
        }
    }

    suspend fun analyze(window: AudioWindow): List<VocalSection> {
        require(window.sampleRate == 16_000)
        if (window.samples.size < sampleCount) return emptyList()
        val result = mutableListOf<VocalSection>()
        // 50% overlap (0.4875s hop) gives fine ~488ms resolution for VOCAL_HANDOFF pauses
        // with zero blind spots between 0.975s YAMNet windows.
        val stride = sampleCount / 2
        var musicMs = 0L
        var yamnetMs = 0L
        var musicCalls = 0
        mutex.withLock {
            val started = android.os.SystemClock.elapsedRealtime()
            val music = musicDetector.analyze(window)
            musicMs = android.os.SystemClock.elapsedRealtime() - started
            musicCalls = musicDetector.lastInferenceCount
            val beforeYamnet = android.os.SystemClock.elapsedRealtime()
            var offset = 0
            while (offset + sampleCount <= window.samples.size) {
                currentCoroutineContext().ensureActive()
                inFloat.clear()
                inFloat.put(window.samples, offset, sampleCount)
                input.position(0)
                output.position(0)
                interpreter.run(input, output)
                outFloat.position(0)
                outFloat.get(scoresArray)
                val isLast = offset + stride + sampleCount > window.samples.size
                val sectionEndSample = if (isLast) window.samples.size else offset + stride
                val startMs = window.startMs + offset * 1000L / 16_000
                val endMs = window.startMs + sectionEndSample * 1000L / 16_000
                result += VocalSection(
                    startMs,
                    endMs,
                    maxOf(vocalScore(scoresArray), music.filter { it.startMs < endMs && it.endMs > startMs }
                        .maxOfOrNull { it.score } ?: 0f)
                )
                offset += stride
            }
            yamnetMs = android.os.SystemClock.elapsedRealtime() - beforeYamnet
        }
        Log.d("SonaAnalysis", "YAMNet+musicnn startMs=${window.startMs} frames=${result.size} " +
            "musicCalls=$musicCalls musicMs=$musicMs yamnetMs=$yamnetMs " +
            "meanScore=${result.map { it.score }.average()} maxScore=${result.maxOf { it.score }}")
        return result
    }

    override fun close() {
        try { interpreter.close() } finally { musicDetector.close() }
    }

    companion object {
        // Fixed AudioSet indices in the bundled YAMNet class map; see assets/models/README.md.
        private val VOCAL_INDICES = intArrayOf(0, 1, 2, 3, 5, 12, 24, 25, 26, 27, 28, 29, 30, 31, 32, 249, 250)

        internal fun vocalScore(scores: FloatArray): Float {
            var maxScore = 0f
            for (idx in VOCAL_INDICES) {
                if (idx in scores.indices) {
                    val score = scores[idx]
                    if (score.isFinite() && score > maxScore) {
                        maxScore = score
                    }
                }
            }
            return maxScore.coerceIn(0f, 1f)
        }
    }
}

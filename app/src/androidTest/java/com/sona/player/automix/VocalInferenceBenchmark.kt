package com.sona.player.automix

import android.content.Context
import android.content.ContentUris
import android.os.SystemClock
import android.provider.MediaStore
import com.sona.player.data.model.Song
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import org.tensorflow.lite.Interpreter

/** Compares CPU kernels on the same decoded audio, without changing the analysis cache. */
internal object VocalInferenceBenchmark {
    suspend fun run(context: Context, songId: Long): String {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
        val song = Song(songId, "Benchmark", "", "", 200_000, uri)
        val window = AudioWindowDecoder(context).decode(song, 20_000, 4000)
        check(window.samples.size >= 48_000) { "Not enough benchmark PCM: ${window.samples.size}" }
        val report = StringBuilder()
        for ((name, count) in listOf("musicnn-vocals.tflite" to 48_000, "yamnet.tflite" to 15_600)) {
            val model = context.assets.openFd("models/$name").use { asset ->
                FileInputStream(asset.fileDescriptor).use {
                    it.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
                }
            }
            var reference: FloatArray? = null
            for ((mode, threads) in listOf("cpu" to 2, "cpu" to 4, "nnapi" to 4)) {
                Interpreter(model, Interpreter.Options().apply {
                    setNumThreads(threads)
                    setUseXNNPACK(false)
                    setUseNNAPI(mode == "nnapi")
                }).use { interpreter ->
                    val input = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
                    input.asFloatBuffer().put(window.samples, 0, count)
                    val output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
                    val times = mutableListOf<Double>()
                    repeat(8) { iteration ->
                        input.rewind()
                        output.rewind()
                        val start = SystemClock.elapsedRealtimeNanos()
                        interpreter.run(input, output)
                        if (iteration >= 3) times += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }
                    val scores = FloatArray(interpreter.getOutputTensor(0).numElements())
                    output.rewind()
                    output.asFloatBuffer().get(scores)
                    val previous = reference
                    if (previous == null) reference = scores
                    val difference = previous?.indices?.maxOf { abs(previous[it] - scores[it]) } ?: 0f
                    check(difference < 0.02f) { "$name kernel output changed by $difference" }
                    report.append("$name mode=$mode threads=$threads " +
                        "medianMs=${times.sorted()[times.size / 2]} maxScoreDelta=$difference\n")
                }
            }
        }
        return report.toString()
    }
}

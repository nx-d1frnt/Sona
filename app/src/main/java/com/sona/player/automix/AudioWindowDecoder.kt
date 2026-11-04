package com.sona.player.automix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import com.sona.player.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Decodes only the requested local-file window, respecting output PCM format and timestamps. */
internal class AudioWindowDecoder(private val context: Context) {

    suspend fun decode(song: Song, startMs: Long, durationMs: Long): AudioWindow {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, song.contentUri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return AudioWindow(startMs, FloatArray(0))
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            return decodeSingleWindow(decoder, extractor, startMs, durationMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("SonaAnalysis", "Cannot decode analysis window", failure)
            return AudioWindow(startMs, FloatArray(0))
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } catch (_: Exception) { }
            extractor.release()
        }
    }

    /**
     * Reuses a single MediaExtractor and MediaCodec instance across multiple chronological windows
     * of the same track via decoder.flush() and seeking, avoiding repeated codec allocations and file re-opens.
     */
    suspend fun decodeWindows(
        song: Song,
        windowRanges: List<Pair<Long, Long>>,
        onWindowReady: suspend (AudioWindow) -> Unit
    ) {
        if (windowRanges.isEmpty()) return
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, song.contentUri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            for ((index, range) in windowRanges.withIndex()) {
                val (startMs, durationMs) = range
                if (index > 0) {
                    try {
                        extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        decoder.flush()
                    } catch (e: Exception) {
                        Log.w("SonaAnalysis", "Failed to seek/flush decoder, falling back to standalone decode", e)
                        onWindowReady(decode(song, startMs, durationMs))
                        continue
                    }
                } else {
                    extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                val window = decodeSingleWindow(decoder, extractor, startMs, durationMs)
                onWindowReady(window)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("SonaAnalysis", "Batch window decode failed, falling back to sequential decode", failure)
            for ((startMs, durationMs) in windowRanges) {
                onWindowReady(decode(song, startMs, durationMs))
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private suspend fun decodeSingleWindow(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        startMs: Long,
        durationMs: Long
    ): AudioWindow {
        var outputFormat = decoder.outputFormat
        val collector = PcmWindowCollector(startMs, durationMs)
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        val endUs = (startMs + durationMs) * 1000L
        val deadline = SystemClock.elapsedRealtime() + 8000L
        while (!outputEnded) {
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Audio decoding timed out" }
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val buffer = requireNotNull(decoder.getInputBuffer(index))
                    val count = extractor.readSampleData(buffer, 0)
                    val pts = extractor.sampleTime
                    if (count < 0 || pts >= endUs) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        decoder.queueInputBuffer(index, 0, count, pts, 0)
                        extractor.advance()
                    }
                }
            }
            when (val index = decoder.dequeueOutputBuffer(info, 1_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                else -> if (index >= 0) {
                    try {
                        val buffer = decoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            collector.append(buffer, info.presentationTimeUs,
                                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                encoding == AudioFormat.ENCODING_PCM_FLOAT)
                        }
                    } finally {
                        decoder.releaseOutputBuffer(index, false)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= endUs
                }
            }
        }
        return collector.window()
    }
}

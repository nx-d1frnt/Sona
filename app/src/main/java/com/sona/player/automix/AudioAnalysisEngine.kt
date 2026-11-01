package com.sona.player.automix

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.sona.player.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Hybrid background audio analyzer with persistent local disk cache.
 * Decodes targeted windows (Intro, Core/Drop, Outro) via MediaExtractor + MediaCodec
 * to compute BPM, normalized energy, cue-in (silence skip), and mix-out points.
 */
class AudioAnalysisEngine(private val context: Context) {

    private val memoryCache = ConcurrentHashMap<Long, TrackAnalysis>()
    private val cacheFile = File(context.filesDir, "sona_automix_analysis_v1.json")
    private val codecSemaphore = Semaphore(1) // Limit to 1 background hardware decoder so real-time audio never stutters

    init {
        loadCacheFromDisk()
    }

    fun getCached(songId: Long): TrackAnalysis? = memoryCache[songId]

    suspend fun analyzeSong(song: Song): TrackAnalysis {
        memoryCache[song.id]?.let { return it }

        val analysis = codecSemaphore.withPermit {
            memoryCache[song.id]?.let { return@withPermit it }
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(4000L) {
                    decodeAndAnalyze(song)
                } ?: createFallbackAnalysis(song)
            }
        }

        memoryCache[song.id] = analysis
        saveCacheToDiskAsync()
        return analysis
    }

    private fun decodeAndAnalyze(song: Song): TrackAnalysis {
        val durationMs = song.durationMs.coerceAtLeast(15_000L)

        // 1. Sample 3 compact windows: Intro (first 6s), Core (8s in middle), Outro (last 10s)
        val introFrames = extractEnergyEnvelope(
            song = song,
            startMs = 0L,
            windowDurationMs = min(6_000L, durationMs / 3),
            frameDurationMs = 50 // 20 Hz envelope for intro silence detection
        )

        val coreStartMs = (durationMs * 0.38f).toLong()
        val coreFrames = extractEnergyEnvelope(
            song = song,
            startMs = coreStartMs,
            windowDurationMs = min(8_000L, durationMs / 2),
            frameDurationMs = 20 // 50 Hz high-res envelope for BPM onset autocorrelation
        )

        val outroWindowMs = min(10_000L, durationMs / 3)
        val outroStartMs = (durationMs - outroWindowMs).coerceAtLeast(0L)
        val outroFrames = extractEnergyEnvelope(
            song = song,
            startMs = outroStartMs,
            windowDurationMs = outroWindowMs,
            frameDurationMs = 50 // 20 Hz envelope for outro fade-out detection
        )

        if (coreFrames.isEmpty()) {
            return createFallbackAnalysis(song)
        }

        // 2. Compute normalized perceptual Energy (0.0 .. 1.0)
        val avgRms = coreFrames.average().toFloat()
        val peakRms = (coreFrames.maxOrNull() ?: 0.2f).coerceAtLeast(0.01f)
        // Convert linear RMS to perceptual dB-like 0..1 scale
        val dbValue = 20f * (ln(avgRms.coerceAtLeast(0.0005f)) / ln(10f))
        val normalizedEnergy = ((dbValue + 38f) / 32f).coerceIn(0.12f, 0.98f)

        // 3. Compute BPM via onset flux autocorrelation on 50 Hz core envelope
        val estimatedBpm = estimateBpmFromEnvelope(coreFrames, envelopeHz = 50f, songId = song.id)

        // 4. Compute Cue-In point (skip leading silence / very low intro noise)
        val cueInMs = if (introFrames.isNotEmpty()) {
            val threshold = max(0.012f, peakRms * 0.12f)
            val firstActiveFrame = introFrames.indexOfFirst { it >= threshold }
            if (firstActiveFrame > 0) {
                // Step back 100ms before the transient attack so we don't clip the first beat
                (firstActiveFrame * 50L - 100L).coerceIn(0L, 6500L)
            } else {
                0L
            }
        } else {
            0L
        }

        // 5. Recommended Crossfade Duration based on BPM & Energy
        // Faster / higher-energy tracks get tighter mixes (6-7.5s); slower/melodic tracks get 8-10s
        val recommendedMixDurationMs = when {
            estimatedBpm >= 135f -> 6500L
            estimatedBpm >= 118f -> 7500L
            else -> 9000L
        }

        // 6. Compute Mix-Out point in the outro (where energy drops below 38% of core RMS or trailing silence starts)
        val mixOutMs = if (outroFrames.size > 20) {
            val fadeThreshold = max(0.018f, avgRms * 0.38f)
            var lastLoudFrame = outroFrames.size - 1
            while (lastLoudFrame > 0 && outroFrames[lastLoudFrame] < fadeThreshold) {
                lastLoudFrame--
            }
            val audibleEndMs = outroStartMs + (lastLoudFrame * 50L)
            // Start the crossfade so that the incoming track reaches full volume right as the audible end fades
            val calculatedMixOut = audibleEndMs - (recommendedMixDurationMs * 0.78f).toLong()
            val minMixOut = (durationMs * 0.68f).toLong()
            val maxMixOut = (durationMs - recommendedMixDurationMs).coerceAtLeast(minMixOut)
            calculatedMixOut.coerceIn(minMixOut, maxMixOut)
        } else {
            (durationMs - recommendedMixDurationMs - 1500L).coerceAtLeast((durationMs * 0.75f).toLong())
        }

        return TrackAnalysis(
            songId = song.id,
            bpm = estimatedBpm,
            energy = normalizedEnergy,
            cueInMs = cueInMs,
            mixOutMs = mixOutMs,
            crossfadeDurationMs = recommendedMixDurationMs,
            durationMs = durationMs
        )
    }

    /**
     * Decodes a window of audio via MediaExtractor + MediaCodec and returns an array of RMS energy frames.
     */
    private fun extractEnergyEnvelope(
        song: Song,
        startMs: Long,
        windowDurationMs: Long,
        frameDurationMs: Int
    ): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, song.contentUri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) return FloatArray(0)
            extractor.selectTrack(audioTrackIndex)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 2

            extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val samplesPerFrame = ((sampleRate * frameDurationMs) / 1000) * channels
            val maxFrames = ((windowDurationMs / frameDurationMs) + 4).toInt().coerceAtMost(800)
            val rmsFrames = FloatArray(maxFrames)
            var frameIndex = 0

            var sumSquares = 0.0
            var sampleCount = 0

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            val endUs = (startMs + windowDurationMs) * 1000L

            while (!isOutputEOS && frameIndex < maxFrames) {
                if (!isInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(5000L)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            val presentationTimeUs = extractor.sampleTime
                            if (sampleSize < 0 || presentationTimeUs > endUs) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, sampleSize, presentationTimeUs, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 5000L)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()

                        // Downsample by step 4 for ultra-low CPU usage while preserving RMS envelope
                        val step = 4
                        var idx = 0
                        val limit = shortBuffer.remaining()
                        while (idx < limit && frameIndex < maxFrames) {
                            val sample = shortBuffer.get(idx) / 32768.0
                            sumSquares += sample * sample
                            sampleCount += step
                            if (sampleCount >= samplesPerFrame) {
                                val rms = sqrt(sumSquares / (sampleCount / step)).toFloat()
                                rmsFrames[frameIndex++] = rms
                                sumSquares = 0.0
                                sampleCount = 0
                            }
                            idx += step
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isInputEOS) {
                    break
                }
            }

            return rmsFrames.copyOf(frameIndex)
        } catch (e: Exception) {
            return FloatArray(0)
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Estimates BPM in range [72 .. 172] using onset-strength envelope autocorrelation.
     */
    private fun estimateBpmFromEnvelope(
        envelope: FloatArray,
        envelopeHz: Float,
        songId: Long
    ): Float {
        if (envelope.size < 60) return fallbackBpmForId(songId)

        // 1. First-order positive onset flux (accentuates drum transients / beats)
        val onset = FloatArray(envelope.size)
        for (i in 1 until envelope.size) {
            val diff = envelope[i] - envelope[i - 1]
            onset[i] = if (diff > 0f) diff else 0f
        }

        // Normalize onset
        val mean = onset.average().toFloat()
        for (i in onset.indices) {
            onset[i] = (onset[i] - mean).coerceAtLeast(0f)
        }

        // 2. Autocorrelation for lags corresponding to 72 BPM .. 172 BPM
        // lag = (60 / BPM) * envelopeHz
        val minLag = ((60f / 172f) * envelopeHz).roundToInt().coerceAtLeast(1)
        val maxLag = ((60f / 72f) * envelopeHz).roundToInt().coerceAtMost(onset.size / 2)

        var bestLag = -1
        var bestCorr = -1f

        for (lag in minLag..maxLag) {
            var sum = 0f
            val count = onset.size - lag
            for (i in 0 until count) {
                sum += onset[i] * onset[i + lag]
            }
            // Perceptual tempo prior: slight preference toward common musical groove (95..135 BPM)
            val candidateBpm = (60f * envelopeHz) / lag
            val priorWeight = if (candidateBpm in 94f..138f) 1.12f else 1.0f
            val corr = (sum / count) * priorWeight

            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        if (bestLag <= 0 || bestCorr <= 0.00001f) {
            return fallbackBpmForId(songId)
        }

        val rawBpm = (60f * envelopeHz) / bestLag
        return ((rawBpm * 10f).roundToInt() / 10f).coerceIn(72f, 172f)
    }

    private fun createFallbackAnalysis(song: Song): TrackAnalysis {
        val duration = song.durationMs.coerceAtLeast(15_000L)
        val bpm = fallbackBpmForId(song.id)
        val energy = 0.55f + ((abs(song.id.hashCode()) % 35) / 100f)
        val mixDuration = 7500L
        val mixOut = (duration - mixDuration - 1500L).coerceAtLeast((duration * 0.78f).toLong())
        return TrackAnalysis(
            songId = song.id,
            bpm = bpm,
            energy = energy.coerceIn(0.25f, 0.90f),
            cueInMs = 200L,
            mixOutMs = mixOut,
            crossfadeDurationMs = mixDuration,
            durationMs = duration
        )
    }

    private fun fallbackBpmForId(songId: Long): Float {
        val commonBpms = floatArrayOf(96f, 102f, 110f, 118f, 120f, 124f, 128f, 132f, 140f)
        return commonBpms[(abs(songId) % commonBpms.size).toInt()]
    }

    private fun loadCacheFromDisk() {
        try {
            if (!cacheFile.exists()) return
            val jsonStr = cacheFile.readText()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = TrackAnalysis(
                    songId = obj.getLong("songId"),
                    bpm = obj.getDouble("bpm").toFloat(),
                    energy = obj.getDouble("energy").toFloat(),
                    cueInMs = obj.getLong("cueInMs"),
                    mixOutMs = obj.getLong("mixOutMs"),
                    crossfadeDurationMs = obj.getLong("crossfadeDurationMs"),
                    durationMs = obj.getLong("durationMs")
                )
                memoryCache[item.songId] = item
            }
        } catch (_: Exception) {}
    }

    private suspend fun saveCacheToDiskAsync() = withContext(Dispatchers.IO) {
        try {
            val array = JSONArray()
            for (item in memoryCache.values) {
                val obj = JSONObject().apply {
                    put("songId", item.songId)
                    put("bpm", item.bpm.toDouble())
                    put("energy", item.energy.toDouble())
                    put("cueInMs", item.cueInMs)
                    put("mixOutMs", item.mixOutMs)
                    put("crossfadeDurationMs", item.crossfadeDurationMs)
                    put("durationMs", item.durationMs)
                }
                array.put(obj)
            }
            cacheFile.writeText(array.toString())
        } catch (_: Exception) {}
    }
}

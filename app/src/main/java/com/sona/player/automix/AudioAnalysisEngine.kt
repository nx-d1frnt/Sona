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
    private val cacheFile = File(context.filesDir, "sona_automix_analysis_v4.json")
    private val codecSemaphore = Semaphore(1) // Limit to 1 background hardware decoder so real-time audio never stutters

    init {
        loadCacheFromDisk()
    }

    fun getCached(songId: Long): TrackAnalysis? = memoryCache[songId]

    fun getAllCached(): Map<Long, TrackAnalysis> = HashMap(memoryCache)

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

        // 1. Sample 3 windows: Intro (first 26s for deep beat/drop cue-in search), Core (15s in middle), Outro (last 24s)
        val introWindowMs = min(26_000L, (durationMs * 38L) / 100L)
        val introFrames = extractEnergyEnvelope(
            song = song,
            startMs = 0L,
            windowDurationMs = introWindowMs,
            frameDurationMs = 25 // 40 Hz high-res envelope to detect drum transients & beat drops in the intro
        )

        val coreStartMs = (durationMs * 0.40f).toLong()
        val coreWindowMs = min(15_000L, durationMs / 3)
        val coreFrames = extractEnergyEnvelope(
            song = song,
            startMs = coreStartMs,
            windowDurationMs = coreWindowMs,
            frameDurationMs = 20 // 50 Hz high-res envelope for BPM onset autocorrelation & mid-track breakdown search
        )

        val outroWindowMs = min(24_000L, durationMs / 3)
        val outroStartMs = (durationMs - outroWindowMs).coerceAtLeast(0L)
        val outroFrames = extractEnergyEnvelope(
            song = song,
            startMs = outroStartMs,
            windowDurationMs = outroWindowMs,
            frameDurationMs = 50 // 20 Hz envelope for outro phrase & fade-out detection
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

        // 4. Recommended DJ Transition Duration based on BPM (8 bars / 16 beats of musical overlap)
        val recommendedMixDurationMs = when {
            estimatedBpm >= 132f -> 9500L
            estimatedBpm >= 112f -> 11000L
            else -> 12500L
        }

        // 5. Compute Smart Phrase & Beat-Drop Entry Points (cueInMs & deepCueInMs)
        // Enters away from 0:00 whenever a track has a slow/ambient/beatless intro, aligning the beat drop with the Bass Swap!
        val (cueInMs, deepCueInMs) = findSmartEntryPoints(
            introFrames = introFrames,
            frameDurationMs = 25L,
            avgCoreRms = avgRms,
            peakCoreRms = peakRms,
            estimatedBpm = estimatedBpm,
            recommendedMixDurationMs = recommendedMixDurationMs,
            durationMs = durationMs
        )

        // 6. Compute Musical Outro Phrase Mix-Out point (starts 12..20s before EOF where outro phrase begins)
        val mixOutMs = if (outroFrames.size > 20) {
            // Find where the track drops below 52% of its core energy (outro breakdown) or before trailing silence
            val phraseThreshold = max(0.025f, avgRms * 0.52f)
            var lastEnergeticFrame = outroFrames.size - 1
            while (lastEnergeticFrame > 0 && outroFrames[lastEnergeticFrame] < phraseThreshold) {
                lastEnergeticFrame--
            }
            val phraseEndMs = outroStartMs + (lastEnergeticFrame * 50L)
            // Start the mix so the Bass Swap (50% of mix) hits right around phraseEndMs - 2.5s
            val calculatedMixOut = phraseEndMs - recommendedMixDurationMs - 2000L
            val minMixOut = (durationMs * 0.62f).toLong()
            val maxMixOut = (durationMs - recommendedMixDurationMs - 2500L).coerceAtLeast(minMixOut)
            calculatedMixOut.coerceIn(minMixOut, maxMixOut)
        } else {
            (durationMs - recommendedMixDurationMs - 3000L).coerceAtLeast((durationMs * 0.70f).toLong())
        }

        // 7. Compute Mid-Track Breakdown / Post-Chorus Mix-Out point (for QUICK_MID_MIX / DJ Set mode, 44%..62% of track)
        val midMixOutMs = findMidTrackTransitionPoint(
            coreFrames = coreFrames,
            coreStartMs = coreStartMs,
            durationMs = durationMs,
            mixOutMs = mixOutMs
        )

        return TrackAnalysis(
            songId = song.id,
            bpm = estimatedBpm,
            energy = normalizedEnergy,
            cueInMs = cueInMs,
            mixOutMs = mixOutMs,
            midMixOutMs = midMixOutMs,
            deepCueInMs = deepCueInMs,
            crossfadeDurationMs = recommendedMixDurationMs,
            durationMs = durationMs
        )
    }

    /**
     * Analyzes the first 26s of the track (in 500ms musical blocks combining RMS energy + percussive onset flux)
     * to find:
     * 1. [cueInMs]: Skips beatless intros, skits, ambient pads, and slow build-ups so the incoming track's
     *    first real beat/drop lands right on the DJ Bass Swap (50% of transition).
     * 2. [deepCueInMs]: Finds a deeper high-energy hook/drop entry point (up to 26s into the track) for
     *    Mid-Track DJ mode and high-energy transitions.
     */
    private fun findSmartEntryPoints(
        introFrames: FloatArray,
        frameDurationMs: Long,
        avgCoreRms: Float,
        peakCoreRms: Float,
        estimatedBpm: Float,
        recommendedMixDurationMs: Long,
        durationMs: Long
    ): Pair<Long, Long> {
        if (introFrames.size < 40) return 0L to min(4000L, durationMs / 6)

        // First audible sound frame (skip pure digital silence)
        val silenceThreshold = max(0.014f, peakCoreRms * 0.10f)
        val firstAudibleIdx = introFrames.indexOfFirst { it >= silenceThreshold }.coerceAtLeast(0)
        val firstAudibleMs = (firstAudibleIdx * frameDurationMs - 100L).coerceAtLeast(0L)

        // Group 25ms frames into 500ms phrase blocks (20 frames per block)
        val framesPerBlock = (500L / frameDurationMs).toInt().coerceAtLeast(4)
        val numBlocks = introFrames.size / framesPerBlock
        if (numBlocks < 6) return firstAudibleMs to firstAudibleMs

        val blockRms = FloatArray(numBlocks)
        val blockOnset = FloatArray(numBlocks)
        val blockGroove = FloatArray(numBlocks)

        for (b in 0 until numBlocks) {
            var rmsSum = 0f
            var fluxSum = 0f
            val startIdx = b * framesPerBlock
            for (k in 0 until framesPerBlock) {
                val cur = introFrames[startIdx + k]
                val prev = if (startIdx + k > 0) introFrames[startIdx + k - 1] else cur
                rmsSum += cur
                if (cur > prev) {
                    fluxSum += (cur - prev)
                }
            }
            blockRms[b] = rmsSum / framesPerBlock
            blockOnset[b] = fluxSum / framesPerBlock
            // Combined groove intensity: weights percussive transient punch heavily so beatless pads score low
            blockGroove[b] = blockRms[b] * 0.55f + blockOnset[b] * 2.2f
        }

        val maxIntroGroove = (blockGroove.maxOrNull() ?: avgCoreRms).coerceAtLeast(0.02f)

        // Detect the biggest upward "Beat Drop / Groove Entry" step-jump across adjacent 2-second windows (4 blocks)
        var bestJumpBlock = -1
        var bestJumpRatio = 1.0f
        val fullGrooveThreshold = max(avgCoreRms * 0.58f, maxIntroGroove * 0.62f)

        for (b in 3 until numBlocks - 2) {
            val beforeAvg = (blockGroove[b - 3] + blockGroove[b - 2] + blockGroove[b - 1]) / 3f
            val afterAvg = (blockGroove[b] + blockGroove[b + 1] + blockGroove[b + 2]) / 3f
            // If the section after block b is strong and significantly punchier than the intro before it:
            if (afterAvg >= fullGrooveThreshold * 0.80f && afterAvg > beforeAvg * 1.38f) {
                val jumpScore = (afterAvg - beforeAvg) / beforeAvg.coerceAtLeast(0.015f)
                if (jumpScore > bestJumpRatio) {
                    bestJumpRatio = jumpScore
                    bestJumpBlock = b
                }
            }
        }

        // Also find the first block that reaches full sustained groove energy
        val firstFullGrooveBlock = blockGroove.indexOfFirst { it >= fullGrooveThreshold }

        // Determine where the main beat/groove actually kicks in
        val beatDropBlock = when {
            bestJumpBlock >= 4 -> bestJumpBlock
            firstFullGrooveBlock >= 4 -> firstFullGrooveBlock
            else -> firstFullGrooveBlock.coerceAtLeast(0)
        }
        val beatDropMs = beatDropBlock * 500L

        // One 4-beat bar duration in ms (e.g. 2000ms at 120 BPM)
        val barMs = ((60_000f / estimatedBpm.coerceIn(75f, 170f)) * 4f).toLong().coerceIn(1400L, 3200L)
        // Pre-roll so that if the beat drops late in the intro, the Bass Swap (at ~48% of mix) lands on the Beat Drop!
        val bassSwapLeadMs = (recommendedMixDurationMs * 0.44f).toLong().coerceIn(barMs, barMs * 2)

        val maxAllowedCueMs = (durationMs * 0.28f).toLong().coerceAtMost(24_000L)

        val smartCueInMs = when {
            // Case A: Track has a long intro/skit/ambient build-up before the beat drops (>= 5.0s)
            beatDropMs >= 5000L -> {
                // If the pre-drop section is audible (a riser/vocal intro), start `bassSwapLeadMs` before the drop
                // so the pre-drop plays during the High-Pass entry and the Beat Drop hits right on the Bass Swap!
                // If the pre-drop was practically silent/quiet, jump closer (1 bar before the drop).
                val preDropRms = blockRms[(beatDropBlock - 2).coerceAtLeast(0)]
                val leadIn = if (preDropRms >= avgCoreRms * 0.25f) bassSwapLeadMs else (barMs / 2)
                (beatDropMs - leadIn).coerceIn(firstAudibleMs, maxAllowedCueMs)
            }
            // Case B: Track starts with a moderate 2..5s intro before the beat
            beatDropMs >= 2000L -> {
                (beatDropMs - (barMs / 2)).coerceIn(firstAudibleMs, maxAllowedCueMs)
            }
            // Case C: Track drops the beat right away, but check if there's a stronger 8-bar phrase drop later
            else -> firstAudibleMs
        }

        // Compute deepCueInMs: look in the 8s .. 25s region for the strongest hook/drop phrase
        var deepestPeakBlock = beatDropBlock
        var deepestPeakScore = -1f
        val deepStartBlock = (8000L / 500L).toInt().coerceAtMost(numBlocks - 1)
        for (b in deepStartBlock until numBlocks) {
            if (blockGroove[b] > deepestPeakScore) {
                deepestPeakScore = blockGroove[b]
                deepestPeakBlock = b
            }
        }
        val deepTargetMs = (deepestPeakBlock * 500L - barMs).coerceIn(smartCueInMs, maxAllowedCueMs)

        return smartCueInMs to deepTargetMs
    }

    /**
     * Searches the middle region of the track (40%..62%) for the natural post-chorus energy dip/breakdown
     * right after an energetic peak so Mid-Track DJ Mode transitions on a musical phrase boundary.
     */
    private fun findMidTrackTransitionPoint(
        coreFrames: FloatArray,
        coreStartMs: Long,
        durationMs: Long,
        mixOutMs: Long
    ): Long {
        val minMidMs = (durationMs * 0.44f).toLong().coerceAtLeast(22_000L)
        val maxMidMs = (durationMs * 0.62f).toLong().coerceAtMost((mixOutMs - 8000L).coerceAtLeast(minMidMs))
        if (coreFrames.size < 100 || maxMidMs <= minMidMs) {
            return ((minMidMs + maxMidMs) / 2L).coerceIn(15_000L, mixOutMs)
        }

        // Group 20ms frames into 500ms blocks (25 frames per block)
        val blockSize = 25
        val numBlocks = coreFrames.size / blockSize
        if (numBlocks < 4) return ((minMidMs + maxMidMs) / 2L)

        val blockEnergy = FloatArray(numBlocks)
        for (b in 0 until numBlocks) {
            var sum = 0f
            val offset = b * blockSize
            for (k in 0 until blockSize) {
                sum += coreFrames[offset + k]
            }
            blockEnergy[b] = sum / blockSize
        }

        // 1. Find the strongest chorus peak in the first 60% of the middle window
        val peakSearchEnd = (numBlocks * 0.60f).toInt().coerceAtLeast(1)
        var peakBlock = 0
        var peakVal = -1f
        for (b in 0 until peakSearchEnd) {
            if (blockEnergy[b] > peakVal) {
                peakVal = blockEnergy[b]
                peakBlock = b
            }
        }

        // 2. Find the subsequent breakdown / energy valley after the chorus peak
        var bestValleyBlock = (peakBlock + 2).coerceAtMost(numBlocks - 1)
        var minValleyVal = Float.MAX_VALUE
        for (b in bestValleyBlock until numBlocks) {
            if (blockEnergy[b] < minValleyVal) {
                minValleyVal = blockEnergy[b]
                bestValleyBlock = b
            }
        }

        // Start the mix ~2.5s before the valley floor so the incoming track layers into the breakdown
        val candidateMs = coreStartMs + (bestValleyBlock * 500L) - 2500L
        return candidateMs.coerceIn(minMidMs, maxMidMs)
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
        val mixDuration = 10500L
        val mixOut = (duration - mixDuration - 3500L).coerceAtLeast((duration * 0.68f).toLong())
        val midMixOut = (duration * 0.50f).toLong().coerceAtMost(mixOut)
        return TrackAnalysis(
            songId = song.id,
            bpm = bpm,
            energy = energy.coerceIn(0.25f, 0.90f),
            cueInMs = 800L,
            mixOutMs = mixOut,
            midMixOutMs = midMixOut,
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
                val dur = obj.getLong("durationMs")
                val item = TrackAnalysis(
                    songId = obj.getLong("songId"),
                    bpm = obj.getDouble("bpm").toFloat(),
                    energy = obj.getDouble("energy").toFloat(),
                    cueInMs = obj.getLong("cueInMs"),
                    mixOutMs = obj.getLong("mixOutMs"),
                    midMixOutMs = obj.optLong("midMixOutMs", (dur * 0.50f).toLong()),
                    deepCueInMs = obj.optLong("deepCueInMs", obj.getLong("cueInMs")),
                    crossfadeDurationMs = obj.getLong("crossfadeDurationMs"),
                    durationMs = dur
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
                    put("midMixOutMs", item.midMixOutMs)
                    put("deepCueInMs", item.deepCueInMs)
                    put("crossfadeDurationMs", item.crossfadeDurationMs)
                    put("durationMs", item.durationMs)
                }
                array.put(obj)
            }
            cacheFile.writeText(array.toString())
        } catch (_: Exception) {}
    }
}

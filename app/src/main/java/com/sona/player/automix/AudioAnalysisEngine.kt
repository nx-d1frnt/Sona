package com.sona.player.automix

import android.content.Context
import com.sona.player.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hybrid background audio analyzer with persistent local disk cache.
 * Decodes targeted windows (Intro, Core/Drop, Outro) via MediaExtractor + MediaCodec
 * to compute BPM, normalized energy, cue-in (silence skip), and mix-out points.
 */
class AudioAnalysisEngine(private val context: Context) {

    private val dbHelper = AnalysisDatabaseHelper(context)
    @Volatile private var vocalDetector: YamnetVocalDetector? = null
    private val vocalDetectorMutex = Mutex()

    suspend fun releaseVocalDetector() = vocalDetectorMutex.withLock {
        vocalDetector?.close()
        vocalDetector = null
    }

    private val windowDecoder = AudioWindowDecoder(context)
    private val memoryCache = ConcurrentHashMap<Long, TrackAnalysis>()
    private val cacheFile = File(context.filesDir, "sona_automix_analysis_v7.json")
    private val tempCacheFile = File(context.filesDir, "sona_automix_analysis_v7.json.tmp")
    private val maxParallelDecoders = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
    private val codecSemaphore = Semaphore(maxParallelDecoders)
    private val cacheSaveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pendingSave: kotlinx.coroutines.Job? = null
    suspend fun flushPendingWrites() { pendingSave?.join() }

    init {
        loadCacheFromDisk()
    }

    fun getCached(songId: Long): TrackAnalysis? = memoryCache[songId]

    fun getAllCached(): Map<Long, TrackAnalysis> = HashMap(memoryCache)

    fun needsAnalysis(songId: Long): Boolean = memoryCache[songId]?.isComplete != true

    suspend fun analyzeSong(song: Song, force: Boolean = false): TrackAnalysis {
        memoryCache[song.id]?.takeIf { !force && it.isComplete }?.let { return it }

        val analysis = codecSemaphore.withPermit {
            memoryCache[song.id]?.takeIf { !force && it.isComplete }?.let { return@withPermit it }
            val existing = if (force) null else memoryCache[song.id]?.takeIf { it.featureAnalysisAttempted }
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(60_000L) {
                    if (existing == null) decodeAndAnalyze(song) else enrichMissing(song, existing)
                } ?: existing?.copy(loudnessAnalyzed = true, keyAnalyzed = true) ?: createFallbackAnalysis(song)
            }.also {
                memoryCache[song.id] = it
                saveTrackToDisk(it)
            }
        }

        return analysis
    }

    // Extend older entries in place: cached beats/vocals survive and YAMNet is not run again.
    // Only the passes that are missing (loudness and/or key) are computed from the decoded windows.
    private suspend fun enrichMissing(song: Song, existing: TrackAnalysis): TrackAnalysis {
        val duration = existing.durationMs
        val ranges = listOf(
            0L to minOf(24_000L, duration * 35 / 100),
            (duration * 0.48).toLong() to minOf(14_000L, duration / 4),
            (duration - minOf(38_000L, duration * 28 / 100)).coerceAtLeast(0L) to minOf(38_000L, duration * 28 / 100)
        )
        val windows = mutableListOf<AudioWindow>()
        windowDecoder.decodeWindows(song, ranges) { windows.add(it) }
        var result = existing
        if (!existing.loudnessAnalyzed) {
            result = result.copy(loudnessSections = windows.mapNotNull { LoudnessAnalyzer.analyze(it) }, loudnessAnalyzed = true)
        }
        if (!existing.keyAnalyzed) {
            val chroma = DoubleArray(12)
            val frames = windows.sumOf { KeyDetector.accumulate(it, chroma) }
            val key = KeyDetector.estimate(chroma, frames)
            result = result.copy(keyIndex = key?.index ?: -1, keyConfidence = key?.confidence ?: 0f, keyAnalyzed = true)
        }
        return result
    }

    private suspend fun decodeAndAnalyze(song: Song): TrackAnalysis {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val durationMs = song.durationMs.coerceAtLeast(15_000L)

        // 1. Sample 3 structural windows:
        // - Intro: first 24s (up to 35% of track) at 40 Hz (25ms frames) for smart Beat-Drop Cue-In
        // - Core/Mid: 48% .. 70% of track at 40 Hz (25ms frames) for BPM & Mid-Track Post-Chorus Breakdown
        // - Outro: last 28% of track (18s..38s) at 25 Hz (40ms frames) for Outro Phrase Mix-Out
        val introWindowMs = min(24_000L, (durationMs * 35L) / 100L)
        val coreStartMs = (durationMs * 0.48f).toLong()
        val coreWindowMs = (durationMs * 0.22f).toLong().coerceIn(14_000L, 42_000L)
        val outroWindowMs = (durationMs * 0.28f).toLong().coerceIn(18_000L, 38_000L)
            .coerceAtMost(durationMs / 2)
        val outroStartMs = (durationMs - outroWindowMs).coerceAtLeast(0L)
        val windowRanges = listOf(
            0L to introWindowMs,
            coreStartMs to coreWindowMs,
            outroStartMs to outroWindowMs
        )
        val windows = mutableListOf<AudioWindow>()
        val beats = mutableListOf<BeatSection>()
        val vocals = mutableListOf<VocalSection>()
        val loudnessSections = mutableListOf<LoudnessSection>()
        var decodeMs = 0L
        var beatMs = 0L
        var vocalMs = 0L
        var classifierAvailable = true
        val chroma = DoubleArray(12)
        var chromaFrames = 0
        var keyMs = 0L

        AnalysisPipeline.processStream(
            produce = { emit ->
                val before = android.os.SystemClock.elapsedRealtime()
                windowDecoder.decodeWindows(song, windowRanges) { window ->
                    decodeMs += android.os.SystemClock.elapsedRealtime() - before
                    emit(window)
                }
            },
            consume = { window ->
                windows += window
                val beforeBeats = android.os.SystemClock.elapsedRealtime()
                BeatTracker.analyze(window)?.let { beats += it }
                beatMs += android.os.SystemClock.elapsedRealtime() - beforeBeats

                val beforeKey = android.os.SystemClock.elapsedRealtime()
                chromaFrames += KeyDetector.accumulate(window, chroma)
                keyMs += android.os.SystemClock.elapsedRealtime() - beforeKey

                LoudnessAnalyzer.analyze(window)?.let { loudnessSections += it }

                val beforeVocals = android.os.SystemClock.elapsedRealtime()
                if (classifierAvailable) {
                    try {
                        val detector = vocalDetector ?: synchronized(this) {
                            vocalDetector ?: YamnetVocalDetector(context).also { vocalDetector = it }
                        }
                        vocals += detector.analyze(window)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        classifierAvailable = false
                        android.util.Log.w("SonaAnalysis", "Vocal classifier unavailable", failure)
                    } catch (failure: LinkageError) {
                        classifierAvailable = false
                        android.util.Log.w("SonaAnalysis", "Vocal runtime unavailable", failure)
                    }
                }
                vocalMs += android.os.SystemClock.elapsedRealtime() - beforeVocals
            }
        )
        val intro = windows[0]
        val core = windows[1]
        val outro = windows[2]
        val introFrames = intro.rmsEnvelope(25)
        val coreFrames = core.rmsEnvelope(25)
        val outroFrames = outro.rmsEnvelope(40)
        android.util.Log.i("SonaAnalysisPerf", "song=${song.id} decodeMs=$decodeMs beatMs=$beatMs keyMs=$keyMs vocalMs=$vocalMs wallMs=${android.os.SystemClock.elapsedRealtime()-startedAt}")
        if (coreFrames.isEmpty()) {
            return createFallbackAnalysis(song)
        }

        // 2. Compute normalized perceptual Energy (0.0 .. 1.0)
        val avgRms = coreFrames.average().toFloat()
        val peakRms = (coreFrames.maxOrNull() ?: 0.2f).coerceAtLeast(0.01f)
        // Convert linear RMS to perceptual dB-like 0..1 scale
        val dbValue = 20f * (ln(avgRms.coerceAtLeast(0.0005f)) / ln(10f))
        val normalizedEnergy = ((dbValue + 38f) / 32f).coerceIn(0.12f, 0.98f)

        // 3. Compute BPM via onset flux autocorrelation on 40 Hz core envelope (with half-time octave folding)
        val measuredBpm = beats.firstOrNull { it.startMs == core.startMs && it.confidence >= 0.65f }?.bpm
        val estimatedBpm = measuredBpm ?: estimateBpmFromEnvelope(
            envelope = coreFrames,
            envelopeHz = 40f,
            normalizedEnergy = normalizedEnergy,
            songId = song.id
        )

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

        // 6. Compute Structural Outro Phrase Mix-Out point (70%..90% of track, adapting per song)
        val mixOutMs = findOutroTransitionPoint(
            outroFrames = outroFrames,
            outroStartMs = outro.startMs,
            frameDurationMs = 40L,
            avgCoreRms = avgRms,
            estimatedBpm = estimatedBpm,
            recommendedMixDurationMs = recommendedMixDurationMs,
            durationMs = durationMs
        )

        // 7. Compute Mid-Track Breakdown / Post-Chorus Mix-Out point (strictly >= 50% of track: 50%..67%)
        val midPoint = MidTrackTransitionDetector.detect(
            coreFrames = coreFrames,
            coreStartMs = core.startMs,
            frameDurationMs = 25L,
            estimatedBpm = estimatedBpm,
            durationMs = durationMs,
            mixOutMs = mixOutMs
        )

        val key = KeyDetector.estimate(chroma, chromaFrames)

        return TrackAnalysis(
            songId = song.id,
            bpm = estimatedBpm,
            energy = normalizedEnergy,
            cueInMs = cueInMs,
            mixOutMs = mixOutMs,
            midMixOutMs = midPoint.positionMs,
            midMixConfidence = midPoint.confidence,
            beatSections = beats,
            vocalSections = vocals,
            loudnessSections = loudnessSections,
            loudnessAnalyzed = true,
            keyIndex = key?.index ?: -1,
            keyConfidence = key?.confidence ?: 0f,
            keyAnalyzed = true,
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
     * Analyzes the last 28% of the track (18..38s) in 400ms phrase blocks to find the exact structural
     * outro boundary (where the final chorus drops into the outro beat/fade or before trailing silence),
     * so Classic Outro Mix adapts dynamically to each track (70%..90% of duration).
     */
    private fun findOutroTransitionPoint(
        outroFrames: FloatArray,
        outroStartMs: Long,
        frameDurationMs: Long,
        avgCoreRms: Float,
        estimatedBpm: Float,
        recommendedMixDurationMs: Long,
        durationMs: Long
    ): Long {
        val minMixOut = (durationMs * 0.70f).toLong()
        val maxMixOut = (durationMs - recommendedMixDurationMs - 2000L).coerceAtLeast(minMixOut)
        if (outroFrames.size < 25 || maxMixOut <= minMixOut) {
            return maxMixOut
        }

        val framesPerBlock = (400L / frameDurationMs).toInt().coerceAtLeast(4)
        val numBlocks = outroFrames.size / framesPerBlock
        if (numBlocks < 8) return maxMixOut

        val blockRms = FloatArray(numBlocks)
        val blockGroove = FloatArray(numBlocks)
        for (b in 0 until numBlocks) {
            var rmsSum = 0f
            var fluxSum = 0f
            val offset = b * framesPerBlock
            for (k in 0 until framesPerBlock) {
                val cur = outroFrames[offset + k]
                val prev = if (offset + k > 0) outroFrames[offset + k - 1] else cur
                rmsSum += cur
                if (cur > prev) fluxSum += (cur - prev)
            }
            blockRms[b] = rmsSum / framesPerBlock
            blockGroove[b] = blockRms[b] * 0.65f + (fluxSum / framesPerBlock) * 1.8f
        }

        // 1. Find where audible groove ends (before final fade-out / trailing silence)
        val silenceFloor = max(0.022f, avgCoreRms * 0.45f)
        var lastAudibleBlock = numBlocks - 1
        while (lastAudibleBlock > 4 && blockRms[lastAudibleBlock] < silenceFloor) {
            lastAudibleBlock--
        }
        val audibleEndMs = outroStartMs + (lastAudibleBlock * 400L)

        // 2. Search for the strongest Final-Chorus -> Outro phrase step-down (across 3.2s windows = 8 blocks)
        var bestStepDownBlock = -1
        var bestStepDownScore = 0f
        val windowBlocks = 7 // 2.8s before vs 2.8s after

        for (b in windowBlocks until (lastAudibleBlock - 3).coerceAtLeast(windowBlocks)) {
            val blockTimeMs = outroStartMs + (b * 400L)
            if (blockTimeMs < minMixOut || blockTimeMs > maxMixOut + 4000L) continue

            var beforeSum = 0f
            var afterSum = 0f
            for (k in 1..windowBlocks) {
                beforeSum += blockGroove[b - k]
                if (b + k - 1 < numBlocks) afterSum += blockGroove[b + k - 1]
            }
            val beforeAvg = beforeSum / windowBlocks
            val afterAvg = afterSum / windowBlocks

            if (beforeAvg >= avgCoreRms * 0.55f && afterAvg < beforeAvg * 0.86f) {
                val dropScore = (beforeAvg - afterAvg) / beforeAvg.coerceAtLeast(0.02f)
                if (dropScore > bestStepDownScore) {
                    bestStepDownScore = dropScore
                    bestStepDownBlock = b
                }
            }
        }

        val barMs = ((60_000f / estimatedBpm.coerceIn(75f, 170f)) * 4f).toLong().coerceIn(1400L, 3000L)
        val candidateMs = if (bestStepDownBlock >= 0 && bestStepDownScore >= 0.14f) {
            // Start the mix ~1 bar before the outro step-down so the Bass Swap lands right in the outro groove
            val stepDownMs = outroStartMs + (bestStepDownBlock * 400L)
            min(stepDownMs - barMs, audibleEndMs - recommendedMixDurationMs)
        } else {
            audibleEndMs - recommendedMixDurationMs - 1500L
        }

        return candidateMs.coerceIn(minMixOut, maxMixOut)
    }

    /**
     * Estimates BPM in range [76 .. 172] using onset-strength envelope autocorrelation
     * with automatic half-time -> double-time octave folding for energetic pop/electronic tracks.
     */
    private fun estimateBpmFromEnvelope(
        envelope: FloatArray,
        envelopeHz: Float,
        normalizedEnergy: Float,
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
        if (maxLag <= minLag) return fallbackBpmForId(songId)

        val corrByLag = FloatArray(maxLag + 1)
        var bestLag = -1
        var bestCorr = -1f

        for (lag in minLag..maxLag) {
            var sum = 0f
            val count = onset.size - lag
            for (i in 0 until count) {
                sum += onset[i] * onset[i + lag]
            }
            // Perceptual tempo prior: slight preference toward club/pop/electronic range (92..152 BPM)
            val candidateBpm = (60f * envelopeHz) / lag
            val priorWeight = if (candidateBpm in 92f..152f) 1.14f else 1.0f
            val corr = (sum / count) * priorWeight
            corrByLag[lag] = corr

            if (corr > bestCorr) {
                bestCorr = corr
                bestLag = lag
            }
        }

        if (bestLag <= 0 || bestCorr <= 0.00001f) {
            return fallbackBpmForId(songId)
        }

        var rawBpm = (60f * envelopeHz) / bestLag
        // Octave folding: if autocorrelation locked onto a 2-bar half-time subharmonic (e.g. 73 BPM instead of 146 BPM),
        // check the double-time lag (bestLag / 2) and fold up when double-time harmonic is strong or track energy is high!
        if (rawBpm < 88f) {
            val halfLag = (bestLag / 2).coerceIn(minLag, maxLag)
            val doubleTimeCorr = corrByLag[halfLag]
            if (doubleTimeCorr >= bestCorr * 0.58f || normalizedEnergy >= 0.48f) {
                rawBpm *= 2f
            }
        }

        return ((rawBpm * 10f).roundToInt() / 10f).coerceIn(76f, 172f)
    }

    private fun createFallbackAnalysis(song: Song): TrackAnalysis {
        val duration = song.durationMs.coerceAtLeast(15_000L)
        val bpm = fallbackBpmForId(song.id)
        val hashOffset = (abs(song.id.hashCode()) % 15) // 0..14% deterministic per-song spread if fallback is ever used
        val energy = 0.55f + ((abs(song.id.hashCode()) % 35) / 100f)
        val mixDuration = 10500L
        val mixOut = (duration - mixDuration - 3500L - (hashOffset * 400L))
            .coerceIn((duration * 0.72f).toLong(), (duration * 0.89f).toLong())
        val midFraction = 0.52f + (hashOffset / 100f) // 52% .. 66%
        val midMixOut = (duration * midFraction).toLong().coerceAtMost(mixOut - 6000L)
        return TrackAnalysis(
            songId = song.id,
            bpm = bpm,
            energy = energy.coerceIn(0.25f, 0.90f),
            cueInMs = 800L,
            mixOutMs = mixOut,
            midMixOutMs = midMixOut,
            crossfadeDurationMs = mixDuration,
            loudnessAnalyzed = true,
            keyAnalyzed = true, // Decode failed; don't retry the key pass forever
            durationMs = duration
        )
    }

    private fun fallbackBpmForId(songId: Long): Float {
        val commonBpms = floatArrayOf(96f, 102f, 110f, 118f, 120f, 124f, 128f, 132f, 140f)
        return commonBpms[(abs(songId) % commonBpms.size).toInt()]
    }

    private fun loadCacheFromDisk() {
        try {
            val fromDb = dbHelper.getAll()
            if (fromDb.isNotEmpty()) {
                memoryCache.putAll(fromDb)
                return
            }
            // Migrate legacy cache from JSON if SQLite is empty
            val sourceFile = when {
                cacheFile.exists() && cacheFile.length() > 2L -> cacheFile
                tempCacheFile.exists() && tempCacheFile.length() > 2L -> tempCacheFile
                else -> return
            }
            val jsonStr = sourceFile.readText()
            val array = JSONArray(jsonStr)
            val migrated = mutableListOf<TrackAnalysis>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val dur = obj.getLong("durationMs").coerceAtLeast(15_000L)
                val rawMid = obj.optLong("midMixOutMs", (dur * 0.56f).toLong())
                val safeMid = rawMid.coerceIn((dur * 0.50f).toLong(), (dur * 0.67f).toLong())
                val item = TrackAnalysis(
                    songId = obj.getLong("songId"),
                    bpm = obj.getDouble("bpm").toFloat(),
                    energy = obj.getDouble("energy").toFloat(),
                    cueInMs = obj.getLong("cueInMs"),
                    mixOutMs = obj.getLong("mixOutMs"),
                    midMixOutMs = safeMid,
                    midMixConfidence = obj.optDouble("midMixConfidence", 0.0).toFloat()
                        .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                    deepCueInMs = obj.optLong("deepCueInMs", obj.getLong("cueInMs")),
                    crossfadeDurationMs = obj.getLong("crossfadeDurationMs"),
                    durationMs = dur,
                    beatSections = readBeatSections(obj.optJSONArray("beats")),
                    vocalSections = readVocalSections(obj.optJSONArray("vocals")),
                    loudnessSections = readLoudnessSections(obj.optJSONArray("loudness")),
                    featureAnalysisAttempted = (obj.optInt("featuresVersion", 0) >= 1 ||
                        (obj.optJSONArray("vocals")?.length() ?: 0) > 0 ||
                            (obj.optJSONArray("beats")?.length() ?: 0) > 0 ||
                            (obj.optJSONArray("loudness")?.length() ?: 0) > 0),
                    loudnessAnalyzed = obj.optBoolean("loudnessAnalyzed", false)
                )
                memoryCache[item.songId] = item
                migrated.add(item)
            }
            if (migrated.isNotEmpty()) {
                dbHelper.insertAll(migrated)
                try {
                    cacheFile.delete()
                    tempCacheFile.delete()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun readBeatSections(array: JSONArray?): List<BeatSection> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            val bpm = obj.getDouble("bpm").toFloat()
            val confidence = obj.getDouble("confidence").toFloat()
            val start = obj.getLong("start")
            val end = obj.getLong("end")
            if (!bpm.isFinite() || bpm <= 0 || !confidence.isFinite() || end <= start) null
            else {
                val times = obj.getJSONArray("times")
                BeatSection(start, end, bpm, confidence.coerceIn(0f, 1f),
                    (0 until times.length()).map { times.getLong(it) }.filter { it in start..end }.distinct().sorted())
            }
        }
    }

    private fun readVocalSections(array: JSONArray?): List<VocalSection> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            val score = obj.getDouble("score").toFloat()
            val start = obj.getLong("start")
            val end = obj.getLong("end")
            if (!score.isFinite() || end <= start) null else VocalSection(start, end, score.coerceIn(0f, 1f))
        }
    }

    private fun readLoudnessSections(array: JSONArray?): List<LoudnessSection> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            val lufs = obj.getDouble("lufs").toFloat()
            val peak = obj.getDouble("peak").toFloat()
            val start = obj.getLong("start")
            val end = obj.getLong("end")
            if (!lufs.isFinite() || !peak.isFinite() || peak <= 0f || end <= start) null
            else LoudnessSection(start, end, lufs, peak)
        }
    }

    /**
     * Saves the individual track analysis to local SQLite storage in O(1) time.
     */
    private fun saveTrackToDisk(analysis: TrackAnalysis) {
        pendingSave = cacheSaveScope.launch {
            try {
                dbHelper.insertOrUpdate(analysis)
            } catch (_: Exception) {}
        }
    }
}

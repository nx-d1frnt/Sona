package com.sona.player.automix

import com.sona.player.R
import com.sona.player.data.model.Song
import kotlin.math.abs
import kotlin.math.min

/**
 * Curated DJ Vibe Presets for Sona's Automix Sets.
 */
enum class AutomixVibe(
    val id: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val descriptionRes: Int
) {
    HIGH_ENERGY(
        id = "high_energy",
        titleRes = R.string.vibe_high_energy_title,
        subtitleRes = R.string.vibe_high_energy_sub,
        descriptionRes = R.string.vibe_high_energy_desc
    ),
    CHILL(
        id = "chill",
        titleRes = R.string.vibe_chill_title,
        subtitleRes = R.string.vibe_chill_sub,
        descriptionRes = R.string.vibe_chill_desc
    ),
    INSTRUMENTAL(
        id = "instrumental",
        titleRes = R.string.vibe_instrumental_title,
        subtitleRes = R.string.vibe_instrumental_sub,
        descriptionRes = R.string.vibe_instrumental_desc
    ),
    NIGHT_DRIVE(
        id = "night_drive",
        titleRes = R.string.vibe_night_drive_title,
        subtitleRes = R.string.vibe_night_drive_sub,
        descriptionRes = R.string.vibe_night_drive_desc
    );

    companion object {
        fun fromId(id: String?): AutomixVibe? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * Intelligent sequencing engine that creates seamless DJ playlists and mixes
 * using Sona's acoustic analysis database (BPM, Energy, Camelot Key, Vocals).
 */
object SmartMixGenerator {

    const val DEFAULT_TARGET_TRACKS = 50
    private const val MIN_RECOMMENDED_MATCHES = 15

    fun currentDayEpoch(): Long {
        return try {
            java.time.LocalDate.now().toEpochDay()
        } catch (_: Throwable) {
            System.currentTimeMillis() / 86_400_000L
        }
    }

    /**
     * Filters tracks in the library matching an [AutomixVibe] profile.
     */
    fun filterTracksForVibe(
        vibe: AutomixVibe,
        allSongs: List<Song>,
        analysisProvider: (Long) -> TrackAnalysis?
    ): List<Pair<Song, TrackAnalysis>> {
        val pairs = allSongs.mapNotNull { song ->
            val analysis = analysisProvider(song.id) ?: return@mapNotNull null
            song to analysis
        }

        val primary = pairs.filter { (song, analysis) -> matchesVibePrimary(vibe, song, analysis) }
        if (primary.size >= MIN_RECOMMENDED_MATCHES) {
            return primary
        }

        // Broaden slightly if user's library has fewer matching analyzed songs
        val fallback = pairs.filter { (song, analysis) -> matchesVibeFallback(vibe, song, analysis) }
        return if (fallback.isNotEmpty()) fallback else primary
    }

    private fun matchesVibePrimary(vibe: AutomixVibe, song: Song, analysis: TrackAnalysis): Boolean {
        return when (vibe) {
            AutomixVibe.HIGH_ENERGY -> {
                analysis.energy >= 0.65f || (analysis.bpm >= 120f && analysis.energy >= 0.52f)
            }
            AutomixVibe.CHILL -> {
                analysis.energy <= 0.45f && analysis.bpm <= 130f
            }
            AutomixVibe.INSTRUMENTAL -> {
                val vocalRatio = analysis.vocalActivityRatio
                val isTagInstrumental = isInstrumentalTitle(song.title)
                (vocalRatio != null && vocalRatio <= 0.15) || isTagInstrumental
            }
            AutomixVibe.NIGHT_DRIVE -> {
                analysis.bpm in 98f..132f && analysis.energy in 0.42f..0.78f
            }
        }
    }

    private fun matchesVibeFallback(vibe: AutomixVibe, song: Song, analysis: TrackAnalysis): Boolean {
        return when (vibe) {
            AutomixVibe.HIGH_ENERGY -> {
                analysis.energy >= 0.55f || analysis.bpm >= 115f
            }
            AutomixVibe.CHILL -> {
                analysis.energy <= 0.52f
            }
            AutomixVibe.INSTRUMENTAL -> {
                val vocalRatio = analysis.vocalActivityRatio
                (vocalRatio != null && vocalRatio <= 0.28) || isInstrumentalTitle(song.title)
            }
            AutomixVibe.NIGHT_DRIVE -> {
                analysis.bpm in 90f..138f && analysis.energy in 0.35f..0.85f
            }
        }
    }

    private fun isInstrumentalTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("instrumental") || lower.contains("karaoke") || lower.contains("dub mix") || lower.contains("ambient")
    }

    /**
     * Generates an optimal harmonic & tempo chain for an [AutomixVibe].
     */
    fun generateVibeMix(
        vibe: AutomixVibe,
        allSongs: List<Song>,
        analysisProvider: (Long) -> TrackAnalysis?,
        playStats: Map<Long, Int> = emptyMap(),
        maxTracks: Int = DEFAULT_TARGET_TRACKS,
        dayEpoch: Long = currentDayEpoch()
    ): List<Song> {
        val matchingPool = filterTracksForVibe(vibe, allSongs, analysisProvider)
        if (matchingPool.isEmpty()) return emptyList()
        if (matchingPool.size == 1) return listOf(matchingPool.first().first)

        // Select the best anchor song (prefer popular / well-analyzed track with typical vibe tempo)
        val sortedCandidates = matchingPool.map { pair ->
            val (song, analysis) = pair
            val plays = playStats[song.id] ?: 0
            val conf = if (analysis.isComplete) 1f else 0.5f
            val baseScore = plays * 2f + conf + (1f - abs(analysis.energy - 0.6f))
            pair to baseScore
        }.sortedWith(compareByDescending<Pair<Pair<Song, TrackAnalysis>, Float>> { it.second }.thenBy { it.first.first.id })

        val starter = if (dayEpoch != 0L) {
            val windowSize = min(sortedCandidates.size, 12)
            val seedHash = abs((dayEpoch * 31L + vibe.ordinal * 17L).hashCode())
            val index = seedHash % windowSize
            sortedCandidates[index].first
        } else {
            sortedCandidates.first().first
        }

        return chainTracks(starter, matchingPool, playStats, maxTracks, dayEpoch)
    }

    /**
     * Generates an optimal harmonic & tempo chain starting from a [seedSong].
     */
    fun generateSeedMix(
        seedSong: Song,
        allSongs: List<Song>,
        analysisProvider: (Long) -> TrackAnalysis?,
        playStats: Map<Long, Int> = emptyMap(),
        maxTracks: Int = DEFAULT_TARGET_TRACKS,
        dayEpoch: Long = 0L
    ): List<Song> {
        val seedAnalysis = analysisProvider(seedSong.id)
            ?: TrackAnalysis(
                songId = seedSong.id,
                bpm = 120f,
                energy = 0.5f,
                cueInMs = 0L,
                mixOutMs = (seedSong.durationMs * 0.85f).toLong(),
                crossfadeDurationMs = 10_000L,
                durationMs = seedSong.durationMs
            )

        val candidatePool = allSongs.mapNotNull { song ->
            val analysis = analysisProvider(song.id) ?: return@mapNotNull null
            song to analysis
        }

        return chainTracks(seedSong to seedAnalysis, candidatePool, playStats, maxTracks, dayEpoch)
    }

    /**
     * Sequences tracks into a seamless Harmonic & Tempo Chain using Sona's
     * Camelot key wheel distance, BPM proximity ratios, and artist diversity penalty.
     */
    fun chainTracks(
        anchor: Pair<Song, TrackAnalysis>,
        pool: List<Pair<Song, TrackAnalysis>>,
        playStats: Map<Long, Int> = emptyMap(),
        maxTracks: Int = DEFAULT_TARGET_TRACKS,
        dayEpoch: Long = 0L
    ): List<Song> {
        val resultSongs = mutableListOf<Song>()
        val resultAnalyses = mutableListOf<TrackAnalysis>()

        resultSongs.add(anchor.first)
        resultAnalyses.add(anchor.second)

        val remaining = pool.filter { it.first.id != anchor.first.id }.toMutableList()
        val recentArtists = ArrayDeque<String>()
        normalizeArtist(anchor.first.artist)?.let { recentArtists.addLast(it) }

        while (remaining.isNotEmpty() && resultSongs.size < maxTracks) {
            val currentAnalysis = resultAnalyses.last()
            val currentArtist = normalizeArtist(resultSongs.last().artist)

            var bestIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY

            for (i in remaining.indices) {
                val (candidateSong, candidateAnalysis) = remaining[i]
                val compatScore = AutomixQueueSelector.computeCompatibilityScore(currentAnalysis, candidateAnalysis)

                // Artist variety penalty
                val candArtist = normalizeArtist(candidateSong.artist)
                val artistPenalty = when {
                    candArtist != null && candArtist == currentArtist -> 0.28f
                    candArtist != null && candArtist in recentArtists -> 0.12f
                    else -> 0f
                }

                // Play count familiarity bump
                val plays = playStats[candidateSong.id] ?: 0
                val playScore = (min(plays, 20) / 20f) * 0.06f

                // Daily variety seed: subtle deterministic perturbation per day & track
                val dailyJitter = if (dayEpoch != 0L) {
                    val hash = abs((candidateSong.id xor (dayEpoch * 2654435761L)).hashCode()) % 1000
                    (hash / 1000f - 0.5f) * 0.08f
                } else 0f

                val finalScore = compatScore + playScore - artistPenalty + dailyJitter
                if (finalScore > bestScore) {
                    bestScore = finalScore
                    bestIndex = i
                }
            }

            if (bestIndex >= 0) {
                val chosen = remaining.removeAt(bestIndex)
                resultSongs.add(chosen.first)
                resultAnalyses.add(chosen.second)
                normalizeArtist(chosen.first.artist)?.let {
                    if (recentArtists.size >= 3) recentArtists.removeFirst()
                    recentArtists.addLast(it)
                }
            } else {
                break
            }
        }

        return resultSongs
    }

    private fun normalizeArtist(artist: String?): String? {
        val cleaned = artist?.trim()?.lowercase() ?: return null
        if (cleaned.isEmpty() || cleaned == "<unknown>" || cleaned == "unknown artist" || cleaned == "various artists") {
            return null
        }
        return cleaned
    }
}

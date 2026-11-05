package com.sona.player.lyrics.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.sona.player.data.model.Song
import com.sona.player.data.repository.SettingsRepository
import com.sona.player.lyrics.database.LyricsDatabaseHelper
import com.sona.player.lyrics.model.LyricSource
import com.sona.player.lyrics.model.TrackLyrics
import com.sona.player.lyrics.network.LrclibApiClient
import com.sona.player.lyrics.network.LrclibResponse
import com.sona.player.lyrics.parser.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class LyricsRepository(
    private val context: Context,
    private val databaseHelper: LyricsDatabaseHelper = LyricsDatabaseHelper.getInstance(context),
    private val apiClient: LrclibApiClient = LrclibApiClient(),
    private val settingsRepository: SettingsRepository = SettingsRepository.getInstance(context)
) {
    companion object {
        @Volatile
        private var instance: LyricsRepository? = null

        fun getInstance(context: Context): LyricsRepository {
            return instance ?: synchronized(this) {
                instance ?: LyricsRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Resolves lyrics following the hybrid waterfall:
     * Cache -> Local .lrc sidecar -> Embedded tag -> LRCLIB Online API
     */
    suspend fun getLyricsForSong(song: Song): TrackLyrics? = withContext(Dispatchers.IO) {
        // 1. Check SQLite Cache
        val cached = databaseHelper.getLyrics(song.id)
        if (cached != null) {
            return@withContext cached
        }

        // 2. Check Local .lrc Sidecar File
        val sidecarLyrics = findSidecarLrc(song)
        if (sidecarLyrics != null) {
            databaseHelper.saveLyrics(
                songId = song.id,
                source = LyricSource.LOCAL_FILE,
                rawLrc = sidecarLyrics.plainLyrics?.takeIf { sidecarLyrics.isSynced } ?: sidecarLyrics.lines.joinToString("\n") { it.text },
                plainLyrics = sidecarLyrics.plainLyrics,
                isSynced = sidecarLyrics.isSynced,
                userOffsetMs = 0L
            )
            return@withContext sidecarLyrics
        }

        // 3. Check Embedded Tags
        val embeddedLyrics = findEmbeddedLyrics(song)
        if (embeddedLyrics != null) {
            databaseHelper.saveLyrics(
                songId = song.id,
                source = LyricSource.EMBEDDED,
                rawLrc = embeddedLyrics.plainLyrics?.takeIf { embeddedLyrics.isSynced } ?: embeddedLyrics.lines.joinToString("\n") { it.text },
                plainLyrics = embeddedLyrics.plainLyrics,
                isSynced = embeddedLyrics.isSynced,
                userOffsetMs = 0L
            )
            return@withContext embeddedLyrics
        }

        // 4. Online Fetch via LRCLIB
        val shouldFetchOnline = canFetchOnline()
        if (shouldFetchOnline) {
            val response = apiClient.fetchLyrics(
                trackName = cleanSongTitle(song.title),
                artistName = song.artist,
                albumName = song.album,
                durationSeconds = (song.durationMs / 1000).toInt()
            )

            if (response != null) {
                val rawLrc = response.syncedLyrics ?: response.plainLyrics
                if (!rawLrc.isNullOrBlank()) {
                    val parsed = LrcParser.parse(
                        songId = song.id,
                        lrcContent = rawLrc,
                        source = LyricSource.LRCLIB
                    )
                    databaseHelper.saveLyrics(
                        songId = song.id,
                        source = LyricSource.LRCLIB,
                        rawLrc = response.syncedLyrics,
                        plainLyrics = response.plainLyrics ?: parsed.plainLyrics,
                        isSynced = parsed.isSynced,
                        userOffsetMs = 0L
                    )
                    return@withContext parsed
                }
            }
        }

        null
    }

    /**
     * Manually updates the timing offset for a song in ms.
     */
    suspend fun updateOffset(songId: Long, offsetMs: Long) = withContext(Dispatchers.IO) {
        databaseHelper.updateOffset(songId, offsetMs)
    }

    /**
     * Saves user-selected lyrics from manual search.
     */
    suspend fun applyManualLyrics(songId: Long, response: LrclibResponse): TrackLyrics = withContext(Dispatchers.IO) {
        val rawLrc = response.syncedLyrics ?: response.plainLyrics
        val parsed = LrcParser.parse(
            songId = songId,
            lrcContent = rawLrc,
            source = LyricSource.MANUAL
        )
        databaseHelper.saveLyrics(
            songId = songId,
            source = LyricSource.MANUAL,
            rawLrc = response.syncedLyrics,
            plainLyrics = response.plainLyrics ?: parsed.plainLyrics,
            isSynced = parsed.isSynced,
            userOffsetMs = 0L
        )
        parsed
    }

    suspend fun searchOnlineLyrics(query: String): List<LrclibResponse> {
        return apiClient.searchLyrics(query)
    }

    private fun findSidecarLrc(song: Song): TrackLyrics? {
        if (song.filePath.isBlank()) return null
        try {
            val audioFile = File(song.filePath)
            if (!audioFile.exists()) return null

            val lrcCandidate = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
            if (lrcCandidate.exists() && lrcCandidate.canRead()) {
                val content = lrcCandidate.readText(Charsets.UTF_8)
                return LrcParser.parse(song.id, content, LyricSource.LOCAL_FILE)
            }
        } catch (e: Exception) {
            // Ignore file read exceptions
        }
        return null
    }

    private fun findEmbeddedLyrics(song: Song): TrackLyrics? {
        val retriever = MediaMetadataRetriever()
        try {
            if (song.filePath.isNotBlank() && File(song.filePath).exists()) {
                retriever.setDataSource(song.filePath)
            } else {
                retriever.setDataSource(context, song.contentUri)
            }
            // MediaMetadataRetriever key 28 is METADATA_KEY_LYRICS (API 35/MediaMetadataRetriever) or key "28"
            // Or extract via getEmbeddedPicture / generic extract
            val rawLyrics = retriever.extractMetadata(28) // METADATA_KEY_LYRICS
            if (!rawLyrics.isNullOrBlank()) {
                return LrcParser.parse(song.id, rawLyrics, LyricSource.EMBEDDED)
            }
        } catch (e: Exception) {
            // Ignore
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {}
        }
        return null
    }

    private suspend fun canFetchOnline(): Boolean {
        val enabled = settingsRepository.fetchOnlineLyricsFlow.first()
        if (!enabled) return false

        val wifiOnly = settingsRepository.lyricsWifiOnlyFlow.first()
        if (!wifiOnly) return true

        return isWifiConnected()
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun cleanSongTitle(title: String): String {
        // Strip common suffixes like (Official Video), [Remastered], etc. for higher API match rates
        return title
            .replace(Regex("(?i)\\[(official|video|audio|remastered|lyrics|hd|4k)[^\\]]*\\]"), "")
            .replace(Regex("(?i)\\((official|video|audio|remastered|lyrics|hd|4k)[^\\)]*\\)"), "")
            .trim()
    }
}

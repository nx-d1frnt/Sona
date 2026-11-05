package com.sona.player.lyrics.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class LrclibResponse(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String?,
    val durationSeconds: Double,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

class LrclibApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val USER_AGENT = "SonaMusicPlayer/1.0.0 (Android)"
    }

    /**
     * Attempts exact match fetch via /api/get
     */
    suspend fun fetchLyrics(
        trackName: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Int? = null
    ): LrclibResponse? = withContext(Dispatchers.IO) {
        val urlBuilder = "$BASE_URL/get".toHttpUrlOrNull()?.newBuilder() ?: return@withContext null
        urlBuilder.addQueryParameter("track_name", trackName)
        urlBuilder.addQueryParameter("artist_name", artistName)
        if (!albumName.isNullOrBlank()) {
            urlBuilder.addQueryParameter("album_name", albumName)
        }
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return@withContext null
                    parseLrclibJson(JSONObject(bodyString))
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Searches LRCLIB via /api/search for manual search or fuzzy matching
     */
    suspend fun searchLyrics(query: String): List<LrclibResponse> = withContext(Dispatchers.IO) {
        val urlBuilder = "$BASE_URL/search".toHttpUrlOrNull()?.newBuilder() ?: return@withContext emptyList()
        urlBuilder.addQueryParameter("q", query)

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: return@withContext emptyList()
                    val jsonArray = JSONArray(bodyString)
                    val results = mutableListOf<LrclibResponse>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.optJSONObject(i) ?: continue
                        parseLrclibJson(obj)?.let { results.add(it) }
                    }
                    results
                } else {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseLrclibJson(obj: JSONObject): LrclibResponse? {
        val id = obj.optLong("id", -1L)
        val trackName = obj.optString("trackName", "")
        val artistName = obj.optString("artistName", "")
        if (trackName.isEmpty() && artistName.isEmpty()) return null

        return LrclibResponse(
            id = id,
            trackName = trackName,
            artistName = artistName,
            albumName = obj.optString("albumName").takeIf { it.isNotBlank() },
            durationSeconds = obj.optDouble("duration", 0.0),
            instrumental = obj.optBoolean("instrumental", false),
            plainLyrics = obj.optString("plainLyrics").takeIf { it.isNotBlank() },
            syncedLyrics = obj.optString("syncedLyrics").takeIf { it.isNotBlank() }
        )
    }
}

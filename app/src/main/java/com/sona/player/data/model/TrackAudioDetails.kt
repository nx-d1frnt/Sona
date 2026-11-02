package com.sona.player.data.model

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.sona.player.R
import java.io.File
import java.util.Locale

data class TrackAudioDetails(
    val songId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val format: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val channelCount: Int,
    val fileSizeBytes: Long,
    val filePath: String,
    val year: Int,
    val trackNumber: Int
) {
    fun getFormattedFileSize(context: Context): String {
        if (fileSizeBytes <= 0) return context.getString(R.string.unknown)
        val mb = fileSizeBytes.toDouble() / (1024 * 1024)
        return context.getString(R.string.file_size_mb, mb)
    }

    fun getFormattedBitrate(context: Context): String {
        return if (bitrateKbps > 0) {
            context.getString(R.string.bitrate_kbps, bitrateKbps)
        } else {
            context.getString(R.string.bitrate_vbr_unknown)
        }
    }

    fun getFormattedSampleRate(context: Context): String {
        return if (sampleRateHz > 0) {
            context.getString(R.string.sample_rate_khz, sampleRateHz / 1000.0)
        } else {
            context.getString(R.string.unknown)
        }
    }

    fun getFormattedChannels(context: Context): String {
        return when (channelCount) {
            1 -> context.getString(R.string.channels_mono)
            2 -> context.getString(R.string.channels_stereo)
            6 -> context.getString(R.string.channels_5_1)
            8 -> context.getString(R.string.channels_7_1)
            else -> if (channelCount > 0) {
                context.getString(R.string.channels_count, channelCount)
            } else {
                context.getString(R.string.unknown)
            }
        }
    }

    companion object {
        fun extract(context: Context, song: Song): TrackAudioDetails {
            var mimeType = "audio/*"
            var bitrate = 0
            var sampleRate = 0
            var channels = 2
            var path = ""

            // Resolve file path from MediaStore if possible
            try {
                val projection = arrayOf(MediaStore.Audio.Media.DATA)
                context.contentResolver.query(song.contentUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (pathCol >= 0) {
                            path = cursor.getString(pathCol) ?: ""
                        }
                    }
                }
            } catch (_: Exception) {}

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, song.contentUri)
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mimeType
                bitrate = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0) / 1000
                sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                channels = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.toIntOrNull() ?: 2
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }

            // Detect format extension
            val extension = when {
                path.isNotEmpty() -> File(path).extension.uppercase(Locale.US)
                mimeType.contains("flac", ignoreCase = true) -> "FLAC"
                mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "MP3"
                mimeType.contains("aac", ignoreCase = true) || mimeType.contains("mp4", ignoreCase = true) -> "AAC / M4A"
                mimeType.contains("ogg", ignoreCase = true) || mimeType.contains("vorbis", ignoreCase = true) -> "OGG"
                mimeType.contains("opus", ignoreCase = true) -> "OPUS"
                mimeType.contains("wav", ignoreCase = true) -> "WAV"
                else -> mimeType.substringAfterLast("/").uppercase(Locale.US)
            }

            return TrackAudioDetails(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                durationMs = song.durationMs,
                format = extension.ifEmpty { "AUDIO" },
                mimeType = mimeType,
                bitrateKbps = bitrate,
                sampleRateHz = sampleRate,
                channelCount = channels,
                fileSizeBytes = song.size,
                filePath = path.ifEmpty { song.contentUri.toString() },
                year = song.year,
                trackNumber = song.trackNumber
            )
        }
    }
}

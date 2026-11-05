package com.sona.player.lyrics.model

enum class LyricSource {
    LOCAL_FILE,
    EMBEDDED,
    LRCLIB,
    MANUAL
}

data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

data class LyricLine(
    val startMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList()
)

data class TrackLyrics(
    val songId: Long,
    val lines: List<LyricLine> = emptyList(),
    val plainLyrics: String? = null,
    val isSynced: Boolean = true,
    val userOffsetMs: Long = 0L,
    val source: LyricSource = LyricSource.LOCAL_FILE
) {
    val isEmpty: Boolean
        get() = lines.isEmpty() && plainLyrics.isNullOrBlank()

    /**
     * Resolves the line active at the specified timestamp taking user offset into account.
     * Returns the index of the active line in [lines], or -1 if none is active yet.
     */
    fun findActiveLineIndex(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val adjustedPosition = positionMs - userOffsetMs
        if (adjustedPosition < lines.first().startMs) return -1

        var low = 0
        var high = lines.size - 1
        var result = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].startMs <= adjustedPosition) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

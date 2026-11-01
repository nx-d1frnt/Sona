package com.sona.player.automix

/** Counts actual foreground-deck playback, independently of source timestamps and seeks. */
internal class TrackListeningGuard {
    companion object {
        const val MIN_PLAYED_MS = 45_000L
        fun earliestPosition(positionMs: Long, durationMs: Long, playedMs: Long, speed: Float): Long {
            fun after(required: Long) = positionMs + ((required - playedMs).coerceAtLeast(0L) * speed).toLong()
            return minOf(after(MIN_PLAYED_MS), maxOf(durationMs - 12_000L, after(5000L)))
        }
    }
    var manualSeek: Boolean = false
        private set
    fun onManualSeek() { manualSeek = true; discontinuity() }

    var entryPositionMs: Long = 0L
        private set
    var playedMs: Long = 0L
        private set
    private var lastTickMs: Long? = null
    private var wasPlaying = false
    private var lastPositionMs: Long? = null

    fun enter(positionMs: Long) {
        manualSeek = false
        entryPositionMs = positionMs.coerceAtLeast(0L)
        playedMs = 0L
        lastTickMs = null
        wasPlaying = false
        lastPositionMs = null
    }

    fun tick(nowMs: Long, isPlaying: Boolean, positionMs: Long? = null, speed: Float = 1f) {
        val previous = lastTickMs
        if (previous != null && wasPlaying && isPlaying) {
            val wall = (nowMs - previous).coerceAtLeast(0L)
            val oldPosition = lastPositionMs
            playedMs += if (positionMs != null && oldPosition != null) {
                val audio = ((positionMs - oldPosition).coerceAtLeast(0L) / speed.coerceIn(0.5f, 2f)).toLong()
                minOf(wall, audio)
            } else wall.coerceAtMost(1000L)
        }
        lastPositionMs = positionMs
        lastTickMs = nowMs
        wasPlaying = isPlaying
    }

    fun discontinuity() {
        lastTickMs = null
        lastPositionMs = null
        wasPlaying = false
    }

    fun allowsAutomaticTransition(remainingMs: Long): Boolean = manualSeek || canTransition ||
        (remainingMs in 0L..12_000L && playedMs >= 5000L)

    val canTransition: Boolean get() = playedMs >= MIN_PLAYED_MS
}

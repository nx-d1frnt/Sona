package com.sona.player.ui.components

internal enum class SonaLiveKind { MIXING, QUEUE_UPDATED, PAUSED, SMART_SHUFFLE, REPEATING, UP_NEXT, QUEUE_END }

internal data class SonaLiveMessage(val kind: SonaLiveKind, val trackTitle: String? = null)

/** The window is measured in listening time, including any Automix tempo adjustment. */
internal fun isSonaLiveUpcoming(positionMs: Long, changeAtMs: Long, tempoRatio: Float): Boolean {
    if (changeAtMs <= 0L) return false
    val remainingMs = (changeAtMs - positionMs) / tempoRatio.coerceIn(0.5f, 2f)
    return remainingMs in 0f..15_000f
}

/** Active playback and imminent track changes take priority over background queue work. */
internal fun resolveSonaLiveMessage(
    isPlaying: Boolean,
    isTransitioning: Boolean,
    transitionTitle: String?,
    nextTitle: String?,
    repeatOne: Boolean,
    smartShuffleEnabled: Boolean,
    isAnalyzing: Boolean,
    showQueueUpdate: Boolean,
    showUpcoming: Boolean
): SonaLiveMessage? = when {
    isPlaying && isTransitioning && transitionTitle != null ->
        SonaLiveMessage(SonaLiveKind.MIXING, transitionTitle)
    !isPlaying -> SonaLiveMessage(SonaLiveKind.PAUSED)
    showUpcoming && repeatOne && nextTitle != null -> SonaLiveMessage(SonaLiveKind.REPEATING, nextTitle)
    showUpcoming && nextTitle != null -> SonaLiveMessage(SonaLiveKind.UP_NEXT, nextTitle)
    showUpcoming -> SonaLiveMessage(SonaLiveKind.QUEUE_END)
    smartShuffleEnabled && showQueueUpdate -> SonaLiveMessage(SonaLiveKind.QUEUE_UPDATED)
    smartShuffleEnabled && isAnalyzing -> SonaLiveMessage(SonaLiveKind.SMART_SHUFFLE)
    else -> null
}

/** One notice per song/shuffle session; later background batches must not extend its deadline. */
internal fun queueNoticeStart(previousStartMs: Long, updateMs: Long): Long = when {
    updateMs <= 0L -> 0L
    previousStartMs > 0L -> previousStartMs
    else -> updateMs
}

internal fun queueNoticeRemainingMs(startMs: Long, nowMs: Long): Long =
    if (startMs <= 0L || nowMs < startMs) 0L else (6_000L - (nowMs - startMs)).coerceAtLeast(0L)

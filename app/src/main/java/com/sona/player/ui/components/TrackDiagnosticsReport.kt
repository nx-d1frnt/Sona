package com.sona.player.ui.components

import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.MusicalCueSelector
import com.sona.player.automix.TrackAnalysis
import com.sona.player.automix.TransitionPlan
import com.sona.player.data.model.Song

/** Stable field names make a copied report useful regardless of the UI language. */
internal object TrackDiagnosticsReport {
    fun track(song: Song): String = buildString {
        appendLine("[Track]")
        appendLine("id=${song.id}")
        appendLine("title=${song.title}")
        appendLine("artist=${song.artist}")
        appendLine("album=${song.album}")
        appendLine("durationMs=${song.durationMs}")
        appendLine("year=${song.year} trackNumber=${song.trackNumber}")
        appendLine("sizeBytes=${song.size} dateAdded=${song.dateAdded}")
        appendLine("uri=${song.contentUri}")
        appendLine("filePath=${song.filePath}")
    }

    fun analysis(track: TrackAnalysis): String = buildString {
        appendLine("[Cached analysis]")
        appendLine("songId=${track.songId} durationMs=${track.durationMs}")
        appendLine("complete=${track.isComplete} featuresAttempted=${track.featureAnalysisAttempted}")
        appendLine("bpm=${track.bpm} energy=${track.energy}")
        appendLine("key=${track.musicalKey?.camelotLabel ?: "unknown"} keyIndex=${track.keyIndex}")
        appendLine("keyConfidence=${track.keyConfidence} keyAnalyzed=${track.keyAnalyzed}")
        appendLine("cueInMs=${track.cueInMs} deepCueInMs=${track.deepCueInMs}")
        appendLine("mixOutMs=${track.mixOutMs} midMixOutMs=${track.midMixOutMs}")
        appendLine("midMixConfidence=${track.midMixConfidence}")
        appendLine("crossfadeDurationMs=${track.crossfadeDurationMs}")
        appendLine("vocalModelVersion=${track.vocalAnalysisVersion} vocalsCurrent=${track.hasCurrentVocalAnalysis}")
        appendLine("vocalActivityPercent=${track.vocalPercentage ?: "unknown"}")
        val vocalScores = track.vocalSections.map { it.score }.filter { it.isFinite() }
        appendLine("vocalScores: min=${vocalScores.minOrNull() ?: "unknown"} " +
            "mean=${vocalScores.takeIf { it.isNotEmpty() }?.average() ?: "unknown"} " +
            "max=${vocalScores.maxOrNull() ?: "unknown"}")
        appendLine("loudnessAnalyzed=${track.loudnessAnalyzed}")
        appendLine("[Vocal sections: ${track.vocalSections.size}]")
        appendLine("Combined model evidence, not calibrated probabilities. Unsampled audio is unknown.")
        track.vocalSections.forEach { appendLine("${it.startMs}..${it.endMs} ms score=${it.score}") }
        appendLine("[Beat sections: ${track.beatSections.size}]")
        track.beatSections.forEach {
            appendLine("${it.startMs}..${it.endMs} ms bpm=${it.bpm} confidence=${it.confidence} downbeatPhase=${it.downbeatPhase}")
            appendLine("beatsMs=${it.beatsMs.joinToString(", ")}")
            appendLine("downbeatsMs=${it.downbeatsMs.joinToString(", ")}")
            appendLine("phrases16Ms=${it.phraseBoundaries16Ms.joinToString(", ")}")
            appendLine("phrases32Ms=${it.phraseBoundaries32Ms.joinToString(", ")}")
        }
        appendLine("[Loudness sections: ${track.loudnessSections.size}]")
        track.loudnessSections.forEach {
            appendLine("${it.startMs}..${it.endMs} ms lufs=${it.lufs} samplePeak=${it.samplePeak}")
        }
        appendLine("Per-class model outputs and inference timings are not stored in the track cache.")
    }

    fun live(
        positionMs: Long, durationMs: Long, playing: Boolean, queueIndex: Int, queueSize: Int,
        shuffle: String, repeatMode: Int, analysis: TrackAnalysis?, state: AutomixTransitionState,
        plan: TransitionPlan?, displayedMixOutMs: Long?
    ): String = buildString {
        appendLine("[Live playback]")
        appendLine("positionMs=$positionMs durationMs=$durationMs playing=$playing")
        appendLine("queueIndex=$queueIndex queueSize=$queueSize shuffle=$shuffle repeatMode=$repeatMode")
        val voice = analysis?.let { MusicalCueSelector.vocalRisk(it, positionMs, positionMs + 1000) }
        appendLine("localVocalRisk1s=${voice ?: "unknown"}")
        val beat = analysis?.beatSections?.filter { positionMs >= it.startMs && positionMs < it.endMs }
            ?.maxByOrNull { it.confidence }
        appendLine("localBpm=${beat?.bpm ?: "unknown"} beatConfidence=${beat?.confidence ?: "unknown"}")
        val loudness = analysis?.loudnessSections?.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
        appendLine("localLufs=${loudness?.lufs ?: "unknown"} samplePeak=${loudness?.samplePeak ?: "unknown"}")
        appendLine("[Automix]")
        appendLine("enabled=${state.isEnabled} mode=${state.mode} analyzing=${state.isAnalyzing}")
        appendLine("entryPositionMs=${state.entryPositionMs} playedMs=${state.playedMs} manualSeek=${state.manualSeek}")
        appendLine("transitioning=${state.isTransitioning} progress=${state.transitionProgress}")
        appendLine("tempoRatio=${state.activeTempoRatio} activeTool=${state.activeTransitionTool}")
        appendLine("matchScorePercent=${state.matchScorePercent}")
        appendLine("nextSongId=${state.nextMatchedSong?.id} nextTitle=${state.nextMatchedSong?.title}")
        appendLine("transitionFromId=${state.transitionFromSongId} targetId=${state.transitionTargetSong?.id}")
        appendLine("displayedMixOutMs=${displayedMixOutMs ?: "unknown"}")
        appendLine("preview=${plan ?: "unavailable"}")
        val nextId = state.transitionTargetSong?.id ?: state.nextMatchedSong?.id
        val saved = if (analysis != null && nextId != null) {
            state.savedTransitions[com.sona.player.automix.SavedTransition.key(analysis.songId, nextId)]
        } else null
        appendLine("savedTransition=${saved ?: "unavailable"}")
    }
}

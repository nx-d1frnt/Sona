package com.sona.player.ui.components

import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sona.player.R
import com.sona.player.automix.AutomixTransitionState
import com.sona.player.automix.ShuffleMode
import com.sona.player.automix.TrackAnalysis
import com.sona.player.automix.TransitionPlan
import com.sona.player.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PlayerTrackDiagnostics(
    song: Song, analysis: TrackAnalysis?, automix: AutomixTransitionState,
    plan: TransitionPlan?, displayedMixOutMs: Long?, positionMs: Long, durationMs: Long,
    isPlaying: Boolean, queueIndex: Int, queueSize: Int, shuffleMode: ShuffleMode, repeatMode: Int,
    modifier: Modifier = Modifier
) {
    var showRaw by remember(song.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val loading = stringResource(R.string.player_diagnostics_loading_format)
    val unavailable = stringResource(R.string.player_diagnostics_format_unavailable)
    val noAnalysis = stringResource(R.string.player_diagnostics_no_analysis)
    val sourceFormat by produceState(loading, song.id, song.contentUri) {
        value = withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, song.contentUri, null)
                val audioTrack = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                audioTrack?.let { extractor.getTrackFormat(it).toString() } ?: unavailable
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                unavailable
            } finally {
                extractor.release()
            }
        }
    }
    val cached = remember(song, analysis, noAnalysis) {
        TrackDiagnosticsReport.track(song) + (analysis?.let { TrackDiagnosticsReport.analysis(it) } ?: noAnalysis)
    }
    val cachedLines = remember(cached) { cached.lines() }
    // Only this small block changes with playback ticks. Raw section formatting stays cached.
    val live = TrackDiagnosticsReport.live(positionMs, durationMs, isPlaying, queueIndex, queueSize,
        shuffleMode.name, repeatMode, analysis, automix, plan, displayedMixOutMs)

    Surface(modifier = modifier, color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_diagnostics_title),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString("Sona track diagnostics\n$live\n$cached\n[Source format]\n$sourceFormat"))
                }) {
                    Text(stringResource(R.string.player_diagnostics_copy), color = Color.White)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    item("summary") {
                        Text("${song.title}\n${analysis?.bpm?.let { "%.1f BPM".format(it) } ?: "BPM unknown"}  ·  Key ${analysis?.musicalKey?.camelotLabel ?: "unknown"}  ·  Vocals ${analysis?.vocalPercentage?.let { "$it%" } ?: "unknown"}", color = Color.White)
                    }
                    item("timeline") { analysis?.let { TrackAnalysisTimeline(it, positionMs, durationMs) } }
                    item("cues") {
                        analysis?.let {
                            DiagnosticText("Entry: ${it.cueInMs / 1000}s · Mix out: ${it.mixOutMs / 1000}s · Mid mix: ${it.midMixOutMs / 1000}s")
                        }
                    }
                    item("expand") {
                        TextButton(onClick = { showRaw = !showRaw }) {
                            Text(if (showRaw) "Hide raw diagnostics" else "Show raw diagnostics", color = Color.White)
                        }
                    }
                    if (showRaw) {
                        item("live") { DiagnosticText(live) }
                        item("format") { DiagnosticText("[Source format]\n$sourceFormat\n") }
                        items(cachedLines) { DiagnosticText(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticText(value: String) {
    Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
        color = Color.White.copy(alpha = 0.9f))
}

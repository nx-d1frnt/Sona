package com.sona.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sona.player.automix.TrackAnalysis

/** Displays measured features only; empty regions are not inferred to be silent. */
@Composable
internal fun TrackAnalysisTimeline(track: TrackAnalysis, positionMs: Long, durationMs: Long) {
    val duration = durationMs.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Vocals (pink) · Beats (blue) · Cues (gold)", color = Color.White, fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(100.dp).padding(vertical = 8.dp)) {
            fun x(ms: Long) = (ms.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() * size.width
            drawRect(Color.White.copy(alpha = 0.07f))
            track.vocalSections.forEach {
                val left = x(it.startMs)
                val width = (x(it.endMs) - left).coerceAtLeast(1f)
                // A grey backing identifies measured regions even when vocal evidence is low.
                drawRect(Color.White.copy(alpha = 0.12f), Offset(left, 0f), Size(width, size.height * .55f))
                val strength = if (it.score.isFinite()) it.score.coerceIn(0f, 1f) else 0f
                drawRect(Color(0xFFFF91B5), Offset(left, size.height * .55f * (1 - strength)),
                    Size(width, size.height * .55f * strength))
            }
            track.beatSections.forEach { section ->
                section.beatsMs.forEach { beat ->
                    drawLine(Color(0xFF80CBFF).copy(alpha = .65f), Offset(x(beat), size.height * .65f),
                        Offset(x(beat), size.height), 1.dp.toPx())
                }
            }
            listOf(track.cueInMs, track.mixOutMs, track.midMixOutMs).forEach {
                drawLine(Color(0xFFFFDA82), Offset(x(it), 0f), Offset(x(it), size.height), 2.dp.toPx())
            }
            drawLine(Color.White, Offset(x(positionMs), 0f), Offset(x(positionMs), size.height), 2.dp.toPx())
        }
        Text("Grey = measured audio · Blank = unsampled · White = playback", color = Color.LightGray, fontSize = 11.sp)
    }
}

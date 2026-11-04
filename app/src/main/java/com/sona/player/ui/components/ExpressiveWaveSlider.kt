package com.sona.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.State
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun ExpressiveWaveSlider(
    progress: Float,
    isPlaying: Boolean,
    primaryColor: Color,
    automixAccentColor: Color,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
    automixZoneStartFraction: Float = 0.85f,
    isTransitioning: Boolean = false,
    transitionProgress: Float = 0f,
    targetPrimaryColor: Color? = null,
    bpm: Float? = null,
    activeTempoRatio: Float = 1.0f,
    animatedProgress: State<Float>? = null
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progress) }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    val activeProgress = if (isDragging) dragProgress else (animatedProgress?.value ?: progress).coerceIn(0f, 1f)

    val mixFraction by animateFloatAsState(
        targetValue = if (isTransitioning) transitionProgress.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "MixFraction"
    )
    // One rounded swell across the overlap; it settles back before the new track takes over.
    val mixEmphasis by animateFloatAsState(
        targetValue = if (isTransitioning && isPlaying && !isDragging) {
            sin(PI.toFloat() * transitionProgress.coerceIn(0f, 1f))
        } else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "MixEmphasis"
    )
    val waveAmplitudeRatio by animateFloatAsState(
        targetValue = if (isPlaying && !isDragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
        label = "WaveAmplitude"
    )
    val thumbWidthDp by animateDpAsState(
        targetValue = if (isDragging) 8.dp else (5f + 2f * mixEmphasis).dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "ThumbWidth"
    )
    val thumbHeightDp by animateDpAsState(
        targetValue = if (isDragging) 30.dp else (22f + 4f * mixEmphasis).dp,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "ThumbHeight"
    )

    val incomingColor = targetPrimaryColor ?: automixAccentColor
    val zoneBlend = if (automixZoneStartFraction < 0.99f) {
        ((activeProgress - automixZoneStartFraction) /
            (1f - automixZoneStartFraction).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
    } else 0f
    val startColor by animateColorAsState(
        targetValue = if (isTransitioning) lerp(primaryColor, incomingColor, mixFraction) else primaryColor,
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "WaveStartColor"
    )
    val tipColor by animateColorAsState(
        targetValue = lerp(primaryColor, incomingColor, if (isTransitioning) {
            1f
        } else zoneBlend),
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
        label = "WaveTipColor"
    )

    // Integrate phase instead of restarting a repeating tween as the tempo ratio changes.
    val effectiveBpm = bpm?.times(activeTempoRatio)?.takeIf { it.isFinite() && it > 40f }
    val cycleSeconds by rememberUpdatedState(
        effectiveBpm?.let { (120f / it).coerceIn(0.6f, 2.4f) } ?: 1.6f
    )
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        val durationScale = coroutineContext[MotionDurationScale]
        var lastFrame = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { frame ->
                val scale = durationScale?.scaleFactor ?: 1f
                if (scale > 0f) {
                    val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
                    phase = (phase + seconds * 2f * PI.toFloat() / (cycleSeconds * scale)) % (2f * PI.toFloat())
                }
                lastFrame = frame
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                    var latestFraction = (down.position.x / widthPx).coerceIn(0f, 1f)

                    isDragging = true
                    dragProgress = latestFraction
                    currentOnValueChange(latestFraction)

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            latestFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                            dragProgress = latestFraction
                            currentOnValueChange(latestFraction)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })

                    isDragging = false
                    currentOnValueChangeFinished(latestFraction)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val centerY = size.height / 2f
            val strokeWidth = 5.dp.toPx()
            val inactiveStrokeWidth = 5.dp.toPx()
            val maxAmplitude = (4.5f + 1.5f * mixEmphasis).dp.toPx() * waveAmplitudeRatio.coerceIn(0f, 1f)
            val wavelength = (28f + 6f * mixEmphasis).dp.toPx()

            val thumbX = (activeProgress * width).coerceIn(0f, width)
            val thumbW = thumbWidthDp.toPx()
            val thumbH = thumbHeightDp.toPx()
            val gap = thumbW / 2f + 6.dp.toPx()

            // 1. Inactive Track (Right of Thumb) with subtle Automix Zone highlight at the end
            val inactiveStartX = (thumbX + gap).coerceAtMost(width)
            if (inactiveStartX < width) {
                val automixStartX = (width * automixZoneStartFraction.coerceIn(0f, 1f)).coerceAtLeast(inactiveStartX)

                // Standard inactive segment
                if (automixStartX > inactiveStartX) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.24f),
                        start = Offset(inactiveStartX, centerY),
                        end = Offset(automixStartX, centerY),
                        strokeWidth = inactiveStrokeWidth,
                        cap = StrokeCap.Round
                    )
                }

                // Automix Zone segment at the end of the track
                if (automixStartX < width && automixZoneStartFraction < 0.99f) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.24f),
                                incomingColor.copy(alpha = 0.55f)
                            ),
                            startX = automixStartX,
                            endX = width
                        ),
                        start = Offset(automixStartX, centerY),
                        end = Offset(width, centerY),
                        strokeWidth = inactiveStrokeWidth,
                        cap = StrokeCap.Round
                    )

                    // Small glowing dot at the end indicating Automix transition point
                    drawCircle(
                        color = incomingColor.copy(alpha = 0.85f),
                        radius = 2.5.dp.toPx(),
                        center = Offset(width - 3.dp.toPx(), centerY)
                    )
                }
            }

            // 2. Active Wave Track (Left of Thumb)
            val activeEndX = (thumbX - gap).coerceAtLeast(0f)
            if (activeEndX > 2f) {
                val wavePath = Path()
                val step = 3f
                var x = 0f

                while (x <= activeEndX) {
                    // Taper amplitude near 0 and near the thumb for clean rounded ends
                    val edgeTaper = ((x / 16.dp.toPx()).coerceIn(0f, 1f) *
                            ((activeEndX - x) / 16.dp.toPx()).coerceIn(0f, 1f))
                    val y = centerY + sin((x / wavelength) * (2f * PI).toFloat() - phase) * maxAmplitude * edgeTaper
                    if (x == 0f) {
                        wavePath.moveTo(x, y)
                    } else {
                        wavePath.lineTo(x, y)
                    }
                    x += step
                }
                wavePath.lineTo(activeEndX, centerY)

                // The incoming cover's hue grows from the tip, then fills the whole wave.
                val activeBrush = Brush.horizontalGradient(
                    colors = listOf(startColor, tipColor),
                    startX = 0f,
                    endX = activeEndX
                )

                drawPath(
                    path = wavePath,
                    brush = activeBrush,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round
                    )
                )
            }

            drawRoundRect(
                color = tipColor,
                topLeft = Offset(thumbX - thumbW / 2f, centerY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f)
            )
        }
    }
}

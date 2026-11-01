package com.sona.player.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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
    automixZoneStartFraction: Float = 0.85f
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progress) }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    val activeProgress = if (isDragging) dragProgress else progress.coerceIn(0f, 1f)

    // Wave amplitude smoothly flattens when paused or when the user is scrubbing
    val targetAmplitudeRatio = if (isPlaying && !isDragging) 1f else 0f
    val waveAmplitudeRatio by animateFloatAsState(
        targetValue = targetAmplitudeRatio,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "WaveAmplitude"
    )

    // Interactive thumb morphing on touch/drag
    val thumbWidthDp by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 5.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "ThumbWidth"
    )
    val thumbHeightDp by animateDpAsState(
        targetValue = if (isDragging) 30.dp else 22.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ThumbHeight"
    )

    // Continuous phase shift for the animated wave
    val infiniteTransition = rememberInfiniteTransition(label = "WaveInfinite")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing)
        ),
        label = "WavePhase"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
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
            val maxAmplitude = 4.5.dp.toPx() * waveAmplitudeRatio
            val wavelength = 28.dp.toPx()

            val thumbX = (activeProgress * width).coerceIn(0f, width)
            val thumbW = thumbWidthDp.toPx()
            val thumbH = thumbHeightDp.toPx()
            val gap = thumbW / 2f + 6.dp.toPx()

            // 1. Inactive Track (Right of Thumb) with subtle Automix Zone highlight at the end
            val inactiveStartX = (thumbX + gap).coerceAtMost(width)
            if (inactiveStartX < width) {
                val automixStartX = (width * automixZoneStartFraction).coerceAtLeast(inactiveStartX)

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
                                automixAccentColor.copy(alpha = 0.55f)
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
                        color = automixAccentColor.copy(alpha = 0.85f),
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

                val activeBrush = if (activeProgress > automixZoneStartFraction) {
                    Brush.horizontalGradient(
                        colors = listOf(primaryColor, automixAccentColor),
                        startX = 0f,
                        endX = activeEndX
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(primaryColor, primaryColor),
                        startX = 0f,
                        endX = activeEndX
                    )
                }

                drawPath(
                    path = wavePath,
                    brush = activeBrush,
                    style = Stroke(
                        width = strokeWidth,
                        cap = StrokeCap.Round
                    )
                )
            }

            // 3. Expressive Pill Thumb
            val currentThumbColor = if (activeProgress > automixZoneStartFraction) {
                automixAccentColor
            } else {
                primaryColor
            }

            // Subtle outer glow when dragging
            if (isDragging) {
                drawRoundRect(
                    color = currentThumbColor.copy(alpha = 0.30f),
                    topLeft = Offset(thumbX - (thumbW + 10.dp.toPx()) / 2f, centerY - (thumbH + 8.dp.toPx()) / 2f),
                    size = Size(thumbW + 10.dp.toPx(), thumbH + 8.dp.toPx()),
                    cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                )
            }

            drawRoundRect(
                color = currentThumbColor,
                topLeft = Offset(thumbX - thumbW / 2f, centerY - thumbH / 2f),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(thumbW / 2f, thumbW / 2f)
            )
        }
    }
}

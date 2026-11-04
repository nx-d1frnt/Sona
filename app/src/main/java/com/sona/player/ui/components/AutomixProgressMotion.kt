package com.sona.player.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

internal data class AutomixProgressMotion(
    val progress: State<Float>,
    val outgoingColor: Color
)

/** Follow the actual deck position, including handoffs that happen before or after 50%. */
@Composable
internal fun rememberAutomixProgressMotion(
    progress: Float,
    primaryColor: Color,
    isTransitioning: Boolean,
    targetSongId: Long?
): AutomixProgressMotion {
    // Keep the outgoing hue throughout the overlap even after the current artwork changes.
    val outgoingColor = remember(isTransitioning, targetSongId) { primaryColor }
    val position = animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "AutomixDeckPosition"
    )
    return AutomixProgressMotion(
        progress = position,
        outgoingColor = if (isTransitioning) outgoingColor else primaryColor
    )
}

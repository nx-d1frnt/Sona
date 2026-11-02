package com.sona.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ExpressiveTransportControls(
    isPlaying: Boolean,
    primaryAccent: Color,
    onPrimaryAccent: Color,
    secondaryAccent: Color,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Асимметричная кнопка Previous (расширяется, когда главная кнопка сжимается)
        val prevInteraction = remember { MutableInteractionSource() }
        val isPrevPressed by prevInteraction.collectIsPressedAsState()

        val prevWeight by animateFloatAsState(
            targetValue = when {
                isPrevPressed -> 1.15f
                isPlaying -> 1f
                else -> 0.72f
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "PrevWeight"
        )
        val prevScale by animateFloatAsState(
            targetValue = if (isPrevPressed) 0.92f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "PrevScale"
        )
        val prevOuterCorner by animateDpAsState(
            targetValue = if (isPrevPressed) 20.dp else 34.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "PrevOuterCorner"
        )
        val prevInnerCorner by animateDpAsState(
            targetValue = if (isPrevPressed) 26.dp else if (isPlaying) 22.dp else 16.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "PrevInnerCorner"
        )

        Surface(
            onClick = onPreviousClick,
            interactionSource = prevInteraction,
            shape = RoundedCornerShape(
                topStart = prevOuterCorner,
                bottomStart = prevOuterCorner,
                topEnd = prevInnerCorner,
                bottomEnd = prevInnerCorner
            ),
            color = Color.White.copy(alpha = if (isPrevPressed) 0.24f else 0.14f),
            modifier = Modifier
                .weight(prevWeight)
                .height(68.dp)
                .graphicsLayer {
                    scaleX = prevScale
                    scaleY = prevScale
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_previous),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 2. Главная Hero-кнопка Play/Pause (Чистый геометрический морфинг M3 Expressive: Pill <-> Squircle)
        val playInteraction = remember { MutableInteractionSource() }
        val isPlayPressed by playInteraction.collectIsPressedAsState()

        val playScale by animateFloatAsState(
            targetValue = if (isPlayPressed) 0.92f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "PlayPressScale"
        )

        // На паузе — широкая капсула (weight 1.9f), при воспроизведении — собранный выразительный сквиркл (weight 1.35f)
        val heroWeight by animateFloatAsState(
            targetValue = when {
                isPlayPressed -> 1.45f
                isPlaying -> 1.35f
                else -> 1.95f
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "HeroWeight"
        )

        // Морфинг углов: На паузе полностью круглая пилюля (34.dp), при воспроизведении — стильный сквиркл (20.dp)
        val heroCorner by animateDpAsState(
            targetValue = when {
                isPlayPressed -> 16.dp
                isPlaying -> 20.dp
                else -> 34.dp
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "HeroCorner"
        )

        Surface(
            onClick = onPlayPauseClick,
            interactionSource = playInteraction,
            shape = RoundedCornerShape(heroCorner),
            color = primaryAccent,
            shadowElevation = 10.dp,
            modifier = Modifier
                .weight(heroWeight)
                .height(68.dp)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                }
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(200)) + scaleIn(
                            initialScale = 0.75f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
                        )).togetherWith(fadeOut(tween(140)) + scaleOut(targetScale = 0.75f))
                    },
                    label = "PlayPauseContentMorph"
                ) { playing ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = androidx.compose.ui.res.stringResource(if (playing) com.sona.player.R.string.action_pause else com.sona.player.R.string.action_play),
                            tint = onPrimaryAccent,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
        }

        // 3. Асимметричная кнопка Next (расширяется, когда главная кнопка сжимается)
        val nextInteraction = remember { MutableInteractionSource() }
        val isNextPressed by nextInteraction.collectIsPressedAsState()

        val nextWeight by animateFloatAsState(
            targetValue = when {
                isNextPressed -> 1.15f
                isPlaying -> 1f
                else -> 0.72f
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "NextWeight"
        )
        val nextScale by animateFloatAsState(
            targetValue = if (isNextPressed) 0.92f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "NextScale"
        )
        val nextOuterCorner by animateDpAsState(
            targetValue = if (isNextPressed) 20.dp else 34.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "NextOuterCorner"
        )
        val nextInnerCorner by animateDpAsState(
            targetValue = if (isNextPressed) 26.dp else if (isPlaying) 22.dp else 16.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "NextInnerCorner"
        )

        Surface(
            onClick = onNextClick,
            interactionSource = nextInteraction,
            shape = RoundedCornerShape(
                topStart = nextInnerCorner,
                bottomStart = nextInnerCorner,
                topEnd = nextOuterCorner,
                bottomEnd = nextOuterCorner
            ),
            color = Color.White.copy(alpha = if (isNextPressed) 0.24f else 0.14f),
            modifier = Modifier
                .weight(nextWeight)
                .height(68.dp)
                .graphicsLayer {
                    scaleX = nextScale
                    scaleY = nextScale
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = androidx.compose.ui.res.stringResource(com.sona.player.R.string.action_next),
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

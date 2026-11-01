package com.sona.player.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Асимметричная лепестковая кнопка Previous с пружинной физикой
        val prevInteraction = remember { MutableInteractionSource() }
        val isPrevPressed by prevInteraction.collectIsPressedAsState()

        val prevScale by animateFloatAsState(
            targetValue = if (isPrevPressed) 0.86f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "PrevScale"
        )
        val prevOuterCorner by animateDpAsState(
            targetValue = if (isPrevPressed) 18.dp else 32.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "PrevOuterCorner"
        )
        val prevInnerCorner by animateDpAsState(
            targetValue = if (isPrevPressed) 26.dp else if (isPlaying) 20.dp else 14.dp,
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
            color = Color.White.copy(alpha = if (isPrevPressed) 0.22f else 0.13f),
            modifier = Modifier
                .size(width = 68.dp, height = 62.dp)
                .graphicsLayer {
                    scaleX = prevScale
                    scaleY = prevScale
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }

        // 2. Главная Hero-кнопка Play/Pause с живым морфингом формы (M3 Expressive Shape Morphing)
        val playInteraction = remember { MutableInteractionSource() }
        val isPlayPressed by playInteraction.collectIsPressedAsState()

        val playScale by animateFloatAsState(
            targetValue = if (isPlayPressed) 0.90f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "PlayPressScale"
        )

        // Ширина плавно перетекает от широкой капсулы (Paused = 188.dp) к органическому сквирклу/цветку (Playing = 154.dp)
        val heroWidth by animateDpAsState(
            targetValue = when {
                isPlayPressed -> 170.dp
                isPlaying -> 158.dp
                else -> 192.dp
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "HeroWidth"
        )

        val heroHeight by animateDpAsState(
            targetValue = if (isPlaying) 72.dp else 66.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "HeroHeight"
        )

        // Коэффициент органической волнистости краев (0f на паузе -> 1f при воспроизведении)
        val organicMorphProgress by animateFloatAsState(
            targetValue = if (isPlaying && !isPlayPressed) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "OrganicMorph"
        )

        // Бесконечное вращение фазы волны и легкое «дыхание» во время проигрывания
        val infiniteTransition = rememberInfiniteTransition(label = "HeroPulse")
        val wavePhase by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "HeroWavePhase"
        )
        val breathingScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.035f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "HeroBreathing"
        )

        val effectiveScale = playScale * if (isPlaying && !isPlayPressed) breathingScale else 1f

        // Кастомная морфирующаяся форма M3 Expressive:
        // При morph = 0 -> гладкий сквиркл/пилюля, при morph = 1 -> живая многолепестковая форма (Expressive Cookie/Scallop)
        val morphingShape = remember(organicMorphProgress, wavePhase) {
            GenericShape { size, _ ->
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f
                val rx = w / 2f
                val ry = h / 2f

                // Базовый суперэллипс (сквиркл) с наложенной гармоникой 8 лепестков при воспроизведении
                val steps = 120
                val lobeCount = 8
                val maxWaveDepth = min(w, h) * 0.045f * organicMorphProgress

                for (i in 0..steps) {
                    val t = (i.toFloat() / steps) * (2f * PI).toFloat()
                    val cosT = cos(t)
                    val sinT = sin(t)

                    // Показатель сквиркла: от капсулы/сквиркла к мягкому контуру
                    val ripple = 1f - (maxWaveDepth / min(rx, ry)) * (0.5f + 0.5f * cos(lobeCount * t - wavePhase))

                    // Скругленный прямоугольник в полярном приближении + волна
                    val x = cx + (rx * 0.96f * cosT) * ripple
                    val y = cy + (ry * 0.96f * sinT) * ripple

                    if (i == 0) {
                        moveTo(x, y)
                    } else {
                        lineTo(x, y)
                    }
                }
                close()
            }
        }

        // Базовая форма пилюли/сквиркла, когда морфинг выключен, или плавный переход
        val pauseCorner by animateDpAsState(
            targetValue = when {
                isPlayPressed -> 20.dp
                isPlaying -> 26.dp
                else -> 33.dp
            },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "PauseCorner"
        )

        val activeShape = if (organicMorphProgress > 0.05f) {
            morphingShape
        } else {
            RoundedCornerShape(pauseCorner)
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .drawBehind {
                    // Мягкое пульсирующее гало цвета обложки вокруг главной кнопки во время игры
                    if (organicMorphProgress > 0.01f) {
                        drawCircle(
                            color = primaryAccent.copy(alpha = 0.22f * organicMorphProgress),
                            radius = (size.maxDimension / 2f) * (1.02f + 0.06f * sin(wavePhase))
                        )
                    }
                }
        ) {
            Surface(
                onClick = onPlayPauseClick,
                interactionSource = playInteraction,
                shape = activeShape,
                color = primaryAccent,
                shadowElevation = 14.dp,
                modifier = Modifier
                    .width(heroWidth)
                    .height(heroHeight)
                    .graphicsLayer {
                        scaleX = effectiveScale
                        scaleY = effectiveScale
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
                            (fadeIn(tween(220)) + scaleIn(initialScale = 0.65f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)))
                                .togetherWith(fadeOut(tween(150)) + scaleOut(targetScale = 0.65f))
                        },
                        label = "PlayPauseIconMorph"
                    ) { playing ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                tint = onPrimaryAccent,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (playing) "Pause" else "Play",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onPrimaryAccent
                            )
                        }
                    }
                }
            }
        }

        // 3. Асимметричная лепестковая кнопка Next с пружинной физикой
        val nextInteraction = remember { MutableInteractionSource() }
        val isNextPressed by nextInteraction.collectIsPressedAsState()

        val nextScale by animateFloatAsState(
            targetValue = if (isNextPressed) 0.86f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "NextScale"
        )
        val nextOuterCorner by animateDpAsState(
            targetValue = if (isNextPressed) 18.dp else 32.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "NextOuterCorner"
        )
        val nextInnerCorner by animateDpAsState(
            targetValue = if (isNextPressed) 26.dp else if (isPlaying) 20.dp else 14.dp,
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
            color = Color.White.copy(alpha = if (isNextPressed) 0.22f else 0.13f),
            modifier = Modifier
                .size(width = 68.dp, height = 62.dp)
                .graphicsLayer {
                    scaleX = nextScale
                    scaleY = nextScale
                }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

package com.sona.player.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ArtworkPalette(
    val primaryAccent: Color,
    val onPrimaryAccent: Color,
    val secondaryAccent: Color,
    val containerAccent: Color
)

@Composable
fun rememberArtworkPalette(albumArtUri: Uri?): ArtworkPalette {
    val context = LocalContext.current
    val defaultPrimary = MaterialTheme.colorScheme.primary
    val defaultSecondary = MaterialTheme.colorScheme.tertiary
    val defaultContainer = MaterialTheme.colorScheme.primaryContainer

    var rawPrimary by remember { mutableStateOf(defaultPrimary) }
    var rawSecondary by remember { mutableStateOf(defaultSecondary) }
    var rawContainer by remember { mutableStateOf(defaultContainer) }

    LaunchedEffect(albumArtUri) {
        if (albumArtUri == null) {
            rawPrimary = defaultPrimary
            rawSecondary = defaultSecondary
            rawContainer = defaultContainer
            return@LaunchedEffect
        }

        val extracted = extractColorsFromUri(context, albumArtUri)
        if (extracted != null) {
            rawPrimary = extracted.first
            rawSecondary = extracted.second
            rawContainer = extracted.third
        } else {
            rawPrimary = defaultPrimary
            rawSecondary = defaultSecondary
            rawContainer = defaultContainer
        }
    }

    val animatedPrimary by animateColorAsState(
        targetValue = rawPrimary,
        animationSpec = tween(durationMillis = 650),
        label = "PrimaryAccentAnimation"
    )
    val animatedSecondary by animateColorAsState(
        targetValue = rawSecondary,
        animationSpec = tween(durationMillis = 650),
        label = "SecondaryAccentAnimation"
    )
    val animatedContainer by animateColorAsState(
        targetValue = rawContainer,
        animationSpec = tween(durationMillis = 650),
        label = "ContainerAccentAnimation"
    )

    val onPrimary = if (animatedPrimary.luminance() > 0.38f) {
        Color(0xFF121017)
    } else {
        Color.White
    }

    return ArtworkPalette(
        primaryAccent = animatedPrimary,
        onPrimaryAccent = onPrimary,
        secondaryAccent = animatedSecondary,
        containerAccent = animatedContainer
    )
}

private suspend fun extractColorsFromUri(
    context: Context,
    uri: Uri
): Triple<Color, Color, Color>? = withContext(Dispatchers.IO) {
    try {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(64, 64)
            .scale(Scale.FILL)
            .allowHardware(false)
            .build()

        val result = context.imageLoader.execute(request)
        val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?: return@withContext null

        extractExpressivePalette(bitmap)
    } catch (e: Exception) {
        null
    }
}

private fun extractExpressivePalette(bitmap: Bitmap): Triple<Color, Color, Color> {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    // Bucket hues into 12 bins (30 degrees each) weighted by saturation & brightness
    val hueWeights = FloatArray(12)
    val hueSumR = FloatArray(12)
    val hueSumG = FloatArray(12)
    val hueSumB = FloatArray(12)

    val hsv = FloatArray(3)
    var fallbackR = 0f
    var fallbackG = 0f
    var fallbackB = 0f
    var totalPixels = 0

    for (pixel in pixels) {
        val r = (pixel shr 16 and 0xFF)
        val g = (pixel shr 8 and 0xFF)
        val b = (pixel and 0xFF)

        fallbackR += r
        fallbackG += g
        fallbackB += b
        totalPixels++

        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        // Prefer vibrant, non-extreme dark/white pixels
        if (sat > 0.18f && value in 0.15f..0.96f) {
            val bin = ((hue / 30f).toInt()).coerceIn(0, 11)
            val weight = sat * sat * value
            hueWeights[bin] += weight
            hueSumR[bin] += r * weight
            hueSumG[bin] += g * weight
            hueSumB[bin] += b * weight
        }
    }

    var bestBin = -1
    var bestWeight = 0f
    for (i in 0 until 12) {
        if (hueWeights[i] > bestWeight) {
            bestWeight = hueWeights[i]
            bestBin = i
        }
    }

    if (bestBin != -1 && bestWeight > 0f) {
        val avgR = (hueSumR[bestBin] / bestWeight).toInt().coerceIn(0, 255)
        val avgG = (hueSumG[bestBin] / bestWeight).toInt().coerceIn(0, 255)
        val avgB = (hueSumB[bestBin] / bestWeight).toInt().coerceIn(0, 255)

        android.graphics.Color.RGBToHSV(avgR, avgG, avgB, hsv)

        // Primary Expressive Pastel/Vibrant Accent (high lightness & rich saturation for dark background)
        val primaryHsv = floatArrayOf(
            hsv[0],
            hsv[1].coerceIn(0.42f, 0.72f),
            hsv[2].coerceIn(0.82f, 0.96f)
        )
        val primaryColor = Color(android.graphics.Color.HSVToColor(primaryHsv))

        // Secondary Automix Accent (shifted hue by +45 degrees for expressive contrast)
        val secondaryHsv = floatArrayOf(
            (hsv[0] + 48f) % 360f,
            (hsv[1] * 1.1f).coerceIn(0.55f, 0.85f),
            0.95f
        )
        val secondaryColor = Color(android.graphics.Color.HSVToColor(secondaryHsv))

        // Container Accent (deeper toned container)
        val containerHsv = floatArrayOf(
            hsv[0],
            hsv[1].coerceIn(0.45f, 0.75f),
            0.38f
        )
        val containerColor = Color(android.graphics.Color.HSVToColor(containerHsv))

        return Triple(primaryColor, secondaryColor, containerColor)
    } else {
        // Monochrome / B&W cover art fallback (like in the reference screenshot!)
        val avgLuma = ((fallbackR + fallbackG + fallbackB) / (totalPixels * 3f * 255f)).coerceIn(0f, 1f)
        val primaryColor = if (avgLuma > 0.5f) Color(0xFFD6E2FF) else Color(0xFFC4D3FF)
        val secondaryColor = Color(0xFFFFB4A9)
        val containerColor = Color(0xFF2B354D)
        return Triple(primaryColor, secondaryColor, containerColor)
    }
}

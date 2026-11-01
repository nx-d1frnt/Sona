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
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Holds a complete Material 3 Monet Tonal Palette generated from the current song's cover art.
 */
data class ArtworkPalette(
    val primaryAccent: Color,           // Monet Primary Tone 80
    val onPrimaryAccent: Color,         // Monet OnPrimary Tone 20
    val primaryContainer: Color,        // Monet PrimaryContainer Tone 30
    val onPrimaryContainer: Color,      // Monet OnPrimaryContainer Tone 90
    val secondaryAccent: Color,         // Monet Tertiary Tone 80 (Expressive Automix Accent)
    val secondaryContainer: Color,      // Monet TertiaryContainer Tone 30
    val surfaceContainerTint: Color     // Monet NeutralVariant Tone 17
)

@Composable
fun rememberArtworkPalette(albumArtUri: Uri?): ArtworkPalette {
    val context = LocalContext.current

    val fallbackPrimary = MaterialTheme.colorScheme.primary
    val fallbackOnPrimary = MaterialTheme.colorScheme.onPrimary
    val fallbackPrimaryContainer = MaterialTheme.colorScheme.primaryContainer
    val fallbackOnPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val fallbackTertiary = MaterialTheme.colorScheme.tertiary
    val fallbackTertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer
    val fallbackSurface = MaterialTheme.colorScheme.surfaceContainer

    var targetPrimary by remember { mutableStateOf(fallbackPrimary) }
    var targetOnPrimary by remember { mutableStateOf(fallbackOnPrimary) }
    var targetPrimaryContainer by remember { mutableStateOf(fallbackPrimaryContainer) }
    var targetOnPrimaryContainer by remember { mutableStateOf(fallbackOnPrimaryContainer) }
    var targetSecondary by remember { mutableStateOf(fallbackTertiary) }
    var targetSecondaryContainer by remember { mutableStateOf(fallbackTertiaryContainer) }
    var targetSurface by remember { mutableStateOf(fallbackSurface) }

    LaunchedEffect(albumArtUri) {
        if (albumArtUri == null) {
            targetPrimary = fallbackPrimary
            targetOnPrimary = fallbackOnPrimary
            targetPrimaryContainer = fallbackPrimaryContainer
            targetOnPrimaryContainer = fallbackOnPrimaryContainer
            targetSecondary = fallbackTertiary
            targetSecondaryContainer = fallbackTertiaryContainer
            targetSurface = fallbackSurface
            return@LaunchedEffect
        }

        val scheme = extractMonetSchemeFromUri(context, albumArtUri)
        if (scheme != null) {
            targetPrimary = Color(scheme.primary)
            targetOnPrimary = Color(scheme.onPrimary)
            targetPrimaryContainer = Color(scheme.primaryContainer)
            targetOnPrimaryContainer = Color(scheme.onPrimaryContainer)
            targetSecondary = Color(scheme.tertiary)
            targetSecondaryContainer = Color(scheme.tertiaryContainer)
            targetSurface = Color(scheme.surfaceContainer)
        }
    }

    val spec = tween<Color>(durationMillis = 650)

    val animPrimary by animateColorAsState(targetPrimary, spec, label = "MonetPrimary")
    val animOnPrimary by animateColorAsState(targetOnPrimary, spec, label = "MonetOnPrimary")
    val animPrimaryContainer by animateColorAsState(targetPrimaryContainer, spec, label = "MonetPrimaryContainer")
    val animOnPrimaryContainer by animateColorAsState(targetOnPrimaryContainer, spec, label = "MonetOnPrimaryContainer")
    val animSecondary by animateColorAsState(targetSecondary, spec, label = "MonetSecondary")
    val animSecondaryContainer by animateColorAsState(targetSecondaryContainer, spec, label = "MonetSecondaryContainer")
    val animSurface by animateColorAsState(targetSurface, spec, label = "MonetSurface")

    return ArtworkPalette(
        primaryAccent = animPrimary,
        onPrimaryAccent = animOnPrimary,
        primaryContainer = animPrimaryContainer,
        onPrimaryContainer = animOnPrimaryContainer,
        secondaryAccent = animSecondary,
        secondaryContainer = animSecondaryContainer,
        surfaceContainerTint = animSurface
    )
}

private suspend fun extractMonetSchemeFromUri(
    context: Context,
    uri: Uri
): MonetColorEngine.MonetScheme? = withContext(Dispatchers.IO) {
    try {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(64, 64)
            .scale(Scale.FILL)
            .allowHardware(false)
            .build()

        val result = context.imageLoader.execute(request)
        val bitmap: Bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            ?: return@withContext null

        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val seedArgb = MonetColorEngine.extractSeedColor(pixels)
        MonetColorEngine.generateDarkExpressiveScheme(seedArgb)
    } catch (e: Exception) {
        null
    }
}

package com.sona.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sona.player.R

enum class SettingsDestination {
    HUB,
    AUTOMIX,
    LIBRARY,
    AUDIO_PLAYBACK,
    APPEARANCE,
    ABOUT
}

@Composable
fun SettingsHubScreen(
    onNavigate: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ExpressiveCategoryCard(
            title = stringResource(R.string.settings_cat_automix_title),
            description = stringResource(R.string.settings_cat_automix_desc),
            icon = Icons.Rounded.AutoAwesome,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { onNavigate(SettingsDestination.AUTOMIX) }
        )

        ExpressiveCategoryCard(
            title = stringResource(R.string.settings_cat_library_title),
            description = stringResource(R.string.settings_cat_library_desc),
            icon = Icons.Rounded.FolderSpecial,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            iconContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = { onNavigate(SettingsDestination.LIBRARY) }
        )

        ExpressiveCategoryCard(
            title = stringResource(R.string.settings_cat_playback_title),
            description = stringResource(R.string.settings_cat_playback_desc),
            icon = Icons.Rounded.Headphones,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            iconContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            onClick = { onNavigate(SettingsDestination.AUDIO_PLAYBACK) }
        )

        ExpressiveCategoryCard(
            title = stringResource(R.string.settings_cat_appearance_title),
            description = stringResource(R.string.settings_cat_appearance_desc),
            icon = Icons.Rounded.Palette,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            iconContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            iconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = { onNavigate(SettingsDestination.APPEARANCE) }
        )

        ExpressiveCategoryCard(
            title = stringResource(R.string.settings_cat_about_title),
            description = stringResource(R.string.settings_cat_about_desc),
            icon = Icons.Rounded.Info,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            iconContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            iconContentColor = MaterialTheme.colorScheme.onSurface,
            onClick = { onNavigate(SettingsDestination.ABOUT) }
        )
    }
}

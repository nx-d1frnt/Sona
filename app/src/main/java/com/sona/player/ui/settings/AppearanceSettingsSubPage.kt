package com.sona.player.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sona.player.R
import com.sona.player.data.repository.ThemeMode
import com.sona.player.data.repository.UserSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsSubPage(
    settings: UserSettings,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateUseDynamicMonet: (Boolean) -> Unit,
    onUpdateUseArtworkPalette: (Boolean) -> Unit,
    onUpdateShowPlayerDiagnostics: (Boolean) -> Unit,
    onUpdateExpressiveAnimations: (Boolean) -> Unit
) {
    val modes = listOf(
        ThemeMode.SYSTEM to (stringResource(R.string.settings_appearance_theme_system) to Icons.Rounded.SettingsBrightness),
        ThemeMode.LIGHT to (stringResource(R.string.settings_appearance_theme_light) to Icons.Rounded.LightMode),
        ThemeMode.DARK to (stringResource(R.string.settings_appearance_theme_dark) to Icons.Rounded.DarkMode)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Theme Mode Segmented Button
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_appearance_theme_title)) {
            Surface(
                shape = connectedShapeFor(ConnectedPosition.Single),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveTonalIconBadge(
                            icon = Icons.Rounded.Palette,
                            contentDescription = null,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_appearance_theme_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Choose default look and feel",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        modes.forEachIndexed { index, (mode, pair) ->
                            val (label, icon) = pair
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { onUpdateThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                icon = {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize)
                                    )
                                }
                            ) {
                                Text(label, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        // Color & Palette Group
        ExpressiveSettingsGroup(title = "Color Engines") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ExpressiveSwitchItem(
                    title = stringResource(R.string.settings_appearance_monet_title),
                    subtitle = stringResource(R.string.settings_appearance_monet_desc),
                    icon = Icons.Rounded.ColorLens,
                    checked = settings.useDynamicMonet,
                    onCheckedChange = onUpdateUseDynamicMonet,
                    position = ConnectedPosition.Top
                )
            }

            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_appearance_artwork_title),
                subtitle = stringResource(R.string.settings_appearance_artwork_desc),
                icon = Icons.Rounded.Palette,
                checked = settings.useArtworkPalette,
                onCheckedChange = onUpdateUseArtworkPalette,
                position = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ConnectedPosition.Middle else ConnectedPosition.Top
            )

            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_appearance_expressive_title),
                subtitle = stringResource(R.string.settings_appearance_expressive_desc),
                icon = Icons.Rounded.Animation,
                checked = settings.expressiveAnimations,
                onCheckedChange = onUpdateExpressiveAnimations,
                position = ConnectedPosition.Bottom
            )

        }

        ExpressiveSettingsGroup(title = stringResource(R.string.now_playing_title)) {
            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_player_diagnostics_title),
                subtitle = stringResource(R.string.settings_player_diagnostics_desc),
                icon = Icons.Rounded.BugReport,
                checked = settings.showPlayerDiagnostics,
                onCheckedChange = onUpdateShowPlayerDiagnostics,
                position = ConnectedPosition.Single
            )
        }
    }
}

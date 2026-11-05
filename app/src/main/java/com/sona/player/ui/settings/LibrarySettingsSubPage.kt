package com.sona.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sona.player.R
import com.sona.player.data.model.Song
import com.sona.player.data.repository.UserSettings
import java.io.File

data class ScannedFolder(
    val path: String,
    val name: String,
    val trackCount: Int
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibrarySettingsSubPage(
    settings: UserSettings,
    allSongs: List<Song>,
    onToggleFolderBlacklist: (String, Boolean) -> Unit,
    onUpdateArtistSeparators: (List<String>) -> Unit,
    onUpdateFetchOnlineLyrics: (Boolean) -> Unit = {},
    onUpdateLyricsWifiOnly: (Boolean) -> Unit = {}
) {
    var showAddFolderDialog by remember { mutableStateOf(false) }
    var customFolderPath by remember { mutableStateOf("") }
    var customSeparatorInput by remember { mutableStateOf("") }

    // Derive scanned folders from raw songs
    val scannedFolders = remember(allSongs) {
        allSongs.mapNotNull { song ->
            val path = song.filePath
            if (path.isNotBlank()) {
                val parent = File(path).parent
                if (!parent.isNullOrBlank()) parent else null
            } else null
        }
            .groupingBy { it }
            .eachCount()
            .map { (path, count) ->
                val name = File(path).name.ifBlank { path }
                ScannedFolder(path = path, name = name, trackCount = count)
            }
            .sortedBy { it.name.lowercase() }
    }

    val defaultPresets = listOf(", ", " & ", " feat. ", " ft. ", " / ", "; ")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Synchronized Lyrics Settings Group
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_cat_lyrics_title)) {
            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_lyrics_online_title),
                subtitle = stringResource(R.string.settings_lyrics_online_desc),
                icon = Icons.Rounded.Language,
                checked = settings.fetchOnlineLyrics,
                onCheckedChange = onUpdateFetchOnlineLyrics,
                position = if (settings.fetchOnlineLyrics) ConnectedPosition.Top else ConnectedPosition.Single
            )

            if (settings.fetchOnlineLyrics) {
                ExpressiveSwitchItem(
                    title = stringResource(R.string.settings_lyrics_wifi_only_title),
                    subtitle = stringResource(R.string.settings_lyrics_wifi_only_desc),
                    icon = Icons.Rounded.Wifi,
                    checked = settings.lyricsWifiOnly,
                    onCheckedChange = onUpdateLyricsWifiOnly,
                    position = ConnectedPosition.Bottom
                )
            }
        }

        // Blacklist Folders Group
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_library_blacklist_title)) {
            Surface(
                shape = connectedShapeFor(ConnectedPosition.Top),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ExpressiveTonalIconBadge(
                        icon = Icons.Rounded.FolderOff,
                        contentDescription = null,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_library_blacklist_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_library_blacklist_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (scannedFolders.isEmpty()) {
                Surface(
                    shape = connectedShapeFor(ConnectedPosition.Middle),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.settings_library_no_folders),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
            } else {
                scannedFolders.forEachIndexed { index, folder ->
                    val isBlacklisted = settings.blacklistedFolders.contains(folder.path)
                    val pos = ConnectedPosition.Middle

                    ExpressiveSwitchItem(
                        title = folder.name,
                        subtitle = "${folder.path} • ${stringResource(R.string.settings_library_folder_track_count, folder.trackCount)}",
                        icon = Icons.Rounded.Folder,
                        checked = !isBlacklisted, // checked = included in library
                        onCheckedChange = { included ->
                            onToggleFolderBlacklist(folder.path, !included)
                        },
                        position = pos,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                }
            }

            // Bottom action: Add custom folder
            Surface(
                shape = connectedShapeFor(ConnectedPosition.Bottom),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = { showAddFolderDialog = true }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_library_add_folder))
                    }
                }
            }
        }

        // Artist Name Separators Group
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_library_separators_title)) {
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
                            icon = Icons.Rounded.Person,
                            contentDescription = null,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_library_separators_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.settings_library_separators_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Active & Preset Separators",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Presets
                        defaultPresets.forEach { preset ->
                            val isSelected = settings.artistSeparators.contains(preset)
                            ElevatedFilterChip(
                                selected = isSelected,
                                onClick = {
                                    val updated = settings.artistSeparators.toMutableList()
                                    if (isSelected) {
                                        updated.remove(preset)
                                    } else {
                                        updated.add(preset)
                                    }
                                    onUpdateArtistSeparators(updated)
                                },
                                label = {
                                    Text(
                                        text = preset.replace(" ", "␣"),
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                                        )
                                    }
                                } else null
                            )
                        }

                        // Custom separators not in default presets
                        settings.artistSeparators.filterNot { defaultPresets.contains(it) }.forEach { customSep ->
                            InputChip(
                                selected = true,
                                onClick = {},
                                label = { Text(customSep) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val updated = settings.artistSeparators.toMutableList()
                                            updated.remove(customSep)
                                            onUpdateArtistSeparators(updated)
                                        },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(Icons.Rounded.Clear, contentDescription = "Remove $customSep")
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Custom separator text input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customSeparatorInput,
                            onValueChange = { customSeparatorInput = it },
                            placeholder = { Text(stringResource(R.string.settings_library_custom_separator_hint)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                val trimmed = customSeparatorInput.trim()
                                if (trimmed.isNotEmpty() && !settings.artistSeparators.contains(trimmed)) {
                                    val updated = settings.artistSeparators.toMutableList()
                                    updated.add(trimmed)
                                    onUpdateArtistSeparators(updated)
                                    customSeparatorInput = ""
                                }
                            },
                            enabled = customSeparatorInput.isNotBlank()
                        ) {
                            Text(stringResource(R.string.settings_library_add_separator))
                        }
                    }
                }
            }
        }
    }

    if (showAddFolderDialog) {
        AlertDialog(
            onDismissRequest = { showAddFolderDialog = false },
            title = { Text(stringResource(R.string.settings_library_folder_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_library_blacklist_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customFolderPath,
                        onValueChange = { customFolderPath = it },
                        placeholder = { Text(stringResource(R.string.settings_library_folder_dialog_hint)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = customFolderPath.trim()
                        if (path.isNotEmpty()) {
                            onToggleFolderBlacklist(path, true)
                            customFolderPath = ""
                            showAddFolderDialog = false
                        }
                    },
                    enabled = customFolderPath.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFolderDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

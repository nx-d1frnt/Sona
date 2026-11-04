package com.sona.player.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.core.content.ContextCompat
import com.sona.player.R
import com.sona.player.analysis.LibraryAnalysisState
import com.sona.player.data.repository.UserSettings

@Composable
fun AutomixSettingsSubPage(
    settings: UserSettings,
    analysisState: LibraryAnalysisState,
    canAnalyze: Boolean,
    onUpdateTransitionDuration: (Int) -> Unit,
    onUpdateHarmonicMixingStrict: (Boolean) -> Unit,
    onUpdateAvoidVocalClash: (Boolean) -> Unit,
    onUpdateEnergyMatching: (Boolean) -> Unit,
    onStartAnalysis: (Boolean) -> Unit,
    onCancelAnalysis: () -> Unit,
    context: Context,
    notificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>?
) {
    var forceAnalysis by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Library Analysis Card
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_library_analysis_title)) {
            Surface(
                shape = connectedShapeFor(ConnectedPosition.Single),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveTonalIconBadge(
                            icon = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_library_analysis_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.settings_library_analysis_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.settings_library_analysis_background_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!analysisState.running) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    androidx.compose.material3.Checkbox(
                                        checked = forceAnalysis,
                                        onCheckedChange = { forceAnalysis = it }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.settings_force_analysis_checkbox),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                if (forceAnalysis) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.settings_force_analysis_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    if (analysisState.message.isNotEmpty()) {
                        Text(
                            text = analysisState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    if (analysisState.total > 0) {
                        val progressFraction = analysisState.completed.toFloat() / analysisState.total.coerceAtLeast(1)
                        val statusText = when {
                            analysisState.running && analysisState.waitingForPlayback -> stringResource(R.string.analysis_waiting_for_playback)
                            analysisState.running -> stringResource(R.string.analysis_in_progress, analysisState.title)
                            analysisState.cancelled -> stringResource(R.string.analysis_cancelled)
                            else -> stringResource(R.string.analysis_completed)
                        }

                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = stringResource(R.string.analysis_progress_count, analysisState.completed, analysisState.total),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (analysisState.running) {
                            FilledTonalButton(
                                onClick = onCancelAnalysis,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.analysis_stop))
                            }
                        }

                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                    notificationPermissionLauncher?.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    onStartAnalysis(forceAnalysis)
                                }
                            },
                            enabled = canAnalyze && !analysisState.running
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (forceAnalysis) stringResource(R.string.analysis_recalculate_all)
                                else stringResource(R.string.analysis_start)
                            )
                        }
                    }

                    if (!canAnalyze) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.analysis_need_library_access),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // Transition Duration
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_automix_duration_title)) {
            ExpressiveSliderItem(
                title = stringResource(R.string.settings_automix_duration_title),
                subtitle = stringResource(R.string.settings_automix_duration_desc),
                icon = Icons.Rounded.Tune,
                value = settings.transitionDurationSeconds.toFloat(),
                valueRange = 4f..16f,
                steps = 11,
                valueLabel = stringResource(R.string.settings_automix_duration_value, settings.transitionDurationSeconds),
                onValueChange = { onUpdateTransitionDuration(it.toInt()) },
                position = ConnectedPosition.Single
            )
        }

        // DJ Intelligence Group
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_cat_automix_title)) {
            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_automix_harmonic_title),
                subtitle = stringResource(R.string.settings_automix_harmonic_desc),
                icon = Icons.Rounded.GraphicEq,
                checked = settings.harmonicMixingStrict,
                onCheckedChange = onUpdateHarmonicMixingStrict,
                position = ConnectedPosition.Top
            )

            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_automix_vocal_clash_title),
                subtitle = stringResource(R.string.settings_automix_vocal_clash_desc),
                icon = Icons.Rounded.RecordVoiceOver,
                checked = settings.avoidVocalClash,
                onCheckedChange = onUpdateAvoidVocalClash,
                position = ConnectedPosition.Middle
            )

            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_automix_energy_title),
                subtitle = stringResource(R.string.settings_automix_energy_desc),
                icon = Icons.Rounded.Bolt,
                checked = settings.energyMatching,
                onCheckedChange = onUpdateEnergyMatching,
                position = ConnectedPosition.Bottom
            )
        }
    }
}

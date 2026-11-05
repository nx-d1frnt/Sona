package com.sona.player.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.sona.player.R
import com.sona.player.analysis.LibraryAnalysisState
import com.sona.player.data.model.Song
import com.sona.player.data.repository.ThemeMode
import com.sona.player.data.repository.UserSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: UserSettings,
    allSongs: List<Song>,
    analysisState: LibraryAnalysisState,
    canAnalyze: Boolean,
    onClose: () -> Unit,
    onUpdateTransitionDuration: (Int) -> Unit,
    onUpdateAutomixPlaylistSize: (Int) -> Unit = {},
    onUpdateHarmonicMixingStrict: (Boolean) -> Unit,
    onUpdateAvoidVocalClash: (Boolean) -> Unit,
    onUpdateEnergyMatching: (Boolean) -> Unit,
    onUpdateAutoAnalyzeOnChargingIdle: (Boolean) -> Unit,
    onToggleFolderBlacklist: (String, Boolean) -> Unit,
    onUpdateArtistSeparators: (List<String>) -> Unit,
    onUpdateGaplessPlayback: (Boolean) -> Unit,
    onUpdateAudioFocusDucking: (Boolean) -> Unit,
    onUpdatePauseOnHeadsetDisconnect: (Boolean) -> Unit,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdateUseDynamicMonet: (Boolean) -> Unit,
    onUpdateUseArtworkPalette: (Boolean) -> Unit,
    onUpdateShowPlayerDiagnostics: (Boolean) -> Unit,
    onUpdateExpressiveAnimations: (Boolean) -> Unit,
    onUpdateFetchOnlineLyrics: (Boolean) -> Unit = {},
    onUpdateLyricsWifiOnly: (Boolean) -> Unit = {},
    onResetSettings: () -> Unit,
    onStartAnalysis: (Boolean) -> Unit,
    onCancelAnalysis: () -> Unit,
    context: Context,
    notificationPermissionLauncher: ManagedActivityResultLauncher<String, Boolean>?,
    modifier: Modifier = Modifier
) {
    var destination by remember { mutableStateOf(SettingsDestination.HUB) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    BackHandler {
        if (destination != SettingsDestination.HUB) {
            destination = SettingsDestination.HUB
        } else {
            onClose()
        }
    }

    val titleText = when (destination) {
        SettingsDestination.HUB -> stringResource(R.string.settings_title)
        SettingsDestination.AUTOMIX -> stringResource(R.string.settings_cat_automix_title)
        SettingsDestination.LIBRARY -> stringResource(R.string.settings_cat_library_title)
        SettingsDestination.AUDIO_PLAYBACK -> stringResource(R.string.settings_cat_playback_title)
        SettingsDestination.APPEARANCE -> stringResource(R.string.settings_cat_appearance_title)
        SettingsDestination.ABOUT -> stringResource(R.string.settings_cat_about_title)
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = titleText,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (destination != SettingsDestination.HUB) {
                                destination = SettingsDestination.HUB
                            } else {
                                onClose()
                            }
                        }
                    ) {
                        if (destination == SettingsDestination.HUB) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isExpressiveMotion = settings.expressiveAnimations

            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    if (isExpressiveMotion) {
                        if (targetState != SettingsDestination.HUB && initialState == SettingsDestination.HUB) {
                            // Forward navigation: Slide in from right
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width / 3 } + fadeOut()
                            )
                        } else {
                            // Back navigation: Slide in from left
                            (slideInHorizontally { width -> -width / 3 } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        }
                    } else {
                        fadeIn().togetherWith(fadeOut())
                    }
                },
                label = "settings_destination_transition"
            ) { target ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (target) {
                        SettingsDestination.HUB -> {
                            SettingsHubScreen(
                                onNavigate = { destination = it }
                            )
                        }
                        SettingsDestination.AUTOMIX -> {
                            AutomixSettingsSubPage(
                                settings = settings,
                                analysisState = analysisState,
                                canAnalyze = canAnalyze,
                                onUpdateTransitionDuration = onUpdateTransitionDuration,
                                onUpdateAutomixPlaylistSize = onUpdateAutomixPlaylistSize,
                                onUpdateHarmonicMixingStrict = onUpdateHarmonicMixingStrict,
                                onUpdateAvoidVocalClash = onUpdateAvoidVocalClash,
                                onUpdateEnergyMatching = onUpdateEnergyMatching,
                                onUpdateAutoAnalyzeOnChargingIdle = onUpdateAutoAnalyzeOnChargingIdle,
                                onStartAnalysis = onStartAnalysis,
                                onCancelAnalysis = onCancelAnalysis,
                                context = context,
                                notificationPermissionLauncher = notificationPermissionLauncher
                            )
                        }
                        SettingsDestination.LIBRARY -> {
                            LibrarySettingsSubPage(
                                settings = settings,
                                allSongs = allSongs,
                                onToggleFolderBlacklist = onToggleFolderBlacklist,
                                onUpdateArtistSeparators = onUpdateArtistSeparators,
                                onUpdateFetchOnlineLyrics = onUpdateFetchOnlineLyrics,
                                onUpdateLyricsWifiOnly = onUpdateLyricsWifiOnly
                            )
                        }
                        SettingsDestination.AUDIO_PLAYBACK -> {
                            AudioPlaybackSettingsSubPage(
                                settings = settings,
                                onUpdateGaplessPlayback = onUpdateGaplessPlayback,
                                onUpdateAudioFocusDucking = onUpdateAudioFocusDucking,
                                onUpdatePauseOnHeadsetDisconnect = onUpdatePauseOnHeadsetDisconnect
                            )
                        }
                        SettingsDestination.APPEARANCE -> {
                            AppearanceSettingsSubPage(
                                settings = settings,
                                onUpdateThemeMode = onUpdateThemeMode,
                                onUpdateUseDynamicMonet = onUpdateUseDynamicMonet,
                                onUpdateUseArtworkPalette = onUpdateUseArtworkPalette,
                                onUpdateShowPlayerDiagnostics = onUpdateShowPlayerDiagnostics,
                                onUpdateExpressiveAnimations = onUpdateExpressiveAnimations
                            )
                        }
                        SettingsDestination.ABOUT -> {
                            AboutSettingsSubPage(
                                onResetSettings = onResetSettings
                            )
                        }
                    }
                }
            }
        }
    }
}

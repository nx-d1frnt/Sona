package com.sona.player.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sona_user_settings")

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

data class UserSettings(
    // Automix settings
    val transitionDurationSeconds: Int = 8,
    val harmonicMixingStrict: Boolean = true,
    val avoidVocalClash: Boolean = true,
    val energyMatching: Boolean = true,

    // Library settings
    val blacklistedFolders: Set<String> = emptySet(),
    val artistSeparators: List<String> = listOf(", ", " & ", " feat. ", " ft. ", " / ", "; "),

    // Audio & Playback settings
    val gaplessPlayback: Boolean = true,
    val audioFocusDucking: Boolean = true,
    val pauseOnHeadsetDisconnect: Boolean = true,

    // Appearance & Theme settings
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicMonet: Boolean = true,
    val useArtworkPalette: Boolean = true,
    val expressiveAnimations: Boolean = true
)

class SettingsRepository(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    object PreferencesKeys {
        val TRANSITION_DURATION = intPreferencesKey("automix_transition_duration")
        val HARMONIC_MIXING_STRICT = booleanPreferencesKey("automix_harmonic_mixing_strict")
        val AVOID_VOCAL_CLASH = booleanPreferencesKey("automix_avoid_vocal_clash")
        val ENERGY_MATCHING = booleanPreferencesKey("automix_energy_matching")

        val BLACKLISTED_FOLDERS = stringSetPreferencesKey("library_blacklisted_folders")
        val ARTIST_SEPARATORS = stringPreferencesKey("library_artist_separators")

        val GAPLESS_PLAYBACK = booleanPreferencesKey("audio_gapless_playback")
        val AUDIO_FOCUS_DUCKING = booleanPreferencesKey("audio_focus_ducking")
        val PAUSE_ON_HEADSET_DISCONNECT = booleanPreferencesKey("audio_pause_on_headset_disconnect")

        val THEME_MODE = stringPreferencesKey("appearance_theme_mode")
        val USE_DYNAMIC_MONET = booleanPreferencesKey("appearance_use_dynamic_monet")
        val USE_ARTWORK_PALETTE = booleanPreferencesKey("appearance_use_artwork_palette")
        val EXPRESSIVE_ANIMATIONS = booleanPreferencesKey("appearance_expressive_animations")
    }

    private val defaultSeparators = listOf(", ", " & ", " feat. ", " ft. ", " / ", "; ")

    val settingsFlow: Flow<UserSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val rawSeparators = prefs[PreferencesKeys.ARTIST_SEPARATORS]
            val separators = if (rawSeparators != null) {
                if (rawSeparators.isEmpty()) emptyList() else rawSeparators.split("\u001F")
            } else {
                defaultSeparators
            }

            val themeModeName = prefs[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name
            val themeMode = try {
                ThemeMode.valueOf(themeModeName)
            } catch (_: Exception) {
                ThemeMode.SYSTEM
            }

            UserSettings(
                transitionDurationSeconds = prefs[PreferencesKeys.TRANSITION_DURATION] ?: 8,
                harmonicMixingStrict = prefs[PreferencesKeys.HARMONIC_MIXING_STRICT] ?: true,
                avoidVocalClash = prefs[PreferencesKeys.AVOID_VOCAL_CLASH] ?: true,
                energyMatching = prefs[PreferencesKeys.ENERGY_MATCHING] ?: true,
                blacklistedFolders = prefs[PreferencesKeys.BLACKLISTED_FOLDERS] ?: emptySet(),
                artistSeparators = separators,
                gaplessPlayback = prefs[PreferencesKeys.GAPLESS_PLAYBACK] ?: true,
                audioFocusDucking = prefs[PreferencesKeys.AUDIO_FOCUS_DUCKING] ?: true,
                pauseOnHeadsetDisconnect = prefs[PreferencesKeys.PAUSE_ON_HEADSET_DISCONNECT] ?: true,
                themeMode = themeMode,
                useDynamicMonet = prefs[PreferencesKeys.USE_DYNAMIC_MONET] ?: true,
                useArtworkPalette = prefs[PreferencesKeys.USE_ARTWORK_PALETTE] ?: true,
                expressiveAnimations = prefs[PreferencesKeys.EXPRESSIVE_ANIMATIONS] ?: true
            )
        }

    val settings: StateFlow<UserSettings> = settingsFlow.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings()
    )

    fun updateTransitionDuration(durationSec: Int) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.TRANSITION_DURATION] = durationSec.coerceIn(4, 16)
            }
        }
    }

    fun updateHarmonicMixingStrict(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.HARMONIC_MIXING_STRICT] = enabled
            }
        }
    }

    fun updateAvoidVocalClash(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.AVOID_VOCAL_CLASH] = enabled
            }
        }
    }

    fun updateEnergyMatching(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.ENERGY_MATCHING] = enabled
            }
        }
    }

    fun setFolderBlacklisted(folderPath: String, blacklisted: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                val current = prefs[PreferencesKeys.BLACKLISTED_FOLDERS]?.toMutableSet() ?: mutableSetOf()
                if (blacklisted) {
                    current.add(folderPath)
                } else {
                    current.remove(folderPath)
                }
                prefs[PreferencesKeys.BLACKLISTED_FOLDERS] = current
            }
        }
    }

    fun updateArtistSeparators(separators: List<String>) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.ARTIST_SEPARATORS] = separators.joinToString("\u001F")
            }
        }
    }

    fun updateGaplessPlayback(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.GAPLESS_PLAYBACK] = enabled
            }
        }
    }

    fun updateAudioFocusDucking(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.AUDIO_FOCUS_DUCKING] = enabled
            }
        }
    }

    fun updatePauseOnHeadsetDisconnect(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.PAUSE_ON_HEADSET_DISCONNECT] = enabled
            }
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.THEME_MODE] = mode.name
            }
        }
    }

    fun updateUseDynamicMonet(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.USE_DYNAMIC_MONET] = enabled
            }
        }
    }

    fun updateUseArtworkPalette(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.USE_ARTWORK_PALETTE] = enabled
            }
        }
    }

    fun updateExpressiveAnimations(enabled: Boolean) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PreferencesKeys.EXPRESSIVE_ANIMATIONS] = enabled
            }
        }
    }

    fun resetToDefaults() {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs.clear()
            }
        }
    }
}

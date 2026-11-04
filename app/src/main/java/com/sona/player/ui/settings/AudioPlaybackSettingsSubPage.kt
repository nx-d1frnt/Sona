package com.sona.player.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sona.player.R
import com.sona.player.data.repository.UserSettings

@Composable
fun AudioPlaybackSettingsSubPage(
    settings: UserSettings,
    onUpdateGaplessPlayback: (Boolean) -> Unit,
    onUpdateAudioFocusDucking: (Boolean) -> Unit,
    onUpdatePauseOnHeadsetDisconnect: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ExpressiveSettingsGroup(title = stringResource(R.string.settings_cat_playback_title)) {
            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_audio_gapless_title),
                subtitle = stringResource(R.string.settings_audio_gapless_desc),
                icon = Icons.Rounded.Repeat,
                checked = settings.gaplessPlayback,
                onCheckedChange = onUpdateGaplessPlayback,
                position = ConnectedPosition.Top
            )

            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_audio_ducking_title),
                subtitle = stringResource(R.string.settings_audio_ducking_desc),
                icon = Icons.AutoMirrored.Rounded.VolumeDown,
                checked = settings.audioFocusDucking,
                onCheckedChange = onUpdateAudioFocusDucking,
                position = ConnectedPosition.Middle
            )

            ExpressiveSwitchItem(
                title = stringResource(R.string.settings_audio_headset_title),
                subtitle = stringResource(R.string.settings_audio_headset_desc),
                icon = Icons.Rounded.Headphones,
                checked = settings.pauseOnHeadsetDisconnect,
                onCheckedChange = onUpdatePauseOnHeadsetDisconnect,
                position = ConnectedPosition.Bottom
            )
        }
    }
}

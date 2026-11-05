package com.sona.player

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.sona.player.data.model.Song
import com.sona.player.data.repository.AudioRepository
import com.sona.player.data.repository.SettingsRepository
import com.sona.player.data.repository.UserLibraryRepository
import com.sona.player.playback.MusicPlayerManager
import com.sona.player.ui.main.MainScreen
import com.sona.player.ui.main.MainViewModel
import com.sona.player.ui.theme.SonaTheme

class MainActivity : ComponentActivity() {

    private lateinit var playerManager: MusicPlayerManager
    private lateinit var viewModel: MainViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.onPermissionGranted()
        }
    }

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.loadSongs()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        configureSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = AudioRepository(applicationContext)
        val userLibraryRepository = UserLibraryRepository(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)
        val lyricsRepository = com.sona.player.lyrics.repository.LyricsRepository.getInstance(applicationContext)
        playerManager = MusicPlayerManager(applicationContext)
        playerManager.init()

        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(repository, userLibraryRepository, playerManager, settingsRepository, lyricsRepository) as T
            }
        })[MainViewModel::class.java]

        checkAndRequestStoragePermission()

        setContent {
            val settings by viewModel.settings.collectAsState()
            SonaTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.useDynamicMonet
            ) {
                MainScreen(
                    viewModel = viewModel,
                    onRequestPermission = { checkAndRequestStoragePermission() },
                    onDeleteSong = { song -> performDeleteSong(song) }
                )
            }
        }
    }

    private fun performDeleteSong(song: Song) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(song.contentUri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                val count = contentResolver.delete(song.contentUri, null, null)
                if (count > 0) {
                    viewModel.loadSongs()
                }
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                    deleteLauncher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                } else {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun checkAndRequestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onPermissionGranted()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            playerManager.release()
        }
    }
}

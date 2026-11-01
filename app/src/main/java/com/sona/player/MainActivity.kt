package com.sona.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sona.player.data.repository.AudioRepository
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = AudioRepository(applicationContext)
        playerManager = MusicPlayerManager(applicationContext)
        playerManager.init()

        viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(repository, playerManager) as T
            }
        })[MainViewModel::class.java]

        checkAndRequestStoragePermission()

        setContent {
            SonaTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestPermission = { checkAndRequestStoragePermission() }
                )
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

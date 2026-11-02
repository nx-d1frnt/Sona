package com.sona.player.analysis

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sona.player.MainActivity
import com.sona.player.data.repository.AudioRepository
import com.sona.player.playback.AutomixPlaybackEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

data class LibraryAnalysisState(
    val running: Boolean = false, val total: Int = 0, val completed: Int = 0,
    val cached: Int = 0, val failed: Int = 0, val title: String = "",
    val waitingForPlayback: Boolean = false, val cancelled: Boolean = false,
    val message: String = ""
)

/** User-started local media processing. Owns its work independently of Activity/ViewModel. */
class LibraryAnalysisService : Service() {
    companion object {
        private const val CHANNEL = "library_analysis"
        private const val NOTIFICATION = 2002
        private const val STOP = "com.sona.player.STOP_ANALYSIS"
        private val mutableState = MutableStateFlow(LibraryAnalysisState())
        val state = mutableState.asStateFlow()
        fun start(context: Context, force: Boolean) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LibraryAnalysisService::class.java)
                    .putExtra("force", force))
            } catch (_: Exception) {
                mutableState.value = LibraryAnalysisState(message = context.getString(com.sona.player.R.string.analysis_error_start_failed))
            }
        }
        fun cancel(context: Context) {
            context.startService(Intent(context, LibraryAnalysisService::class.java).setAction(STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private val prefs by lazy { getSharedPreferences("library_analysis_task", MODE_PRIVATE) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(com.sona.player.R.string.notification_channel_analysis), NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "Sona:LibraryAnalysis").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            finish(cancelled = true)
            return START_NOT_STICKY
        }
        if (intent == null && !prefs.getBoolean("running", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Use the platform API: this project's Core version masks out the newer
                // mediaProcessing type when forwarding through ServiceCompat.
                startForeground(NOTIFICATION, notification(), if (Build.VERSION.SDK_INT >= 35)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else startForeground(NOTIFICATION, notification())
        } catch (_: Exception) {
            finish(message = getString(com.sona.player.R.string.analysis_error_service_failed))
            return START_NOT_STICKY
        }
        if (work?.isActive == true) return START_STICKY
        val resume = intent == null
        val force = if (resume) prefs.getBoolean("force", false) else intent?.getBooleanExtra("force", false) ?: false
        prefs.edit().putBoolean("running", true).putBoolean("force", force).apply()
        mutableState.value = LibraryAnalysisState(running = true)
        work = scope.launch {
            try {
                val engine = AutomixPlaybackEngine.getInstance(applicationContext)
                val songs = AudioRepository(applicationContext).getSongs()
                val totalCount = songs.size
                val initialCached = if (force) 0 else songs.count { !engine.needsLibraryAnalysis(it.id) }
                val completedCount = AtomicInteger(initialCached)
                val cachedCount = AtomicInteger(initialCached)
                val failedCount = AtomicInteger(0)

                mutableState.value = mutableState.value.copy(
                    total = totalCount,
                    completed = initialCached,
                    cached = initialCached
                )
                publish(true)

                val pendingSongs = if (force) songs else songs.filter { engine.needsLibraryAnalysis(it.id) }
                if (pendingSongs.isEmpty()) {
                    engine.notifyLibraryAnalysisProgress(forceImmediate = true)
                    finish()
                    return@launch
                }

                val workerCount = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
                val channel = Channel<com.sona.player.data.model.Song>(Channel.BUFFERED)

                val producer = launch(Dispatchers.Default) {
                    for (song in pendingSongs) {
                        channel.send(song)
                    }
                    channel.close()
                }

                val workers = (0 until workerCount).map {
                    launch(Dispatchers.Default) {
                        for (song in channel) {
                            ensureActive()
                            while (engine.playerState.value.isPlaying || engine.playerState.value.automix.isTransitioning) {
                                releaseWakeLock()
                                mutableState.update { it.copy(waitingForPlayback = true) }
                                publish()
                                delay(1000)
                            }
                            mutableState.update { it.copy(waitingForPlayback = false, title = song.title) }

                            acquireWakeLock()
                            publish()
                            try {
                                engine.analyzeLibrarySong(song, force)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failedCount.incrementAndGet()
                            }

                            val currentCompleted = completedCount.incrementAndGet()
                            mutableState.update {
                                it.copy(
                                    completed = currentCompleted,
                                    cached = cachedCount.get(),
                                    failed = failedCount.get()
                                )
                            }
                            publish()
                        }
                    }
                }

                producer.join()
                workers.joinAll()
                engine.notifyLibraryAnalysisProgress(forceImmediate = true)
                engine.flushPendingAnalysisWrites()
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                finish(message = getString(com.sona.player.R.string.analysis_error_read_failed))
            } finally {
                withContext(NonCancellable) {
                    AutomixPlaybackEngine.getInstance(applicationContext).releaseAnalysisResources()
                }
            }
        }
        return START_STICKY
    }

    private fun notification(): Notification {
        val state = mutableState.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1, Intent(this, LibraryAnalysisService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(com.sona.player.R.string.analysis_notification_title, state.completed, state.total))
            .setContentText(if (state.waitingForPlayback) getString(com.sona.player.R.string.analysis_waiting_for_playback) else state.title.ifEmpty { getString(com.sona.player.R.string.analysis_preparing) })
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
            .setProgress(state.total, state.completed, state.total == 0)
            .addAction(0, getString(com.sona.player.R.string.analysis_stop), cancel).build()
    }

    private fun publish(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastNotificationAt >= 500L) {
            lastNotificationAt = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        }
    }

    private fun acquireWakeLock() { if (wakeLock?.isHeld != true) wakeLock?.acquire(90_000L) }
    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }
    private fun finish(cancelled: Boolean = false, message: String = "") {
        prefs.edit().putBoolean("running", false).remove("done").apply()
        mutableState.value = mutableState.value.copy(running = false, waitingForPlayback = false,
            cancelled = cancelled, message = message)
        work?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        finish(message = getString(com.sona.player.R.string.analysis_timeout))
    }
    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

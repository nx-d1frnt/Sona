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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
                mutableState.value = LibraryAnalysisState(message = "Android не разрешил запуск фонового анализа. Попробуйте снова из настроек.")
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
            NotificationChannel(CHANNEL, "Анализ музыкальной библиотеки", NotificationManager.IMPORTANCE_LOW))
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
            finish(message = "Android не разрешил фоновый анализ. Попробуйте запустить его снова из настроек.")
            return START_NOT_STICKY
        }
        if (work?.isActive == true) return START_STICKY
        val resume = intent == null
        val force = if (resume) prefs.getBoolean("force", false) else intent?.getBooleanExtra("force", false) ?: false
        val done = if (resume) prefs.getStringSet("done", emptySet()).orEmpty().toMutableSet() else mutableSetOf()
        prefs.edit().putBoolean("running", true).putBoolean("force", force).putStringSet("done", done.toSet()).apply()
        mutableState.value = LibraryAnalysisState(running = true)
        work = scope.launch {
            try {
                val engine = AutomixPlaybackEngine.getInstance(applicationContext)
                val songs = AudioRepository(applicationContext).getSongs()
                mutableState.value = mutableState.value.copy(total = songs.size,
                    completed = songs.count { it.id.toString() in done })
                publish(true)
                for (song in songs) {
                    ensureActive()
                    if (song.id.toString() in done) continue
                    mutableState.value = mutableState.value.copy(title = song.title)
                    if (!force && !engine.needsLibraryAnalysis(song.id)) {
                        mutableState.value = mutableState.value.let { it.copy(cached = it.cached + 1) }
                    } else {
                        while (engine.playerState.value.isPlaying || engine.playerState.value.automix.isTransitioning) {
                            releaseWakeLock()
                            mutableState.value = mutableState.value.copy(waitingForPlayback = true)
                            publish()
                            delay(1000)
                        }
                        mutableState.value = mutableState.value.copy(waitingForPlayback = false)
                        // Limit CPU wakefulness to one bounded analysis operation at a time.
                        wakeLock?.acquire(90_000L)
                        publish()
                        try {
                            engine.analyzeLibrarySong(song, force)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.value = mutableState.value.let { it.copy(failed = it.failed + 1) }
                        } finally {
                            releaseWakeLock()
                        }
                    }
                    done.add(song.id.toString())
                    prefs.edit().putStringSet("done", done.toSet()).apply()
                    mutableState.value = mutableState.value.let { it.copy(completed = it.completed + 1) }
                    publish()
                    yield()
                }
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                finish(message = "Не удалось прочитать библиотеку. Проверьте доступ к аудиофайлам и запустите анализ снова.")
            } finally {
                withContext(NonCancellable) { AutomixPlaybackEngine.getInstance(applicationContext).releaseAnalysisResources() }
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
            .setContentTitle("Анализ библиотеки: ${state.completed} из ${state.total}")
            .setContentText(if (state.waitingForPlayback) "Ожидание паузы в воспроизведении" else state.title.ifEmpty { "Подготовка" })
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setSilent(true)
            .setProgress(state.total, state.completed, state.total == 0)
            .addAction(0, "Остановить", cancel).build()
    }

    private fun publish(force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastNotificationAt >= 1000L) {
            lastNotificationAt = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
        }
    }

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
        finish(message = "Android остановил длительную обработку. Готовые результаты сохранены; анализ можно продолжить.")
    }
    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}

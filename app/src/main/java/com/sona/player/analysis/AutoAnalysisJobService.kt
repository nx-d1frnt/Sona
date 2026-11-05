package com.sona.player.analysis

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.sona.player.playback.AutomixPlaybackEngine
import kotlinx.coroutines.*

class AutoAnalysisJobService : JobService() {
    companion object {
        private const val TAG = "AutoAnalysisJobService"
        private var activeJob: Job? = null

        fun cancelActiveJob() {
            activeJob?.cancel()
            activeJob = null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onStartJob(params: JobParameters?): Boolean {
        if (LibraryAnalysisService.isRunning) {
            return false
        }

        val job = scope.launch {
            try {
                val runner = LibraryAnalysisRunner(
                    context = applicationContext,
                    force = false,
                    onProgress = { state -> LibraryAnalysisService.updateState { state } }
                )
                runner.execute()
                LibraryAnalysisService.updateState { it.copy(running = false, etaMillis = null) }
                jobFinished(params, false)
            } catch (cancelled: CancellationException) {
                jobFinished(params, true)
            } catch (e: Exception) {
                Log.e(TAG, "Error in auto analysis job", e)
                jobFinished(params, true)
            } finally {
                withContext(NonCancellable) {
                    LibraryAnalysisService.updateState {
                        it.copy(running = false, waitingForPlayback = false, etaMillis = null)
                    }
                    AutomixPlaybackEngine.getInstance(applicationContext).releaseAnalysisResources()
                }
            }
        }
        activeJob = job
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        activeJob = null
        return true
    }

    override fun onDestroy() {
        activeJob?.cancel()
        activeJob = null
        scope.cancel()
        super.onDestroy()
    }
}

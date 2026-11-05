package com.sona.player.analysis

import android.content.Context
import com.sona.player.R

class AnalysisEtaEstimator(
    private val totalPending: Int,
    private val timeProvider: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private var activeStartTime: Long = timeProvider()
    private var accumulatedActiveMs: Long = 0L
    private var isPaused: Boolean = false
    private var smoothedEtaMs: Double? = null

    @Synchronized
    fun pause() {
        if (!isPaused) {
            accumulatedActiveMs += (timeProvider() - activeStartTime).coerceAtLeast(0L)
            isPaused = true
        }
    }

    @Synchronized
    fun resume() {
        if (isPaused) {
            activeStartTime = timeProvider()
            isPaused = false
        }
    }

    @Synchronized
    fun updateProgress(newlyCompleted: Int): Long? {
        if (totalPending <= 0) return 0L
        val remaining = (totalPending - newlyCompleted).coerceAtLeast(0)
        if (remaining == 0) return 0L
        if (newlyCompleted <= 0) return null

        val currentActiveMs = accumulatedActiveMs + if (!isPaused) {
            (timeProvider() - activeStartTime).coerceAtLeast(0L)
        } else {
            0L
        }

        if (currentActiveMs < 200L) return null

        val msPerTrack = currentActiveMs.toDouble() / newlyCompleted
        val rawEta = remaining * msPerTrack
        val currentSmoothed = smoothedEtaMs
        val smoothed = if (currentSmoothed == null) {
            rawEta
        } else {
            0.3 * rawEta + 0.7 * currentSmoothed
        }
        smoothedEtaMs = smoothed
        return smoothed.toLong().coerceAtLeast(0L)
    }

    @Synchronized
    fun currentEta(): Long? = smoothedEtaMs?.toLong()

    companion object {
        fun formatEta(context: Context, etaMs: Long): String {
            val totalSeconds = (etaMs + 500L) / 1000L
            return when {
                totalSeconds < 60L -> context.getString(R.string.analysis_eta_less_than_minute)
                totalSeconds < 3600L -> {
                    val minutes = ((totalSeconds + 30L) / 60L).toInt().coerceAtLeast(1)
                    context.getString(R.string.analysis_eta_minutes, minutes)
                }
                else -> {
                    val hours = (totalSeconds / 3600L).toInt()
                    val minutes = ((totalSeconds % 3600L + 30L) / 60L).toInt()
                    if (minutes >= 60) {
                        context.getString(R.string.analysis_eta_hours, hours + 1)
                    } else if (minutes == 0) {
                        context.getString(R.string.analysis_eta_hours, hours)
                    } else {
                        context.getString(R.string.analysis_eta_hours_minutes, hours, minutes)
                    }
                }
            }
        }
    }
}

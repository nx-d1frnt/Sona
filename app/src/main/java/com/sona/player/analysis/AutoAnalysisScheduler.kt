package com.sona.player.analysis

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

object AutoAnalysisScheduler {
    private const val TAG = "AutoAnalysisScheduler"
    const val JOB_ID = 2026

    fun schedule(context: Context) {
        try {
            val jobScheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val componentName = ComponentName(context, AutoAnalysisJobService::class.java)
            val builder = JobInfo.Builder(JOB_ID, componentName)
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setPersisted(true)
                .setPeriodic(6 * 60 * 60 * 1000L, 60 * 60 * 1000L)

            val result = jobScheduler.schedule(builder.build())
            Log.d(TAG, "Auto analysis scheduled: result=$result")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule auto analysis", e)
        }
    }

    fun cancel(context: Context) {
        try {
            val jobScheduler = context.getSystemService(JobScheduler::class.java) ?: return
            jobScheduler.cancel(JOB_ID)
            Log.d(TAG, "Auto analysis cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel auto analysis", e)
        }
    }
}

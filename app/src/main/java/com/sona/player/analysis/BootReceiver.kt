package com.sona.player.analysis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sona.player.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val repository = SettingsRepository(context)
            CoroutineScope(Dispatchers.IO).launch {
                val settings = repository.settingsFlow.first()
                if (settings.autoAnalyzeOnChargingIdle) {
                    AutoAnalysisScheduler.schedule(context)
                }
            }
        }
    }
}

package com.shadowpheonix.tradingdashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Credential-encrypted keys are available after unlock, never during direct boot.
// Android does not deliver these broadcasts to a Settings-force-stopped app.
class AlertResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (shouldResumeAlerts(AlertStore(context).enabled(),
                        NotificationManagerCompat.from(context).areNotificationsEnabled(),
                        ConnectionStore(context).load().isNotEmpty())) AlertService.start(context)
            } catch (_: Exception) {
                // Platform restrictions may require opening the app. No alarm loop.
            } finally { result.finish() }
        }
    }
}

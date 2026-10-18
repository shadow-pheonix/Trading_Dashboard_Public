package com.shadowpheonix.tradingdashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Private, immutable delete intents only refresh local notification grouping.
// No network requests, credentials, trading actions or background service starts.
class AlertNotificationDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val connection = intent.getStringExtra("connection") ?: return
        val tag = intent.getStringExtra("tag") ?: return
        AlertNotifications(context).dismissed(connection, tag)
    }
}

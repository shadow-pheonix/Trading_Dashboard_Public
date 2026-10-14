package com.shadowpheonix.tradingdashboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

const val ALERT_CONNECTION_CHANNEL = "alerts_connection"
const val ALERT_SUMMARY_CHANNEL = "alerts_summary"

fun alertChannel(value: AlertPresentation): String = when {
    value.urgent -> "alerts_critical"
    value.category == AlertCategory.Trade -> "alerts_trades_floating"
    value.category == AlertCategory.Risk -> "alerts_risk_floating"
    else -> "alerts_reports_floating"
}

// Channel importance is immutable. New floating channels are needed for existing
// installations, but an explicitly blocked legacy category stays blocked.
fun floatingAlertImportance(legacyImportance: Int?) =
    if (legacyImportance == NotificationManager.IMPORTANCE_NONE) NotificationManager.IMPORTANCE_NONE
    else NotificationManager.IMPORTANCE_HIGH

// Android channels let the user independently control sounds/vibration and urgency.
// All notification intents lead to the locked dashboard; no trading actions are offered.
class AlertNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun channels() {
        listOf(
            NotificationChannel("alerts_critical", "Urgent risk alerts", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("alerts_trades_floating", "Trade entries and exits",
                floatingAlertImportance(manager.getNotificationChannel("alerts_trades")?.importance)),
            NotificationChannel("alerts_risk_floating", "Risk and recovery updates",
                floatingAlertImportance(manager.getNotificationChannel("alerts_risk")?.importance)),
            NotificationChannel("alerts_reports_floating", "Bot status and reports",
                floatingAlertImportance(manager.getNotificationChannel("alerts_reports")?.importance)),
            NotificationChannel(ALERT_SUMMARY_CHANNEL, "Alert summaries", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(ALERT_CONNECTION_CHANNEL, "Background alert connection", NotificationManager.IMPORTANCE_LOW),
        ).forEach { channel ->
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            if (channel.importance == NotificationManager.IMPORTANCE_HIGH) channel.enableVibration(true)
            if (channel.id in setOf(ALERT_CONNECTION_CHANNEL, ALERT_SUMMARY_CHANNEL)) {
                channel.setSound(null, null)
                channel.enableVibration(false)
                channel.setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun clearRoutine(connectionId: String, history: List<DashboardAlert>) {
        val routine = history.filter(::isRoutineEvaluation)
        routine.forEach { manager.cancel("alert:$connectionId:${it.id}", 1) }
        if (routine.isNotEmpty()) manager.cancel("alert-summary:$connectionId", 2)
    }

    private fun intent(connectionId: String, eventId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("${context.packageName}.alert.$connectionId.$eventId")
            .putExtra("alertConnection", connectionId).putExtra("alertEvent", eventId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun post(connectionId: String, instance: String, event: DashboardAlert, recent: List<DashboardAlert>) {
        if (isRoutineEvaluation(event)) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val value = presentAlert(instance, event)
        val group = "instance_alerts_$connectionId"
        val open = intent(connectionId, event.id)
        val public = NotificationCompat.Builder(context, alertChannel(value))
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Trading Dashboard")
            .setContentText("Unlock to view alert details").build()
        val notification = NotificationCompat.Builder(context, alertChannel(value))
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(value.title).setContentText(value.preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(value.details))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(event.timestamp).setShowWhen(true).setGroup(group)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setCategory(if (value.urgent) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_EVENT)
            .build()
        // Distinct tags avoid hash collisions and make reconnect/replay updates idempotent.
        manager.notify("alert:$connectionId:${event.id}", 1, notification)
        val items = recent.takeLast(5)
        val summary = NotificationCompat.InboxStyle()
        items.forEach { summary.addLine(presentAlert(instance, it).title) }
        manager.notify("alert-summary:$connectionId", 2,
            NotificationCompat.Builder(context, ALERT_SUMMARY_CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$instance · Trading alerts").setContentText("${recent.size} recent alerts")
                .setStyle(summary).setGroup(group).setGroupSummary(true).setSilent(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setContentIntent(open)
                .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build())
    }
}

fun openAlertNotificationSettings(context: Context, connectionIndicator: Boolean = false) {
    AlertNotifications(context).channels()
    val intent = Intent(if (connectionIndicator) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (connectionIndicator) intent.putExtra(Settings.EXTRA_CHANNEL_ID, ALERT_CONNECTION_CHANNEL)
    try { context.startActivity(intent) }
    catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

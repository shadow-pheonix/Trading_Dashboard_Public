package com.shadowpheonix.tradingdashboard

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import java.util.Locale

const val ALERT_CONNECTION_CHANNEL = "alerts_connection"
const val ALERT_SUMMARY_CHANNEL = "alerts_summary"
private const val THREAD_PREFIX = "alert-thread:"
private const val PENDING_IDS = "dashboard.alert.pendingIds"
private const val INSTANCE_LABEL = "dashboard.alert.instanceLabel"
private const val SUMMARY_PREVIEW = "dashboard.alert.preview"

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
    private val store = AlertStore(context)

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

    private fun intent(connectionId: String, eventId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("${context.packageName}.alert.$connectionId.${eventId ?: "inbox"}")
            .putExtra("alertConnection", connectionId).putExtra("alertEvent", eventId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun deleteIntent(connectionId: String, tag: String): PendingIntent {
        val intent = Intent(context, AlertNotificationDismissReceiver::class.java)
            .setAction("${context.packageName}.dismiss.$tag")
            .putExtra("connection", connectionId).putExtra("tag", tag)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun avatar(connectionId: String, instance: String): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val palette = intArrayOf(0xff476acc.toInt(), 0xff168174.toInt(), 0xff8054b6.toInt(), 0xffa45e26.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette[Math.floorMod(connectionId.hashCode(), palette.size)] }
        canvas.drawCircle(64f, 64f, 64f, paint)
        paint.color = Color.WHITE
        paint.textSize = 39f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val initials = instance.filter { it.isLetterOrDigit() }.take(3).uppercase(Locale.ROOT).ifEmpty { "TD" }
        canvas.drawText(initials, 64f, 64f - (paint.descent() + paint.ascent()) / 2f, paint)
        return bitmap
    }

    private fun richText(text: String) = SpannableString(text).apply {
        notificationBoldRanges(text).forEach { range ->
            setSpan(StyleSpan(Typeface.BOLD), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun publicVersion(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Trading Dashboard")
        .setContentText("Unlock to view alert details").build()

    fun post(connectionId: String, instance: String, event: DashboardAlert, recent: List<DashboardAlert>) = synchronized(lock) {
        // Gate here, so every event and group summary respects the current saved
        // preference without restarting sockets or interrupting history/cursors.
        if (!store.canNotify(connectionId) || isRoutineEvaluation(event)) return@synchronized
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return@synchronized
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@synchronized
        val value = presentAlert(instance, event)
        val channel = alertChannel(value)
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) {
            pendingCards.remove("$THREAD_PREFIX$connectionId:$channel")
            return@synchronized
        }
        val group = "instance_alerts_$connectionId"
        // Keep Android's category controls intact and critical messages separate:
        // a status report cannot overwrite a pending unprotected-position alert.
        val tag = "$THREAD_PREFIX$connectionId:$channel"
        val existing = activeCards()[tag]
        val pendingIds = existing?.extras?.getStringArrayList(PENDING_IDS).orEmpty()
        val thread = notificationThread(recent, pendingIds, event)
        val image = avatar(connectionId, instance)
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle("$instance · ${notificationTopic(value)}").setGroupConversation(true)
        thread.displayed.forEach { message ->
            val presentation = presentAlert(instance, message)
            val sender = Person.Builder().setName(presentation.title.removePrefix("$instance · "))
                .setKey("$connectionId:${presentation.title}").setBot(true)
                .setIcon(IconCompat.createWithBitmap(image)).build()
            style.addMessage(richText(presentation.details), message.timestamp, sender)
        }
        val open = intent(connectionId, event.id)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(value.title).setContentText(value.preview)
            .setLargeIcon(image).setStyle(style).setSubText(notificationCount(thread.events.size))
            .setColor(if (value.urgent) 0xffc43d4d.toInt() else 0xff476acc.toInt())
            .setContentIntent(open).setDeleteIntent(deleteIntent(connectionId, tag))
            .setAutoCancel(true).setOnlyAlertOnce(!thread.isNew).setNumber(thread.events.size)
            .setAllowSystemGeneratedContextualActions(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(event.timestamp).setShowWhen(true).setGroup(group)
            .setSortKey((Long.MAX_VALUE - event.timestamp).toString().padStart(19, '0'))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion(channel))
            .setCategory(if (value.urgent) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_EVENT)
            .addExtras(Bundle().apply {
                putStringArrayList(PENDING_IDS, ArrayList(thread.events.map { it.id }))
                putString(INSTANCE_LABEL, instance)
                putString(SUMMARY_PREVIEW, "${value.title.removePrefix("$instance · ")} · ${value.preview}")
            })
            .build()
        manager.notify(tag, 1, notification)
        pendingCards.posted(tag, notification)
        manager.cancel("alert:$connectionId:${event.id}", 1) // Replace a legacy replay of this same event.
        updateSummary(connectionId, "alert:$connectionId:${event.id}")
    }

    private fun updateSummary(connectionId: String, excludedTag: String? = null) {
        val cards = activeCards().filterKeys { it != excludedTag &&
            (it.startsWith("$THREAD_PREFIX$connectionId:") || it.startsWith("alert:$connectionId:")) }
            .values.sortedBy { it.`when` }
        val tag = "alert-summary:$connectionId"
        if (cards.size < 2) { manager.cancel(tag, 2); return }
        val instance = cards.last().extras.getString(INSTANCE_LABEL) ?: "Bot alerts"
        val count = cards.sumOf { it.extras.getStringArrayList(PENDING_IDS)?.size ?: 1 }
        val summary = NotificationCompat.InboxStyle()
        cards.takeLast(6).forEach { card ->
            val extras = card.extras
            summary.addLine(extras.getString(SUMMARY_PREVIEW)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
        }
        manager.notify(tag, 2,
            NotificationCompat.Builder(context, ALERT_SUMMARY_CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(instance).setContentText("$count recent alerts · ${cards.size} categories")
                .setStyle(summary).setGroup("instance_alerts_$connectionId").setGroupSummary(true).setSilent(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setContentIntent(intent(connectionId, null))
                .setDeleteIntent(deleteIntent(connectionId, tag)).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion(ALERT_SUMMARY_CHANNEL)).build())
    }

    fun dismissed(connectionId: String, tag: String) = synchronized(lock) {
        if (tag == "alert-summary:$connectionId") dismiss(connectionId)
        else if (tag.startsWith("$THREAD_PREFIX$connectionId:")) {
            pendingCards.remove(tag)
            updateSummary(connectionId, tag)
        }
    }

    private fun activeCards(): Map<String, Notification> {
        val observed = manager.activeNotifications.filter { it.id == 1 && it.tag?.let { tag ->
            tag.startsWith(THREAD_PREFIX) || tag.startsWith("alert:") } == true }
            .associate { it.tag to it.notification }
        return pendingCards.snapshot(observed).filterValues {
            manager.getNotificationChannel(it.channelId)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    // Called only on the authenticated alert page, muting or forgetting an
    // instance. Portfolio/home opening never marks unseen alerts as read.
    fun dismiss(connectionId: String? = null) = synchronized(lock) {
        val submitted = pendingCards.snapshot(emptyMap()).filterKeys { tag ->
            connectionId == null || tag.startsWith("$THREAD_PREFIX$connectionId:")
        }
        submitted.forEach { (tag, card) ->
            manager.cancel(tag, 1)
            pendingCards.remove(tag)
            card.group?.takeIf { it.startsWith("instance_alerts_") }?.removePrefix("instance_alerts_")
                ?.let { manager.cancel("alert-summary:$it", 2) }
        }
        manager.activeNotifications.filter { card ->
            if (connectionId != null) card.tag?.startsWith("$THREAD_PREFIX$connectionId:") == true ||
                card.tag?.startsWith("alert:$connectionId:") == true || card.tag == "alert-summary:$connectionId"
            else card.tag?.let { it.startsWith(THREAD_PREFIX) || it.startsWith("alert:") || it.startsWith("alert-summary:") } == true
        }.forEach { manager.cancel(it.tag, it.id); pendingCards.remove(it.tag) }
        if (connectionId != null) manager.cancel("alert-summary:$connectionId", 2)
    }

    companion object {
        private val lock = Any()
        private val pendingCards = PendingNotificationCards<Notification> {
            it.extras.getStringArrayList(PENDING_IDS).orEmpty()
        }
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

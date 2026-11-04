package com.shadowpheonix.tradingdashboard

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

// A foreground service keeps the event connection alive in the background. It
// never opens the portfolio stream or requests account balances. The alert feed
// optionally includes the server's combined mark P&L for local milestone alerts.
class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receivers: List<Job> = emptyList()
    private var reload: Job? = null
    private var currentConnections: List<Connection> = emptyList()
    private lateinit var store: AlertStore
    private lateinit var notifications: AlertNotifications
    private lateinit var network: AlertNetwork
    private val api = AlertApi()
    override fun onCreate() {
        super.onCreate()
        store = AlertStore(this)
        notifications = AlertNotifications(this)
        notifications.channels()
        network = AlertNetwork(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, ALERT_CONNECTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle("Trading alerts active")
            .setContentText("Listening for trade and P&L alerts · tap to check delivery")
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(901, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(901, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.enabled()) { stopSelf(); return START_NOT_STICKY }
        reload?.cancel()
        reload = scope.launch {
            val connections = try { ConnectionStore(this@AlertService).load() } catch (_: Exception) { emptyList() }
            if (connections.isEmpty()) { stopSelf(); return@launch }
            if (connections == currentConnections && receivers.all { it.isActive }) return@launch
            receivers.forEach { it.cancelAndJoin() }
            currentConnections = connections
            receivers = connections.map { connection -> scope.launch { listen(connection) } }
        }
        return START_STICKY
    }
    private suspend fun listen(connection: Connection) {
        receiveAlerts(network.online,
            waiting = { store.status(connection.id, "Waiting for internet · missed events replay on connection") },
            disconnected = { store.status(connection.id, "Disconnected · retrying; missed events replay on connection") },
        ) { healthy ->
            val previous = store.load(connection.id)
            notifications.clearRoutine(connection.id, previous.history)
            store.status(connection.id, "Connecting")
            var ledger = previous
            // This flag belongs to one socket attempt, never to a saved ledger.
            var pnlContinuous = false
            api.stream(connection, previous.cursor).collect { batch ->
                val delivery = replayAlerts(ledger, batch, pnlContinuous = pnlContinuous)
                // Persist before posting. Bot events can replay after a restart;
                // local price milestones require a fresh, uninterrupted stream.
                if (delivery.notifications.isNotEmpty()) store.save(connection.id,
                    delivery.ledger.copy(pendingNotifications = delivery.notifications))
                delivery.notifications.forEach { event ->
                    notifications.post(connection.id, batch.instance.label, event, delivery.ledger.history)
                }
                if (delivery.notifications.isNotEmpty() || alertCheckpointChanged(ledger, delivery.ledger))
                    store.save(connection.id, delivery.ledger)
                ledger = delivery.ledger
                pnlContinuous = true
                store.status(connection.id, if (batch.status != "ready") "Bot event feed unavailable · server setup required"
                    else if (delivery.ledger.gap) "Connected · older events expired" else "Connected")
                healthy()
            }
        }
    }
    override fun onDestroy() { scope.cancel(); network.close(); api.close(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
        fun stop(context: Context) { context.stopService(Intent(context, AlertService::class.java)) }
    }
}

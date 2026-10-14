package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlin.random.Random

data class AlertNetworkState(val online: Boolean, val networkId: Long = 0)

fun alertRetryDelay(failures: Int, fraction: Double = Random.nextDouble()): Long {
    require(failures > 0 && fraction in 0.0..1.0)
    val maximum = (1000L shl failures.coerceAtMost(7)).coerceAtMost(120_000L)
    return (maximum * (0.8 + 0.2 * fraction)).toLong()
}

// A network transition cancels both the socket and its retry timer. Offline
// phones do no reconnect work; persisted cursors recover events after a gap.
suspend fun receiveAlerts(
    online: Flow<AlertNetworkState>,
    waiting: () -> Unit,
    disconnected: () -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
    connect: suspend (healthy: () -> Unit) -> Unit,
) {
    online.distinctUntilChanged().collectLatest { network ->
        if (!network.online) { waiting(); return@collectLatest }
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try { connect { failures = 0 } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Fixed user-facing status; never log keys or URLs. */ }
            disconnected()
            failures = (failures + 1).coerceAtMost(7)
            pause(alertRetryDelay(failures))
        }
    }
}

fun shouldResumeAlerts(enabled: Boolean, notificationsAllowed: Boolean, hasConnections: Boolean) =
    enabled && notificationsAllowed && hasConnections

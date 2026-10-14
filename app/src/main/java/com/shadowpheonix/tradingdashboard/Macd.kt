package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin
import kotlin.math.abs

const val MACD_INTERVAL = 900_000L

@Serializable
data class MacdPoint(val timestamp: Long, val macd: Double, val signal: Double, val histogram: Double)
@Serializable
data class MacdModel(val id: String, val fastPeriod: Int, val slowPeriod: Int, val signalPeriod: Int,
    val points: List<MacdPoint>)
@Serializable
data class MacdSnapshot(val schemaVersion: Int, val instance: String, val generatedAt: Long,
    val source: String, val symbol: String, val intervalMinutes: Int, val status: String,
    val sampledAt: Long? = null, val models: List<MacdModel> = emptyList())

fun validateMacd(value: MacdSnapshot) {
    require(value.schemaVersion == 2 && value.instance.isNotBlank() && value.instance.length <= 240 &&
        value.generatedAt > 0 && value.source in setOf("binance_spot_closed", "bot_spot_closed") && value.symbol == "BTCUSDT" &&
        value.intervalMinutes == 15 && value.status in setOf("ok", "stale", "loading", "failed"))
    if (value.models.isEmpty()) {
        require(value.status in setOf("loading", "failed") && value.sampledAt == null)
        return
    }
    require(value.status in setOf("ok", "stale") && value.sampledAt != null &&
        value.sampledAt in 1L..value.generatedAt && value.models.map { it.id } == listOf("M1", "M2", "M3"))
    value.models.forEachIndexed { index, model ->
        val fast = (index + 2) * 150
        require(model.fastPeriod == fast && model.slowPeriod == fast * 3 && model.signalPeriod == fast &&
            model.points.size == 96)
        model.points.forEachIndexed { i, p ->
            require(p.timestamp > 0 && p.timestamp % MACD_INTERVAL == 0L &&
                p.timestamp + MACD_INTERVAL <= value.generatedAt &&
                listOf(p.macd, p.signal, p.histogram).all { it.isFinite() && abs(it) <= 1e20 } &&
                abs(p.histogram - (p.macd - p.signal)) < 0.000001 &&
                (i == 0 || p.timestamp - model.points[i - 1].timestamp == MACD_INTERVAL))
        }
        require(model.points.map { it.timestamp } == value.models.first().points.map { it.timestamp })
        if (value.status == "ok") require(model.points.last().timestamp ==
            value.generatedAt / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL)
    }
}

fun macdIsStale(value: MacdSnapshot?, now: Long): Boolean = value?.let {
    it.status != "ok" || it.models.firstOrNull()?.points?.lastOrNull()?.timestamp !=
        now / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL
} ?: true

/** Separate chart requests never replace/reopen the portfolio WebSocket or fetch balances. */
class MacdSession(private val scope: CoroutineScope,
    private val fetch: suspend (Connection) -> MacdSnapshot,
    private val onValue: (MacdSnapshot) -> Unit,
    private val onError: (String) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val cache: MacdCache? = null,
    private val onCached: (MacdSnapshot) -> Unit = onValue,
    private val onRefreshing: () -> Unit = {}) {
    private var job: Job? = null
    private var active: Connection? = null
    fun select(connection: Connection?, enabled: Boolean, refresh: Boolean = false) {
        if (!enabled || connection == null) { close(); return }
        if (!refresh && active?.id == connection.id && active?.accessKey == connection.accessKey && job?.isActive == true) return
        close(); active = connection
        job = scope.launch {
            var retained = try { cache?.load(connection) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            ensureActive()
            retained?.let(onCached)
            while (true) {
                try {
                    val value = fetch(connection)
                    ensureActive()
                    if (value.models.isNotEmpty()) {
                        if (retained == null || value.generatedAt >= retained.generatedAt) {
                            val changed = retained?.models != value.models || retained?.status != value.status || retained?.source != value.source
                            retained = value
                            onValue(value)
                            try { if (changed) cache?.save(connection, value) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Cache failure does not block online charts. */ }
                        }
                    } else if (retained == null) onValue(value)
                    if (value.status == "loading") onRefreshing()
                    if (value.status == "failed") onError("Candle source unavailable · retrying. Saved charts shown when available.")
                    pause(if (value.status == "loading") 2_000 else 30_000)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { onError("MACD data unavailable. Refresh to retry."); pause(30_000) }
            }
        }
    }
    fun forget(id: String) {
        val pending = if (active?.id == id) job else null
        if (pending != null) close()
        scope.launch { pending?.cancelAndJoin(); cache?.forget(id) }
    }
    fun close() { job?.cancel(); job = null; active = null }
}

package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.cancelAndJoin

const val MACD_INTERVAL = 900_000L

@Serializable
data class MacdPoint(val timestamp: Long, val macd: Double, val signal: Double, val histogram: Double)
@Serializable
data class MacdModel(val id: String, val fastPeriod: Int, val slowPeriod: Int, val signalPeriod: Int,
    val points: List<MacdPoint>)
@Serializable
data class PriceCandle(val timestamp: Long, val open: Double, val high: Double, val low: Double, val close: Double)
@Serializable
data class MacdSnapshot(val schemaVersion: Int, val instance: String, val generatedAt: Long,
    val source: String, val symbol: String, val intervalMinutes: Int, val status: String,
    val sampledAt: Long? = null, val models: List<MacdModel> = emptyList(),
    val candles: List<PriceCandle> = emptyList(), val candleStatus: String? = null,
    val candleSampledAt: Long? = null, val tradeAnnotations: List<ChartTradeAnnotation> = emptyList(),
    val annotationStatus: String? = null, val annotationSampledAt: Long? = null,
    val chartWindow: Int = 96, val formingCandle: PriceCandle? = null,
    val chartModels: List<MacdModel> = emptyList(), val formingModels: List<MacdModel> = emptyList(),
    val chartMacdStatus: String? = null)

fun validateMacd(value: MacdSnapshot) {
    require(value.schemaVersion == 2 && value.instance.isNotBlank() && value.instance.length <= 240 &&
        value.generatedAt > 0 && value.source in setOf("binance_spot_closed", "bot_spot_closed") && value.symbol == "BTCUSDT" &&
        value.intervalMinutes == 15 && value.status in setOf("ok", "stale", "loading", "failed"))
    validatePriceCandles(value)
    validateLiveChart(value)
    validateTradeAnnotations(value)
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
                validMacdPoint(p) &&
                (i == 0 || p.timestamp - model.points[i - 1].timestamp == MACD_INTERVAL))
        }
        require(model.points.map { it.timestamp } == value.models.first().points.map { it.timestamp })
        if (value.status == "ok") require(model.points.last().timestamp ==
            value.generatedAt / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL)
    }
}

fun validatePriceCandles(value: MacdSnapshot) {
    require(value.candleStatus == null || value.candleStatus in setOf("ok", "stale", "loading", "failed"))
    if (value.candles.isEmpty()) {
        require(value.candleStatus == null || value.candleStatus in setOf("loading", "failed"))
        require(value.candleSampledAt == null)
        return
    }
    require(value.candles.size == (if (value.chartWindow == 256) 255 else 96) && value.candleStatus in setOf("ok", "stale") &&
        value.candleSampledAt != null && value.candleSampledAt in 1L..value.generatedAt)
    value.candles.forEachIndexed { index, candle ->
        require(candle.timestamp > 0 && candle.timestamp % MACD_INTERVAL == 0L &&
            candle.timestamp + MACD_INTERVAL <= value.generatedAt &&
            listOf(candle.open, candle.high, candle.low, candle.close).all { it.isFinite() && it > 0 && it <= 1e20 } &&
            candle.low <= minOf(candle.open, candle.close) && candle.high >= maxOf(candle.open, candle.close) &&
            (index == 0 || candle.timestamp - value.candles[index - 1].timestamp == MACD_INTERVAL))
    }
    if (value.candleStatus == "ok") require(value.candles.last().timestamp ==
        value.generatedAt / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL)
}

fun candlesAreStale(value: MacdSnapshot?, now: Long): Boolean = value?.let {
    it.candleStatus != "ok" || it.candles.lastOrNull()?.timestamp !=
        now / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL
} ?: true

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
                    if (value.models.isNotEmpty() || value.candles.isNotEmpty() || value.annotationStatus != null) {
                        if (retained == null || value.generatedAt >= retained.generatedAt) {
                            // Price and indicator availability are independent. A partial
                            // refresh must not erase the other complete retained series.
                            var merged = value
                            retained?.let { saved ->
                                if (merged.models.isEmpty() && saved.models.isNotEmpty()) merged = merged.copy(
                                    models = saved.models, sampledAt = saved.sampledAt, source = saved.source, status = "stale")
                                if (merged.candles.isEmpty() && saved.candles.isNotEmpty()) merged = merged.copy(
                                    candles = saved.candles, candleSampledAt = saved.candleSampledAt, candleStatus = "stale",
                                    chartWindow = saved.chartWindow, formingCandle = null, chartModels = saved.chartModels,
                                    formingModels = emptyList(), chartMacdStatus = if (saved.chartModels.isNotEmpty()) "stale" else saved.chartMacdStatus)
                                if (merged.chartWindow == 256 && merged.chartModels.isEmpty() && saved.chartModels.isNotEmpty())
                                    merged = merged.copy(chartModels=saved.chartModels, formingModels=emptyList(), chartMacdStatus="stale")
                            }
                            val changed = retained?.models != merged.models || retained?.status != merged.status || retained?.source != merged.source ||
                                retained?.candles != merged.candles || retained?.candleStatus != merged.candleStatus ||
                                retained?.chartModels != merged.chartModels || retained?.chartWindow != merged.chartWindow
                            retained = merged
                            onValue(merged)
                            try { if (changed) cache?.save(connection, merged) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { /* Cache failure does not block online charts. */ }
                        }
                    } else if (retained == null) onValue(value)
                    if (value.status == "loading") onRefreshing()
                    if (value.status == "failed") onError("MACD source unavailable · retrying. Saved indicators shown when available.")
                    pause(if (value.status == "loading" || value.candleStatus == "loading") 2_000 else if (value.chartWindow == 256) 5_000 else 30_000)
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

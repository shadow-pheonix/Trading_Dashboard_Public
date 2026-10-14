package com.shadowpheonix.tradingdashboard

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

data class TradeStatistics(
    val count: Int, val wins: Int, val losses: Int, val breakeven: Int,
    val netPnl: Double, val fees: Double, val averagePnl: Double?,
    val profitFactor: Double?, val hasWinsWithoutLosses: Boolean,
    val bestTrade: Double?, val worstTrade: Double?,
)

// Trades are already paired, source-scoped and period-filtered by the v2 server.
// Recorded equity/Sharpe require actual samples. Reference equity is separately labelled
// and uses the Edge Monitor source-aware compounding convention on a $10k base.
fun tradeStatistics(snapshot: DashboardSnapshot): TradeStatistics? {
    if (snapshot.metrics.closedTrades != snapshot.trades.size || snapshot.metrics.realizedPnl == null) return null
    val trades = snapshot.trades.sortedWith(compareBy<Trade> { it.closedAt }.thenBy { it.id })
    val wins = trades.filter { it.netPnl > 0 }
    val losses = trades.filter { it.netPnl < 0 }
    val profit = wins.sumOf { it.netPnl }
    val loss = -losses.sumOf { it.netPnl }
    val net = trades.sumOf { it.netPnl }
    return TradeStatistics(trades.size, wins.size, losses.size, trades.size - wins.size - losses.size,
        net, trades.sumOf { it.fees }, if (trades.isEmpty()) null else net / trades.size,
        if (loss > 0) profit / loss else null, profit > 0 && loss == 0.0,
        trades.maxOfOrNull { it.netPnl }, trades.minOfOrNull { it.netPnl })
}

enum class ChartView(val label: String) {
    Cumulative("Cumulative"), Daily("Daily"), Monthly("Monthly"), NormalizedEquity("Equity"), Equity("Recorded equity")
}

data class ChartSample(val timestamp: Long, val value: Double)

data class ChartSeries(val recorded: List<ChartSample>, val captured: ChartSample?) {
    val samples: List<ChartSample> get() = recorded + listOfNotNull(captured)
}

// Keep the last actual close at its recorded timestamp. The current estimate is
// a separate point, never a fabricated closed trade at the snapshot time.
fun chartSeries(snapshot: DashboardSnapshot, view: ChartView): ChartSeries {
    val recorded = chartSamples(snapshot, view)
    val captured = capturedChartSample(snapshot, view)?.takeIf {
        recorded.isEmpty() || it.timestamp >= recorded.last().timestamp
    }
    return ChartSeries(recorded, captured)
}

fun chartTimeFraction(samples: List<ChartSample>, timestamp: Long): Double {
    if (samples.size < 2 || samples.last().timestamp == samples.first().timestamp) return 0.5
    return ((timestamp - samples.first().timestamp).toDouble() /
        (samples.last().timestamp - samples.first().timestamp)).coerceIn(0.0, 1.0)
}

data class ChartBounds(val low: Double, val high: Double)

fun chartBounds(values: List<Double>, bars: Boolean): ChartBounds {
    val finite = values.filter { it.isFinite() }
    val min = finite.minOrNull() ?: 0.0
    val max = finite.maxOrNull() ?: 0.0
    val low = if (bars) minOf(0.0, min) else min
    val high = if (bars) maxOf(0.0, max) else max
    val range = (high - low).takeIf { it > 0 } ?: maxOf(abs(high) * 0.05, 1.0)
    return ChartBounds(low - range * 0.15, high + range * 0.15)
}

fun chartAxisLabel(value: Double): String = when {
    abs(value) >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000)
    abs(value) >= 1_000 -> String.format(Locale.US, "%.1fk", value / 1_000)
    else -> String.format(Locale.US, "%.0f", value)
}

fun chartSamples(snapshot: DashboardSnapshot, view: ChartView): List<ChartSample> = when (view) {
    ChartView.Cumulative -> snapshot.curve.sortedBy { it.timestamp }.map { ChartSample(it.timestamp, it.pnl) }
    ChartView.NormalizedEquity -> snapshot.normalizedEquity?.points?.sortedBy { it.timestamp }
        ?.map { ChartSample(it.timestamp, it.equity) } ?: emptyList()
    ChartView.Equity -> snapshot.curve.sortedBy { it.timestamp }.mapNotNull { point ->
        point.equity?.takeIf { it.isFinite() }?.let { ChartSample(point.timestamp, it) }
    }
    ChartView.Daily, ChartView.Monthly -> if (tradeStatistics(snapshot) == null) emptyList() else {
        snapshot.trades.groupBy {
            val date = Instant.ofEpochMilli(it.closedAt).atZone(ZoneOffset.UTC).toLocalDate()
            (if (view == ChartView.Monthly) date.withDayOfMonth(1) else date)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }.toSortedMap().map { (timestamp, trades) -> ChartSample(timestamp, trades.sumOf { it.netPnl }) }
    }
}.filter { it.value.isFinite() }

fun availableChartViews(snapshot: DashboardSnapshot): List<ChartView> =
    ChartView.entries.filter {
        when (it) {
            ChartView.Equity -> chartSamples(snapshot, it).isNotEmpty()
            ChartView.NormalizedEquity -> snapshot.normalizedEquity != null
            else -> true
        }
    }

fun closedChartValue(snapshot: DashboardSnapshot, view: ChartView): Double? {
    val pnl = snapshot.metrics.realizedPnl ?: return null
    return when (view) {
        ChartView.Cumulative -> pnl
        ChartView.NormalizedEquity -> snapshot.normalizedEquity?.points?.maxByOrNull { it.timestamp }?.equity
        else -> null
    }?.takeIf { it.isFinite() }
}

fun liveChartSample(snapshot: DashboardSnapshot, view: ChartView, now: Long): ChartSample? {
    if (!isFresh(snapshot, now)) return null
    val closed = closedChartValue(snapshot, view) ?: return null
    val value = if (view == ChartView.NormalizedEquity) {
        val fraction = snapshot.openPerformance?.entryEquityReturnPct?.takeIf { it.isFinite() && it > -100 } ?: return null
        closed * (1 + fraction / 100)
    } else {
        val open = snapshot.metrics.unrealizedPnl?.takeIf { it.isFinite() } ?: return null
        closed + open
    }
    return value.takeIf { it.isFinite() }?.let { ChartSample(snapshot.generatedAt, it) }
}

// Analytics is a captured view. Check whether prices were fresh when the server
// built this snapshot, rather than removing its open point as wall time passes.
fun capturedChartSample(snapshot: DashboardSnapshot, view: ChartView): ChartSample? {
    if (snapshot.historyMode != "real") return null
    val mark = snapshot.priceUpdatedAt?.takeIf { it - snapshot.generatedAt in 0L..1_000L }
    val capturedAt = maxOf(snapshot.generatedAt, mark ?: snapshot.generatedAt)
    return liveChartSample(snapshot, view, capturedAt)
}

fun closestChartSample(samples: List<ChartSample>, fraction: Double): Int? {
    if (samples.isEmpty()) return null
    val position = fraction.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
    val timestamp = samples.first().timestamp + position * (samples.last().timestamp - samples.first().timestamp)
    return samples.indices.minByOrNull { kotlin.math.abs(samples[it].timestamp - timestamp) }
}

// Only a matching real-money instance/model may use the separate demand-fetched balance sample.
data class ScopedBalance(val equity: Double?, val sampledAt: Long?)

fun scopedBalanceSample(state: DashboardState): ScopedBalance? {
    val snapshot = state.snapshot ?: return null
    val balances = state.balances ?: return null
    if (snapshot.historyMode != "real" || snapshot.instance != balances.instance) return null
    return if (snapshot.selectedModel == "all") ScopedBalance(balances.equity, balances.sampledAt)
    else balances.models.find { it.id == snapshot.selectedModel }?.let { ScopedBalance(it.equity, it.sampledAt) }
}

fun scopedBalance(state: DashboardState): Double? = scopedBalanceSample(state)?.equity

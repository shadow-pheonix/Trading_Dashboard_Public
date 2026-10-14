package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable

val models = linkedMapOf("all" to "All models", "M1" to "M1", "M2" to "M2", "M3" to "M3")

fun strategyLabel(model: String): String = when (model) {
    "M1" -> "M1(300)"
    "M2" -> "M2(450)"
    "M3" -> "M3(600)"
    else -> model
}

@Serializable
data class Connection(val id: String, val label: String, val baseUrl: String, val accessKey: String) {
    override fun toString() = "Connection(id=$id, label=$label, credentials=redacted)"
}

fun migrateConnections(saved: List<Connection>): List<Connection> = saved

@Serializable
data class DashboardSnapshot(
    val schemaVersion: Int = 2,
    val instance: String,
    val generatedAt: Long,
    val live: Boolean,
    val period: String,
    val selectedAccount: String = "all",
    val selectedModel: String = "all",
    val currency: String = "USD",
    val accounts: List<Account>,
    val metrics: Metrics,
    val curve: List<CurvePoint> = emptyList(),
    val monthlyReturns: List<MonthlyReturn> = emptyList(),
    val positions: List<Position> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val strategies: List<Strategy> = emptyList(),
    val dataSource: String = "unknown",
    val stateUpdatedAt: Long? = null,
    val priceUpdatedAt: Long? = null,
    val pnlBasis: String = "unknown",
    val historyMode: String = "real",
    val btcTicker: BtcTicker? = null,
    val openPerformance: OpenPerformance? = null,
    val normalizedEquity: NormalizedEquity? = null,
)

@Serializable
data class NormalizedEquity(val baseEquity: Double, val legacyCapitalEstimate: Boolean, val points: List<EquityPoint>,
    val drawdown: Drawdown? = null)

@Serializable
data class Drawdown(val usd: Double, val pct: Double)

@Serializable
data class EquityPoint(val timestamp: Long, val equity: Double)

@Serializable
data class BtcTicker(val symbol: String, val price: Double, val change24hPct: Double, val updatedAt: Long)

@Serializable
data class OpenPerformance(val investedUsd: Double?, val entryEquityUsd: Double?,
    val investedReturnPct: Double?, val entryEquityReturnPct: Double?,
    val usesIdleBalances: Boolean = false, val idleBalanceSampledAt: Long? = null)

fun isBtcFresh(snapshot: DashboardSnapshot, now: Long): Boolean = snapshot.historyMode == "real" &&
    btcTickerIsFresh(snapshot.btcTicker, now)

fun btcTickerIsFresh(ticker: BtcTicker?, now: Long): Boolean =
    ticker?.let { now - it.updatedAt in 0L..15_000L } == true

fun newestBtcTicker(previous: BtcTicker?, incoming: BtcTicker?): BtcTicker? =
    incoming?.takeIf { previous == null || it.updatedAt >= previous.updatedAt } ?: previous

@Serializable
data class Account(val id: String, val label: String, val equity: Double? = null, val unrealizedPnl: Double? = null)

@Serializable
data class Metrics(
    val realizedPnl: Double? = null,
    val unrealizedPnl: Double? = null,
    val equity: Double? = null,
    val totalReturnPct: Double? = null,
    val winRatePct: Double? = null,
    val sharpeRatio: Double? = null,
    val maxDrawdownPct: Double? = null,
    val profitableDays: Int? = null,
    val observedDays: Int? = null,
    val closedTrades: Int? = null,
)

@Serializable
data class CurvePoint(val timestamp: Long, val pnl: Double, val equity: Double? = null)

@Serializable
data class MonthlyReturn(val year: Int, val month: Int, val returnPct: Double?, val pnl: Double? = null)

@Serializable
data class Position(
    val id: String, val accountId: String, val symbol: String, val side: String,
    val size: Double, val entryPrice: Double, val markPrice: Double?,
    val unrealizedPnl: Double?, val leverage: Double, val stopPrice: Double? = null,
    val stopPrices: List<Double> = emptyList(),
)

@Serializable
data class Trade(
    val id: String, val accountId: String, val strategy: String,
    val symbol: String, val side: String, val openedAt: Long?, val closedAt: Long,
    val entryPrice: Double, val exitPrice: Double, val netPnl: Double,
    val fees: Double, val funding: Double? = null, val source: String = "live",
)

@Serializable
data class Strategy(
    val id: String, val name: String, val status: String,
    val signal: String, val reason: String, val evaluatedAt: Long,
)

fun isFresh(snapshot: DashboardSnapshot, now: Long): Boolean =
    snapshot.live && snapshot.generatedAt > 0 &&
        now - snapshot.generatedAt in 0L..90_000L &&
        (snapshot.dataSource != "bot_files_binance" || snapshot.priceUpdatedAt?.let { now - it in 0L..15_000L } == true)

@Serializable
data class WalletAllocation(val wallet: String, val pct: Double?)

@Serializable
data class ModelBalance(val id: String, val equity: Double?, val status: String, val sampledAt: Long?,
    val walletAllocations: List<WalletAllocation> = emptyList())

@Serializable
data class BalanceSnapshot(val schemaVersion: Int, val instance: String, val generatedAt: Long,
    val attemptedAt: Long, val sampledAt: Long?, val status: String, val equity: Double?,
    val models: List<ModelBalance>, val originalEquity: Double? = null,
    val pnl: Double? = null, val returnPct: Double? = null)

// Entry notional: the USD value of the combined BTC size, before leverage.
fun positionNotionalUsd(position: Position): Double? = (position.size * position.entryPrice)
    .takeIf { it.isFinite() && it > 0 }

// A presentation midpoint only; exact saved stops remain available in details.
// Spread is measured against the midpoint, with a maximum difference of 0.1%.
fun positionStopMidpoint(position: Position): Double? {
    val stops = position.stopPrices.ifEmpty { listOfNotNull(position.stopPrice) }
    if (stops.isEmpty() || stops.any { !it.isFinite() || it <= 0 }) return null
    val low = stops.min(); val high = stops.max()
    val midpoint = low + (high - low) / 2
    return midpoint.takeIf { (high - low) <= it * 0.001 }
}

fun positionDirection(positions: List<Position>): String = positions.map { it.side }.distinct().sorted()
    .joinToString(" + ").ifEmpty { "FLAT" }

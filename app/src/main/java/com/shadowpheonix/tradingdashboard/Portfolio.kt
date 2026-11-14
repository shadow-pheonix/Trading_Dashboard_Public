package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable

@Serializable
data class PortfolioSnapshot(val schemaVersion: Int, val generatedAt: Long, val currency: String,
    val status: String, val totals: PortfolioTotals, val instances: List<PortfolioInstance>)

@Serializable
data class PortfolioTotals(val equity: Double?, val originalEquity: Double?, val unrealizedPnl: Double?,
    val openPositions: Int?, val returnPct: Double?)

@Serializable
data class PortfolioInstance(val id: String, val label: String, val status: String,
    val dashboard: DashboardSnapshot?, val balances: BalanceSnapshot?)

fun portfolioAvailable(state: DashboardState): Boolean =
    state.active != null && (state.liveSnapshot ?: state.snapshot)?.portfolioAvailable == true

fun portfolioRequest(connection: Connection) = dashboardRequest(connection, "All", "all").let { request ->
    request.newBuilder().removeHeader("X-Dashboard-Margin")
        .url(request.url.newBuilder().query("view=portfolio").build()).build()
}

fun validatePortfolio(value: PortfolioSnapshot) {
    if (value.schemaVersion != 2 || value.generatedAt <= 0 || value.currency != "USD" ||
        value.status !in listOf("ok", "partial") || value.instances.size !in 1..20 ||
        value.instances.map { it.id }.distinct().size != value.instances.size)
        throw DashboardError("Invalid portfolio response.")
    value.instances.forEach { item ->
        if (!item.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) || item.label.isBlank() || item.label.length > 128 ||
            item.status !in listOf("ok", "partial", "unavailable")) throw DashboardError("Invalid portfolio instance.")
        item.dashboard?.let { dashboard ->
            validateSnapshot(dashboard, "All", "all", "real")
            if (dashboard.instance != item.label || dashboard.generatedAt > value.generatedAt ||
                dashboard.trades.isNotEmpty() || dashboard.curve.isNotEmpty() || dashboard.monthlyReturns.isNotEmpty() ||
                dashboard.normalizedEquity != null || dashboard.edgeDrawdowns != null || dashboard.realDrawdowns != null ||
                dashboard.portfolioAvailable) throw DashboardError("Invalid portfolio scope.")
        }
        item.balances?.let { balances ->
            validateBalances(balances)
            if (balances.instance != item.label || balances.generatedAt > value.generatedAt)
                throw DashboardError("Invalid portfolio balances.")
        }
    }
    val totals = value.totals
    listOf(totals.equity, totals.originalEquity, totals.unrealizedPnl, totals.returnPct).forEach {
        if (it != null && (!it.isFinite() || kotlin.math.abs(it) > 1e20)) throw DashboardError("Invalid portfolio totals.")
    }
    fun sum(values: List<Double?>): Double? = if (values.all { it != null }) values.sumOf { it!! } else null
    fun matches(actual: Double?, expected: Double?) = if (actual == null || expected == null) actual == expected
        else kotlin.math.abs(actual - expected) <= maxOf(0.000001, kotlin.math.abs(expected) * 1e-10)
    val equity = sum(value.instances.map { it.balances?.equity })
    val original = sum(value.instances.map { it.balances?.originalEquity })
    val pnl = sum(value.instances.map { it.dashboard?.metrics?.unrealizedPnl })
    val count = if (value.instances.all { it.dashboard != null }) value.instances.sumOf { it.dashboard!!.positions.size } else null
    val returns = if (equity != null && original != null && original > 0) (equity / original - 1) * 100 else null
    if (!matches(totals.equity, equity) || !matches(totals.originalEquity, original) ||
        !matches(totals.unrealizedPnl, pnl) || !matches(totals.returnPct, returns) || totals.openPositions != count)
        throw DashboardError("Portfolio totals do not match their instances.")
}

package com.shadowpheonix.tradingdashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// Synthetic data for Android Studio only. This file is excluded from release APKs.
private const val previewTime = 1_791_446_700_000L
private val previewSnapshot = DashboardSnapshot(
    instance = "Main", generatedAt = previewTime, live = true, period = "All",
    accounts = listOf(Account("M1", "M1"), Account("M2", "M2"), Account("M3", "M3")),
    metrics = Metrics(unrealizedPnl = 4696.84), dataSource = "bot_files_binance",
    stateUpdatedAt = previewTime, priceUpdatedAt = previewTime,
    btcTicker = BtcTicker("BTCUSDT", 80828.49, -2.15, previewTime),
    openPerformance = OpenPerformance(30000.0, 45000.0, 4696.84 / 30000 * 100,
        4696.84 / 45000 * 100, true, previewTime),
    positions = listOf(
        Position("m1", "M1", "BTCUSDT", "SHORT", 0.7014, 85457.25, 80828.49, 3246.61, 3.0, 87166.40),
        Position("m2", "M2", "BTCUSDT", "SHORT", 0.3067, 85557.0, 80828.49, 1450.23, 3.0, 87268.14),
    ),
)

@Preview(name = "Home · 360dp", widthDp = 360, heightDp = 900, showBackground = true)
@Preview(name = "Home · narrow", widthDp = 320, heightDp = 900, showBackground = true)
@Preview(name = "Home · dark", widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Home · large text", widthDp = 360, heightDp = 1100, fontScale = 1.5f, showBackground = true)
@Composable
private fun HomePreview() {
    val state = DashboardState(snapshot = previewSnapshot, btcTicker = previewSnapshot.btcTicker,
        btcPriceDirection = BtcPriceDirection.Up, connectionHealthy = true, now = previewTime,
        balancesStale = false, balances = BalanceSnapshot(2, "Main", previewTime, previewTime, previewTime,
            "ok", 42795.80, listOf(ModelBalance("M1", 17400.15, "ok", previewTime),
                ModelBalance("M2", 11980.40, "ok", previewTime), ModelBalance("M3", 13415.25, "ok", previewTime)),
            originalEquity = 40000.0, pnl = 2795.80, returnPct = 2795.80 / 40000 * 100))
    DashboardTheme(Appearance.System) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Header(state, {}, {}, {})
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PortfolioOverviewCard(state)
                PositionsCard(previewSnapshot)
                BalancesCard(state)
            }
        }
    }
}

@Preview(name = "Opposing positions · unavailable balances", widthDp = 360, heightDp = 900, showBackground = true)
@Preview(name = "Opposing positions · dark", widthDp = 360, heightDp = 900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun UnavailableHomePreview() {
    val snapshot = previewSnapshot.copy(metrics = Metrics(), positions = previewSnapshot.positions +
        Position("opposing", "M1", "BTCUSDT", "LONG", 0.1, 81200.0, null, null, 2.0))
    DashboardTheme(Appearance.System) {
        Column(Modifier.background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PortfolioOverviewCard(DashboardState(snapshot = snapshot, btcTicker = snapshot.btcTicker))
            PositionsCard(snapshot)
            BalancesCard(DashboardState(snapshot = snapshot))
        }
    }
}

@Preview(name = "Analytics controls · light", widthDp = 360, heightDp = 1100)
@Preview(name = "Analytics controls · dark", widthDp = 360, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AnalyticsPreview() {
    val trades = listOf(
        Trade("one", "M1", "M1", "BTCUSDT", "SHORT", null, previewTime - 172800000,
            82000.0, 81000.0, 120.0, 2.0),
        Trade("two", "M2", "M2", "BTCUSDT", "LONG", null, previewTime - 86400000,
            81000.0, 80800.0, -40.0, 1.0),
        Trade("three", "M1", "M1", "BTCUSDT", "SHORT", null, previewTime,
            82000.0, 81000.0, 90.0, 2.0),
    )
    val snapshot = previewSnapshot.copy(trades = trades,
        metrics = Metrics(realizedPnl = 170.0, closedTrades = 3, winRatePct = 66.7),
        curve = listOf(CurvePoint(trades[0].closedAt, 120.0), CurvePoint(trades[1].closedAt, 80.0),
            CurvePoint(trades[2].closedAt, 170.0)))
    DashboardTheme(Appearance.System) {
        Column(Modifier.background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AppearanceCard(Appearance.System, {})
            CurveCard(snapshot)
            MetricGrid(snapshot)
        }
    }
}

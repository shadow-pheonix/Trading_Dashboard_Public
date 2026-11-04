package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class RealDrawdownsTest {
    private val drawdowns = RealDrawdowns("All", "real", 3, true,
        RealDrawdown(17.25, 900.0), RealDrawdown(25.0, 400.0))
    private fun snapshot(mode: String = "real", period: String = "1M") = DashboardSnapshot(
        instance = "Main", generatedAt = 1_000_000, live = false, period = period,
        accounts = listOf(Account("M1", "M1"), Account("M2", "M2"), Account("M3", "M3")),
        metrics = Metrics(), realDrawdowns = drawdowns, historyMode = mode,
        dataSource = if (mode == "real") "bot_files_binance" else "edge_csv")

    @Test fun realLifetimeCardsRoundTripIndependentlyOfVisiblePeriodAndSource() {
        for (mode in listOf("real", "edge")) for (period in periods) {
            val value = snapshot(mode, period)
            val decoded = dashboardJson.decodeFromString<DashboardSnapshot>(dashboardJson.encodeToString(value))
            validateSnapshot(decoded, period, "all", mode)
            assertEquals(drawdowns, decoded.realDrawdowns)
            assertTrue(decoded.realDrawdowns!!.maximum.usd!! < decoded.realDrawdowns!!.current.usd!!)
        }
    }

    @Test fun absentMetricAndUnavailableDollarAmountsRemainUnavailableWithoutFallback() {
        val legacy = snapshot().copy(realDrawdowns = null,
            normalizedEquity = NormalizedEquity(10000.0, false, listOf(EquityPoint(1, 9000.0)), Drawdown(1000.0, 10.0)),
            metrics = Metrics(realizedPnl = -100.0))
        val decoded = dashboardJson.decodeFromString<DashboardSnapshot>(dashboardJson.encodeToString(legacy))
        validateSnapshot(decoded, "1M", "all")
        assertNull(decoded.realDrawdowns)
        val unknownCash = snapshot().copy(realDrawdowns = drawdowns.copy(current = RealDrawdown(12.0, null)))
        val roundTrip = dashboardJson.decodeFromString<DashboardSnapshot>(dashboardJson.encodeToString(unknownCash))
        validateSnapshot(roundTrip, "1M", "all")
        assertNull(roundTrip.realDrawdowns!!.current.usd)
    }

    @Test fun malformedDrawdownScopeAmountsAndOrderingAreRejected() {
        val invalid = listOf(drawdowns.copy(period = "1Y"), drawdowns.copy(source = "edge"),
            drawdowns.copy(closedTrades = 0), drawdowns.copy(closedTrades = 20001),
            drawdowns.copy(current = RealDrawdown(28.0, 10.0)),
            drawdowns.copy(current = RealDrawdown(Double.NaN, 10.0)),
            drawdowns.copy(current = RealDrawdown(-1.0, 10.0)),
            drawdowns.copy(maximum = RealDrawdown(101.0, 10.0)),
            drawdowns.copy(current = RealDrawdown(1.0, -1.0)),
            drawdowns.copy(current = RealDrawdown(1.0, Double.POSITIVE_INFINITY)),
            drawdowns.copy(current = RealDrawdown(1.0, 1e21)),
            drawdowns.copy(current = RealDrawdown(0.0, null)),
            drawdowns.copy(current = RealDrawdown(0.0, 10.0)))
        invalid.forEach { assertThrows(DashboardError::class.java) {
            validateSnapshot(snapshot().copy(realDrawdowns = it), "1M", "all")
        } }
        validateSnapshot(snapshot().copy(realDrawdowns = drawdowns.copy(current = RealDrawdown(0.0, 0.0))), "1M", "all")
    }
}

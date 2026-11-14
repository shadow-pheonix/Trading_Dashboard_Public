package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class EdgeEquityTest {
    private val stamp = 100000L
    private val history = NormalizedEquity(10000.0, false,
        listOf(EquityPoint(1000, 12000.0), EquityPoint(2000, 10800.0)),
        historySource = "edge_csv", walletScope = "A", openEstimate = EquityOpenEstimate(-5.0, stamp))
    private val drawdowns = EdgeDrawdowns("All", "edge", 2, false,
        RealDrawdown(10.0, 110.0), RealDrawdown(10.0, 110.0), "A")
    private fun snapshot() = DashboardSnapshot(instance = "Main", generatedAt = stamp, live = true,
        period = "All", accounts = listOf(Account("M1", "M1"), Account("M2", "M2"), Account("M3", "M3")),
        metrics = Metrics(unrealizedPnl = 9990.0), dataSource = "bot_files_binance", normalizedEquity = history,
        edgeDrawdowns = drawdowns, priceUpdatedAt = stamp,
        openPerformance = OpenPerformance(null, 1000.0, null, 999.0))

    @Test fun completeEdgeHistoryCanRenderWithoutRegularTradePnlAndRetainsScopedDottedPoint() {
        val value = snapshot()
        val decoded = dashboardJson.decodeFromString<DashboardSnapshot>(dashboardJson.encodeToString(value))
        validateSnapshot(decoded, "All", "all")
        assertEquals(drawdowns, decoded.edgeDrawdowns)
        assertNull(decoded.metrics.realizedPnl)
        val equity = displayedChartSeries(decoded, ChartView.NormalizedEquity)
        assertEquals(listOf(20.0, 8.0), equity.recorded.map { kotlin.math.round(it.value) })
        assertEquals(2.6, equity.captured!!.value, 0.000001)
        assertEquals(stamp, equity.captured!!.timestamp)
        val dd = equityDrawdownSeries(decoded, ChartView.NormalizedEquity)!!
        assertEquals(-14.5, dd.captured!!.value, 0.000001)
        assertEquals(equity.samples.map { it.timestamp }, dd.samples.map { it.timestamp })
        assertEquals(10.0, decoded.edgeDrawdowns!!.current.pct, 0.0)
    }

    @Test fun walletScopedEstimateNeverFallsBackToWholeInstanceOpenReturn() {
        val value = snapshot().copy(normalizedEquity = history.copy(openEstimate = null))
        assertNull(capturedChartSample(value, ChartView.NormalizedEquity))
        assertNull(equityDrawdownSeries(value, ChartView.NormalizedEquity)!!.captured)
        assertEquals(2, chartSeries(value, ChartView.NormalizedEquity).recorded.size)
        val stale = snapshot().copy(normalizedEquity = history.copy(openEstimate = EquityOpenEstimate(-5.0, 1)))
        assertNull(capturedChartSample(stale, ChartView.NormalizedEquity))
        assertNull(capturedChartSample(snapshot().copy(historyMode = "edge"), ChartView.NormalizedEquity))
    }

    @Test fun analyticsCaptureAndLinkedInspectionStayStableAsPhoneTimeMovesOn() {
        val value = snapshot()
        val captured = capturedChartSample(value, ChartView.NormalizedEquity)
        repeat(5) { assertEquals(captured, capturedChartSample(value, ChartView.NormalizedEquity)) }
        assertNull(liveChartSample(value, ChartView.NormalizedEquity, stamp + 15001))
        assertEquals(captured, capturedChartSample(value, ChartView.NormalizedEquity))
        val equity = displayedChartSeries(value, ChartView.NormalizedEquity)
        val dd = equityDrawdownSeries(value, ChartView.NormalizedEquity)!!
        for (fraction in listOf(0.0, 0.5, 1.0)) {
            assertEquals(closestChartSample(equity.samples, fraction), closestChartSample(dd.samples, fraction))
        }
    }

    @Test fun edgeDrawdownsAreLifetimeAcrossOtherStatisticSourceAndPeriodSelections() {
        for (mode in listOf("real", "edge")) for (period in periods) {
            val value = snapshot().copy(historyMode = mode, period = period,
                live = mode == "real", priceUpdatedAt = if (mode == "real") stamp else null,
                dataSource = if (mode == "edge") "edge_csv" else "bot_files_binance",
                normalizedEquity = history.copy(openEstimate = null),
                openPerformance = if (mode == "edge") null else snapshot().openPerformance)
            validateSnapshot(value, period, "all", mode)
            assertEquals(drawdowns, value.edgeDrawdowns)
        }
    }

    @Test fun invalidSourceWalletCapitalAndUnscopedOpenDataAreRejected() {
        val value = snapshot()
        for (invalid in listOf(history.copy(historySource = "real_csv"), history.copy(walletScope = "B"),
            history.copy(openEstimate = EquityOpenEstimate(-100.0, stamp)),
            history.copy(openEstimate = EquityOpenEstimate(Double.NaN, stamp)),
            history.copy(openEstimate = EquityOpenEstimate(1.0, stamp - 15001)),
            history.copy(openEstimate = EquityOpenEstimate(1.0, stamp + 1001)))) {
            assertThrows(DashboardError::class.java) { validateSnapshot(value.copy(normalizedEquity = invalid), "All", "all") }
        }
        for (invalid in listOf(drawdowns.copy(source = "real"), drawdowns.copy(walletScope = "B"),
            drawdowns.copy(period = "1Y"), drawdowns.copy(closedTrades = 0),
            drawdowns.copy(current = RealDrawdown(101.0, 1.0)), drawdowns.copy(current = RealDrawdown(0.0, 1.0)))) {
            assertThrows(DashboardError::class.java) { validateSnapshot(value.copy(edgeDrawdowns = invalid), "All", "all") }
        }
    }
}

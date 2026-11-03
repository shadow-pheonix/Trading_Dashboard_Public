package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class EquityPercentTest {
    private fun snapshot() = DashboardSnapshot(instance = "Synthetic", generatedAt = 100000,
        live = true, period = "All", accounts = emptyList(), priceUpdatedAt = 100000,
        metrics = Metrics(realizedPnl = -10.0, unrealizedPnl = 5.0, closedTrades = 2),
        normalizedEquity = NormalizedEquity(10000.0, false,
            listOf(EquityPoint(1000, 10000.0), EquityPoint(2000, 11000.0), EquityPoint(3000, 9900.0))),
        openPerformance = OpenPerformance(null, 1000.0, null, 5.0))

    @Test fun equityDefaultsToPercentAndPreservesCompoundReturnsAndOpenMark() {
        assertEquals(ChartView.NormalizedEquity, defaultAnalyticsChart)
        val result = displayedChartSeries(snapshot(), defaultAnalyticsChart)
        assertEquals(listOf(1000L, 2000L, 3000L), result.recorded.map { it.timestamp })
        listOf(0.0, 10.0, -1.0).zip(result.recorded).forEach { (expected, actual) -> assertEquals(expected, actual.value, 0.000001) }
        assertEquals(3.95, result.captured!!.value, 0.000001)
        assertEquals(100000L, result.captured.timestamp)
        assertEquals(9900.0, closedChartValue(snapshot(), defaultAnalyticsChart)!!, 0.0)
        assertEquals("-1.00%", equityPercent(result.recorded.last().value))
        assertEquals("+0.00%", equityPercent(-0.000001))
        assertEquals("0.10%", chartPercentAxisLabel(0.1))
        assertEquals("-0.05%", chartPercentAxisLabel(-0.05))
    }

    @Test fun unavailableCapitalStaysUnavailableAndOtherViewsKeepTheirUnits() {
        val missing = snapshot().copy(normalizedEquity = null)
        assertTrue(displayedChartSeries(missing, defaultAnalyticsChart).samples.isEmpty())
        assertEquals(chartSeries(snapshot(), ChartView.Cumulative), displayedChartSeries(snapshot(), ChartView.Cumulative))
        assertNull(displayedChartSeries(snapshot().copy(openPerformance = null), defaultAnalyticsChart).captured)
        assertNull(displayedChartSeries(snapshot().copy(historyMode = "edge"), defaultAnalyticsChart).captured)
    }
}

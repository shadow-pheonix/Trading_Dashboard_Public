package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class EquityDrawdownTest {
    private fun snapshot(values: List<Double>) = DashboardSnapshot(instance = "Synthetic", generatedAt = 100000,
        live = false, period = "All", accounts = emptyList(), dataSource = "bot_files_binance", metrics = Metrics(realizedPnl = 0.0),
        normalizedEquity = NormalizedEquity(10000.0, false,
            values.mapIndexed { i, value -> EquityPoint((i + 1) * 1000L, value) }))

    private fun percentages(expected: List<Double>, actual: ChartSeries) {
        assertEquals(expected.size, actual.samples.size)
        expected.zip(actual.samples).forEach { (want, point) -> assertEquals(want, point.value, 0.000001) }
    }

    @Test fun runningPeakIncludesUntouchedBaseAndResetsOnRecovery() {
        val value = snapshot(listOf(9000.0, 11000.0, 9900.0, 12100.0, 6050.0))
        percentages(listOf(-10.0, 0.0, -10.0, 0.0, -50.0), equityDrawdownSeries(value, ChartView.NormalizedEquity)!!)
    }

    @Test fun drawdownUsesEquityLevelsInsteadOfSubtractingDisplayedReturnPercentages() {
        val value = snapshot(listOf(10000.0, 11000.0, 9900.0))
        val displayed = displayedChartSeries(value, ChartView.NormalizedEquity)
        val dd = equityDrawdownSeries(value, ChartView.NormalizedEquity)!!
        percentages(listOf(0.0, 0.0, -10.0), dd)
        assertEquals(-1.0, displayed.recorded.last().value, 0.000001)
        assertEquals(displayed.samples.map { it.timestamp }, dd.samples.map { it.timestamp })
    }

    @Test fun capturedOpenEstimateExtendsBothCurvesWithoutChangingClosedDrawdowns() {
        val value = snapshot(listOf(10000.0, 12000.0, 10800.0)).copy(live = true, priceUpdatedAt = 100000,
            openPerformance = OpenPerformance(null, 1000.0, null, -5.0))
        val dd = equityDrawdownSeries(value, ChartView.NormalizedEquity)!!
        percentages(listOf(0.0, 0.0, -10.0, -14.5), dd)
        assertEquals(100000L, dd.captured!!.timestamp)
        assertEquals(3000L, dd.recorded.last().timestamp)
        val recovery = value.copy(openPerformance = value.openPerformance!!.copy(entryEquityReturnPct = 50.0))
        val recovered = equityDrawdownSeries(recovery, ChartView.NormalizedEquity)!!
        assertEquals(dd.recorded, recovered.recorded)
        assertEquals(0.0, recovered.captured!!.value, 0.0)
    }

    @Test fun missingStaleAndBacktestOpenMarksNeverInventDrawdownSamples() {
        val value = snapshot(listOf(10000.0, 9000.0)).copy(live = true, priceUpdatedAt = 100000,
            openPerformance = OpenPerformance(null, 1000.0, null, -5.0))
        for (variant in listOf(value.copy(openPerformance = null), value.copy(priceUpdatedAt = 1),
            value.copy(historyMode = "edge"), value.copy(live = false))) {
            val dd = equityDrawdownSeries(variant, ChartView.NormalizedEquity)!!
            percentages(listOf(0.0, -10.0), dd)
            assertNull(dd.captured)
        }
    }

    @Test fun selectedSourcePeriodAndModelsUseTheirOwnEquityRatherThanLifetimeCardValues() {
        val all = snapshot(listOf(10000.0, 15000.0, 12000.0)).copy(
            realDrawdowns = RealDrawdowns("All", "real", 100, false, RealDrawdown(3.0, 100.0), RealDrawdown(9.0, 300.0)))
        val filtered = snapshot(listOf(10000.0, 9000.0)).copy(period = "YTD", selectedModel = "M2", historyMode = "edge",
            realDrawdowns = all.realDrawdowns)
        percentages(listOf(0.0, 0.0, -20.0), equityDrawdownSeries(all, ChartView.NormalizedEquity)!!)
        percentages(listOf(0.0, -10.0), equityDrawdownSeries(filtered, ChartView.NormalizedEquity)!!)
        assertEquals(all.realDrawdowns, filtered.realDrawdowns)
    }

    @Test fun recordedEquityHasItsOwnPeakAndPnlViewsHaveNoPercentageDrawdown() {
        val value = snapshot(listOf(10000.0)).copy(curve = listOf(CurvePoint(1000, 0.0, 100.0),
            CurvePoint(2000, -20.0, 80.0), CurvePoint(3000, 20.0, 120.0), CurvePoint(4000, -10.0, 90.0)))
        percentages(listOf(0.0, -20.0, 0.0, -25.0), equityDrawdownSeries(value, ChartView.Equity)!!)
        for (view in listOf(ChartView.Cumulative, ChartView.Daily, ChartView.Monthly)) assertNull(equityDrawdownSeries(value, view))
    }

    @Test fun flatSingleZeroAndUnavailableBaselinesHaveExplicitOutcomes() {
        percentages(listOf(0.0), equityDrawdownSeries(snapshot(listOf(10000.0)), ChartView.NormalizedEquity)!!)
        percentages(listOf(0.0, 0.0), equityDrawdownSeries(snapshot(listOf(10000.0, 10000.0)), ChartView.NormalizedEquity)!!)
        percentages(listOf(0.0, -100.0), equityDrawdownSeries(snapshot(listOf(10000.0, 0.0)), ChartView.NormalizedEquity)!!)
        for (values in listOf(emptyList(), listOf(-1.0), listOf(Double.NaN)))
            assertNull(equityDrawdownSeries(snapshot(values), ChartView.NormalizedEquity))
        for (base in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val value = snapshot(listOf(10000.0))
            assertNull(equityDrawdownSeries(value.copy(normalizedEquity = value.normalizedEquity!!.copy(baseEquity = base)), ChartView.NormalizedEquity))
        }
        assertNull(equityDrawdownSeries(snapshot(listOf(10000.0)).copy(normalizedEquity = null), ChartView.NormalizedEquity))
        assertNull(equityDrawdownSeries(snapshot(emptyList()).copy(curve = listOf(CurvePoint(1, 0.0, 0.0))), ChartView.Equity))
    }

    @Test fun linkedInspectionUsesIdenticalTimePositionsAndIndicesIncludingIrregularCloses() {
        val value = snapshot(listOf(10000.0)).copy(normalizedEquity = NormalizedEquity(10000.0, false,
            listOf(EquityPoint(10000, 9000.0), EquityPoint(1000, 10000.0), EquityPoint(1100, 12000.0), EquityPoint(1100, 11000.0))))
        val equity = displayedChartSeries(value, ChartView.NormalizedEquity).samples
        val dd = equityDrawdownSeries(value, ChartView.NormalizedEquity)!!.samples
        assertEquals(equity.map { it.timestamp }, dd.map { it.timestamp })
        for (fraction in listOf(-1.0, 0.0, 0.02, 0.5, 1.0, 2.0)) {
            val index = closestChartSample(equity, fraction)!!
            assertEquals(index, closestChartSample(dd, fraction))
            assertEquals(chartTimeFraction(equity, equity[index].timestamp), chartTimeFraction(dd, dd[index].timestamp), 0.0)
        }
        assertEquals(listOf(10000L, 1000L, 1100L, 1100L), value.normalizedEquity!!.points.map { it.timestamp })
    }
}

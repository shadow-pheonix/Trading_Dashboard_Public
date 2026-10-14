package com.shadowpheonix.tradingdashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AppearanceAnalyticsTest {
    private class MemoryPreferences(var value: String? = null, var succeeds: Boolean = true) : AppearancePreferences {
        override fun read() = value
        override fun write(value: String): Boolean {
            if (succeeds) this.value = value
            return succeeds
        }
    }

    private fun trade(id: String, pnl: Double, time: String = "2026-01-01T12:00:00Z", fees: Double = 2.0,
                      source: String = "live") = Trade(id, "M1", "M1", "BTCUSDT", "LONG", null,
        Instant.parse(time).toEpochMilli(), 100.0, 110.0, pnl, fees, source = source)

    private fun snapshot(trades: List<Trade> = emptyList()) = DashboardSnapshot(
        instance = "Main", generatedAt = 1, live = false, period = "All", accounts = emptyList(),
        trades = trades, metrics = Metrics(realizedPnl = trades.sumOf { it.netPnl }, closedTrades = trades.size))

    @Test fun themePersistsAcrossStoreRecreationAndSupportsBothSystemStates() {
        val prefs = MemoryPreferences()
        assertEquals(Appearance.Light, AppearanceStore(prefs).load())
        Appearance.entries.forEach { theme ->
            AppearanceStore(prefs).save(theme)
            assertEquals(theme, AppearanceStore(prefs).load())
        }
        assertFalse(Appearance.Light.isDark(true))
        assertTrue(Appearance.Dark.isDark(false))
        assertFalse(Appearance.System.isDark(false))
        assertTrue(Appearance.System.isDark(true))
        prefs.value = "invalid"
        assertEquals(Appearance.Light, AppearanceStore(prefs).load())
    }

    @Test fun failedPreferenceWriteKeepsThePreviousValue() {
        val prefs = MemoryPreferences(Appearance.Light.name, succeeds = false)
        assertThrows(IllegalStateException::class.java) { AppearanceStore(prefs).save(Appearance.Dark) }
        assertEquals(Appearance.Light, AppearanceStore(prefs).load())
    }

    @Test fun themeChangesPreserveConnectionKeysFiltersAndHomeScope() {
        val connection = Connection("id", "Main", DASHBOARD_URL, "synthetic-key")
        val original = DashboardState(connections = listOf(connection), activeId = "id",
            page = Page.Analytics, period = "1Y", account = "M2", mode = "edge")
        val changed = original.copy(appearance = Appearance.Dark)
        assertEquals(original.query, changed.query)
        assertEquals(original.connections, changed.connections)
        assertFalse(changed.showConnectionForm)
        assertEquals(DashboardQuery("All", "all", "real"), changed.withPage(Page.Dashboard).query)
        assertEquals(Appearance.Dark, changed.withPage(Page.Dashboard).appearance)
    }

    private fun contrast(first: Color, second: Color): Double {
        val a = first.luminance().toDouble()
        val b = second.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    @Test fun lightAndDarkTextAndFinancialColorsHaveReadableContrast() {
        listOf(false, true).forEach { dark ->
            val colors = dashboardColors(dark)
            listOf(colors.surface, colors.background, colors.surfaceVariant,
                colors.surfaceContainer, colors.surfaceContainerLow, colors.surfaceContainerHigh).forEach { background ->
                listOf(colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.secondary, colors.error).forEach { text ->
                    assertTrue("$dark text contrast ${contrast(text, background)}", contrast(text, background) >= 4.5)
                }
            }
            assertTrue(contrast(colors.primary, colors.onPrimary) >= 4.5)
        }
        assertNotEquals(dashboardColors(false).surface, dashboardColors(true).surface)
    }

    @Test fun combinedTradesCountOnceAndFeesAreNotSubtractedAgain() {
        val stats = tradeStatistics(snapshot(listOf(trade("paired", 100.0, fees = 4.0),
            trade("loss", -40.0), trade("flat", 0.0))))!!
        assertEquals(3, stats.count)
        assertEquals(1, stats.wins)
        assertEquals(1, stats.losses)
        assertEquals(1, stats.breakeven)
        assertEquals(60.0, stats.netPnl, 0.0)
        assertEquals(8.0, stats.fees, 0.0)
        assertEquals(20.0, stats.averagePnl!!, 0.0)
        assertEquals(2.5, stats.profitFactor!!, 0.0)
    }

    @Test fun tradeStatisticsPreservePnlAcrossChronologicalCloses() {
        val rows = listOf(trade("last", -30.0, "2026-01-04T00:00:00Z"),
            trade("first", -20.0), trade("win", 100.0, "2026-01-02T00:00:00Z"),
            trade("loss", -40.0, "2026-01-03T00:00:00Z"))
        assertEquals(10.0, tradeStatistics(snapshot(rows))!!.netPnl, 0.0)
        assertEquals(-20.0, tradeStatistics(snapshot(listOf(rows[1])))!!.netPnl, 0.0)
    }

    @Test fun simultaneousClosesKeepTheSameCombinedTradeStatistics() {
        val rows = listOf(trade("a", -100.0), trade("b", 120.0))
        assertEquals(20.0, tradeStatistics(snapshot(rows))!!.netPnl, 0.0)
        assertEquals(tradeStatistics(snapshot(rows)), tradeStatistics(snapshot(rows.reversed())))
    }

    @Test fun emptyAllWinAllLossAndIncompleteHistoryHaveExplicitValues() {
        val empty = tradeStatistics(snapshot())!!
        assertEquals(0, empty.count)
        assertNull(empty.averagePnl)
        assertNull(empty.bestTrade)
        assertNull(empty.profitFactor)
        val win = tradeStatistics(snapshot(listOf(trade("win", 10.0))))!!
        assertTrue(win.hasWinsWithoutLosses)
        assertNull(win.profitFactor)
        val loss = tradeStatistics(snapshot(listOf(trade("loss", -10.0))))!!
        assertEquals(0.0, loss.profitFactor!!, 0.0)
        assertFalse(loss.hasWinsWithoutLosses)
        assertNull(tradeStatistics(snapshot().copy(metrics = Metrics())))
        assertNull(tradeStatistics(snapshot().copy(metrics = Metrics(realizedPnl = 10.0, closedTrades = 1))))
    }

    @Test fun dailyAndMonthlyChartsGroupInUtcAndIncludeBothSelectedSources() {
        val rows = listOf(trade("backtest", 10.0, "2026-01-31T23:59:59Z", source = "backtest"),
            trade("live", -4.0, "2026-02-01T00:00:00Z"),
            trade("later", 20.0, "2026-02-01T23:59:59Z"))
        val value = snapshot(rows).copy(historyMode = "edge")
        assertEquals(listOf(10.0, 16.0), chartSamples(value, ChartView.Daily).map { it.value })
        assertEquals(listOf(10.0, 16.0), chartSamples(value, ChartView.Monthly).map { it.value })
        assertEquals(Instant.parse("2026-02-01T00:00:00Z").toEpochMilli(), chartSamples(value, ChartView.Monthly).last().timestamp)
        assertTrue(chartSamples(value.copy(metrics = Metrics()), ChartView.Daily).isEmpty())
    }

    @Test fun equityChartAppearsOnlyForActualFiniteRecordedEquity() {
        val unavailable = snapshot().copy(curve = listOf(CurvePoint(1, 10.0, null)))
        assertFalse(ChartView.Equity in availableChartViews(unavailable))
        val available = unavailable.copy(curve = listOf(CurvePoint(3, 30.0, 200.0),
            CurvePoint(1, 10.0, null), CurvePoint(2, 20.0, 150.0)))
        assertTrue(ChartView.Equity in availableChartViews(available))
        assertEquals(listOf(ChartSample(2, 150.0), ChartSample(3, 200.0)), chartSamples(available, ChartView.Equity))
        assertEquals(listOf(10.0, 20.0, 30.0), chartSamples(available, ChartView.Cumulative).map { it.value })
    }

    @Test fun chartInspectionFindsTheNearestTimestampAndHandlesBoundaries() {
        val samples = listOf(ChartSample(100, 1.0), ChartSample(110, 2.0), ChartSample(200, 3.0))
        assertEquals(1, closestChartSample(samples, 0.2))
        assertEquals(0, closestChartSample(samples, -1.0))
        assertEquals(2, closestChartSample(samples, 2.0))
        assertEquals(0, closestChartSample(samples, Double.NaN))
        assertEquals(0, closestChartSample(samples.take(1), 0.9))
        assertNull(closestChartSample(emptyList(), 0.5))
    }

    @Test fun normalizedEquityUsesClosedReturnsAndAddsFreshOpenReturnOnce() {
        val stamp = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
        val value = snapshot().copy(generatedAt = stamp, live = true, dataSource = "bot_files_binance", priceUpdatedAt = stamp,
            normalizedEquity = NormalizedEquity(10000.0, false, listOf(EquityPoint(stamp - 1000, 9200.0))),
            openPerformance = OpenPerformance(100.0, 700.0, 7.0, 1.0),
            metrics = Metrics(realizedPnl = 20.0, unrealizedPnl = 7.0, closedTrades = 0),
            curve = listOf(CurvePoint(stamp - 1000, 20.0)))
        assertTrue(ChartView.NormalizedEquity in availableChartViews(value))
        assertFalse(ChartView.Equity in availableChartViews(value))
        assertEquals(listOf(ChartSample(stamp - 1000, 9200.0)), chartSamples(value, ChartView.NormalizedEquity))
        assertEquals(ChartSample(stamp, 9292.0), liveChartSample(value, ChartView.NormalizedEquity, stamp))
        assertEquals(ChartSample(stamp, 27.0), liveChartSample(value, ChartView.Cumulative, stamp))
        assertNull(liveChartSample(value, ChartView.Daily, stamp))
        assertNull(liveChartSample(value, ChartView.Equity, stamp))
        assertNull(liveChartSample(value.copy(metrics = value.metrics.copy(unrealizedPnl = null)), ChartView.Cumulative, stamp))
        assertNull(liveChartSample(value.copy(openPerformance = null), ChartView.NormalizedEquity, stamp))
        assertNull(liveChartSample(value.copy(historyMode = "edge", live = false), ChartView.NormalizedEquity, stamp))
        assertNull(liveChartSample(value.copy(live = false), ChartView.NormalizedEquity, stamp))
        assertNull(liveChartSample(value, ChartView.NormalizedEquity, stamp + 15001))
        assertEquals(9200.0, closedChartValue(value, ChartView.NormalizedEquity)!!, 0.0)
    }

    @Test fun analyticsCapturedPointDoesNotDisappearWithPhoneClockSkewOrTimePassing() {
        val stamp = 1000000L
        val value = snapshot().copy(generatedAt = stamp, live = true, dataSource = "bot_files_binance", priceUpdatedAt = stamp,
            normalizedEquity = NormalizedEquity(10000.0, false, listOf(EquityPoint(stamp - 1000, 11095.07))),
            openPerformance = OpenPerformance(1000.0, 1000.0, 7.8, 7.8),
            metrics = Metrics(realizedPnl = 100.0, unrealizedPnl = 78.0))
        val mark = capturedChartSample(value, ChartView.NormalizedEquity)!!
        assertEquals(11095.07 * 1.078, mark.value, .000001)
        for (phoneTime in listOf(stamp - 250, stamp + 16000, stamp + 3600000)) {
            assertNull(liveChartSample(value, ChartView.NormalizedEquity, phoneTime))
            assertEquals(mark, capturedChartSample(value, ChartView.NormalizedEquity))
        }
        assertEquals(ChartSample(stamp, 178.0), capturedChartSample(value, ChartView.Cumulative))
        assertNotNull(capturedChartSample(value.copy(priceUpdatedAt = stamp + 100), ChartView.NormalizedEquity))
        assertNull(capturedChartSample(value.copy(priceUpdatedAt = stamp + 1001), ChartView.NormalizedEquity))
        assertNull(capturedChartSample(value.copy(priceUpdatedAt = stamp - 15001), ChartView.NormalizedEquity))
        assertNull(capturedChartSample(value.copy(live = false, historyMode = "edge"), ChartView.NormalizedEquity))
        assertNull(capturedChartSample(value.copy(historyMode = "edge"), ChartView.NormalizedEquity))
        assertNull(capturedChartSample(value.copy(openPerformance = null), ChartView.NormalizedEquity))
    }

    @Test fun flatAndEmptyTradeHistoryCanShowKnownZeroAndMissingEquityStaysUnavailable() {
        val stamp = 100000L
        val value = snapshot().copy(generatedAt = stamp, live = true, dataSource = "bot_files_binance", priceUpdatedAt = stamp,
            metrics = Metrics(realizedPnl = 0.0, unrealizedPnl = 0.0, closedTrades = 0))
        assertEquals(ChartSample(stamp, 0.0), liveChartSample(value, ChartView.Cumulative, stamp))
        assertNull(closedChartValue(value, ChartView.NormalizedEquity))
        assertFalse(ChartView.NormalizedEquity in availableChartViews(value))
        assertNull(liveChartSample(value.copy(metrics = Metrics()), ChartView.Cumulative, stamp))
    }

    @Test fun chartBoundsIncludeLiveLossesAndFlatValuesWithoutClipping() {
        val range = chartBounds(listOf(100.0, 110.0, -50.0), false)
        assertTrue(range.low < -50.0 && range.high > 110.0)
        val flat = chartBounds(listOf(0.0), false)
        assertTrue(flat.low < 0 && flat.high > 0)
        val bars = chartBounds(listOf(100.0, 200.0), true)
        assertTrue(bars.low < 0 && bars.high > 200)
        val none = chartBounds(listOf(Double.NaN), false)
        assertTrue(none.low.isFinite() && none.high > none.low)
        assertEquals("43.1k", chartAxisLabel(43100.0))
        assertEquals("-1.5k", chartAxisLabel(-1500.0))
    }

    @Test fun activeChartPointSpansActualTimeAfterTheLastClose() {
        val stamp = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
        val first = stamp - 10 * 86400000L
        val lastClose = stamp - 2 * 86400000L
        val value = snapshot().copy(generatedAt = stamp, live = true, priceUpdatedAt = stamp,
            dataSource = "bot_files_binance", curve = listOf(CurvePoint(first, -100.0), CurvePoint(lastClose, 200.0)),
            metrics = Metrics(realizedPnl = 200.0, unrealizedPnl = 50.0, closedTrades = 0))
        val series = chartSeries(value, ChartView.Cumulative)
        assertEquals(lastClose, series.recorded.last().timestamp)
        assertEquals(ChartSample(stamp, 250.0), series.captured)
        assertEquals(3, series.samples.size)
        assertEquals(0.8, chartTimeFraction(series.samples, lastClose), .000001)
        assertEquals(1.0, chartTimeFraction(series.samples, stamp), 0.0)
        assertEquals(1, closestChartSample(series.samples, 0.8))
        assertEquals(2, closestChartSample(series.samples, 1.0))
        assertFalse(series.recorded.any { it.timestamp == stamp })
        val normalized = value.copy(normalizedEquity = NormalizedEquity(10000.0, false,
            listOf(EquityPoint(first, 10000.0), EquityPoint(lastClose, 11000.0))),
            openPerformance = OpenPerformance(null, 1000.0, null, 5.0))
        val equity = chartSeries(normalized, ChartView.NormalizedEquity)
        assertEquals(lastClose, equity.recorded.last().timestamp)
        assertEquals(ChartSample(stamp, 11550.0), equity.captured)
        assertEquals(.8, chartTimeFraction(equity.samples, lastClose), .000001)
    }

    @Test fun chartTimeHandlesEmptySingleEqualTimeAndFutureOrUnavailableSamples() {
        val value = snapshot().copy(generatedAt = 100000L, live = true, priceUpdatedAt = 100000L,
            dataSource = "bot_files_binance", metrics = Metrics(realizedPnl = 0.0, unrealizedPnl = 0.0))
        assertEquals(listOf(ChartSample(100000, 0.0)), chartSeries(value, ChartView.Cumulative).samples)
        assertEquals(.5, chartTimeFraction(emptyList(), 1), 0.0)
        assertEquals(.5, chartTimeFraction(listOf(ChartSample(1, 0.0)), 1), 0.0)
        val same = value.copy(curve = listOf(CurvePoint(100000, 0.0)))
        assertEquals(.5, chartTimeFraction(chartSeries(same, ChartView.Cumulative).samples, 100000), 0.0)
        val future = value.copy(curve = listOf(CurvePoint(100001, 0.0)))
        assertNull(chartSeries(future, ChartView.Cumulative).captured)
        assertEquals(future.curve.first().timestamp, chartSeries(future, ChartView.Cumulative).samples.last().timestamp)
        assertNull(chartSeries(value.copy(priceUpdatedAt = 1), ChartView.Cumulative).captured)
        assertNull(chartSeries(value.copy(historyMode = "edge"), ChartView.Cumulative).captured)
        assertNull(chartSeries(value, ChartView.Daily).captured)
        assertNull(chartSeries(value, ChartView.Equity).captured)
    }

    @Test fun drawdownComesFromServerEquityAndNeverFallsBackToRawPnl() {
        val raw = snapshot(listOf(trade("loss", -9999.0)))
        assertNull(raw.normalizedEquity?.drawdown)
        val equity = NormalizedEquity(10000.0, false, listOf(EquityPoint(1, 9000.0)), Drawdown(1000.0, 10.0))
        val server = raw.copy(normalizedEquity = equity)
        assertEquals(Drawdown(1000.0, 10.0), server.normalizedEquity?.drawdown)
        assertEquals(Drawdown(1000.0, 10.0), server.copy(historyMode = "edge").normalizedEquity?.drawdown)
        assertNull(server.copy(normalizedEquity = equity.copy(drawdown = null)).normalizedEquity?.drawdown)
    }

    @Test fun strategyNamesShowPeriodsWithoutChangingModelIdsOrFilters() {
        assertEquals(listOf("M1(300)", "M2(450)", "M3(600)"), listOf("M1", "M2", "M3").map(::strategyLabel))
        assertEquals(listOf("all", "M1", "M2", "M3"), models.keys.toList())
        assertEquals("Unknown", strategyLabel("Unknown"))
    }

    @Test fun currentBalanceRespectsInstanceModelSourceAndIncompleteTotals() {
        val balance = BalanceSnapshot(2, "Main", 1, 1, 1, "ok", 300.0,
            listOf(ModelBalance("M1", 100.0, "ok", 1), ModelBalance("M2", 200.0, "ok", 1)))
        val state = DashboardState(snapshot = snapshot(), balances = balance, balancesStale = true)
        assertEquals(300.0, scopedBalance(state)!!, 0.0)
        assertEquals(100.0, scopedBalance(state.copy(snapshot = snapshot().copy(selectedModel = "M1")))!!, 0.0)
        assertNull(scopedBalance(state.copy(snapshot = snapshot().copy(selectedModel = "M3"))))
        assertNull(scopedBalance(state.copy(snapshot = snapshot().copy(historyMode = "edge"))))
        assertNull(scopedBalance(state.copy(balances = balance.copy(instance = "Vinay"))))
        assertNull(scopedBalance(state.copy(balances = balance.copy(status = "failed", equity = null))))
        val partial = state.copy(snapshot = snapshot().copy(selectedModel = "M1"),
            balances = balance.copy(status = "failed", equity = null, sampledAt = null,
                models = listOf(ModelBalance("M1", 100.0, "ok", 7))))
        assertEquals(ScopedBalance(100.0, 7), scopedBalanceSample(partial))
    }
}

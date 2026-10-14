package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class OpenPerformanceTest {
    private val time = 100_000L
    private fun snapshot(pnl: Double? = 60.0) = DashboardSnapshot(
        instance = "Main", generatedAt = time, live = true, period = "All",
        accounts = listOf(Account("M1", "M1"), Account("M2", "M2"), Account("M3", "M3")),
        metrics = Metrics(unrealizedPnl = pnl), dataSource = "bot_files_binance", priceUpdatedAt = time)

    @Test fun olderV2SnapshotsLeaveNewFieldsUnavailable() {
        val original = snapshot()
        val json = dashboardJson.encodeToString(original)
        assertFalse(json.contains("btcTicker"))
        assertFalse(json.contains("openPerformance"))
        val value = dashboardJson.decodeFromString<DashboardSnapshot>(json)
        validateSnapshot(value, "All", "all")
        assertNull(value.btcTicker)
        assertNull(value.openPerformance)
    }

    @Test fun returnsAcceptPositiveNegativeAndZeroPnlOnSavedCapital() {
        listOf(60.0, -60.0, 0.0).forEach { pnl ->
            val value = snapshot(pnl).copy(openPerformance = OpenPerformance(1000.0, 3000.0,
                pnl / 1000 * 100, pnl / 3000 * 100))
            validateSnapshot(value, "All", "all")
            assertEquals(pnl / 1000 * 100, value.openPerformance!!.investedReturnPct!!, 0.0)
        }
    }

    @Test fun missingEntryEquityAndMissingMarksNeverGetAComputedReturn() {
        validateSnapshot(snapshot().copy(openPerformance = OpenPerformance(1000.0, null, 6.0, null)), "All", "all")
        validateSnapshot(snapshot(null).copy(openPerformance = OpenPerformance(1000.0, 3000.0, null, null)), "All", "all")
        validateSnapshot(snapshot(0.0).copy(openPerformance = OpenPerformance(0.0, null, null, null)), "All", "all")
        listOf(OpenPerformance(null, null, 0.0, null), OpenPerformance(1000.0, null, 60.0, null),
            OpenPerformance(-1.0, null, null, null), OpenPerformance(1000.0, 3000.0, 6.0, 3.0),
            OpenPerformance(Double.NaN, null, null, null), OpenPerformance(1000.0, null, Double.NaN, null)).forEach { bad ->
            assertThrows(DashboardError::class.java) { validateSnapshot(snapshot().copy(openPerformance = bad), "All", "all") }
        }
    }

    @Test fun tickerFreshnessHasIndependentFifteenSecondBoundary() {
        val value = snapshot().copy(btcTicker = BtcTicker("BTCUSDT", 81000.0, -2.5, time))
        validateSnapshot(value, "All", "all")
        assertTrue(isBtcFresh(value, time + 15_000))
        assertFalse(isBtcFresh(value, time + 15_001))
        assertFalse(isBtcFresh(value, time - 1))
        assertFalse(isBtcFresh(snapshot(), time))
        assertTrue(isBtcFresh(value.copy(priceUpdatedAt = null, live = false), time))
    }

    @Test fun mixedEntryAndIdleCapitalRequiresAnHonestSampleTimestamp() {
        val valid = OpenPerformance(1000.0, 1500.0, 6.0, 4.0, true, time - 1000)
        validateSnapshot(snapshot().copy(openPerformance = valid), "All", "all")
        val json = dashboardJson.encodeToString(snapshot().copy(openPerformance = valid))
        assertEquals(valid, dashboardJson.decodeFromString<DashboardSnapshot>(json).openPerformance)
        validateSnapshot(snapshot().copy(openPerformance = valid.copy(entryEquityUsd = null,
            entryEquityReturnPct = null, idleBalanceSampledAt = null)), "All", "all")
        listOf(valid.copy(idleBalanceSampledAt = null), valid.copy(idleBalanceSampledAt = time + 1),
            valid.copy(idleBalanceSampledAt = 0), valid.copy(usesIdleBalances = false)).forEach { bad ->
            assertThrows(DashboardError::class.java) { validateSnapshot(snapshot().copy(openPerformance = bad), "All", "all") }
        }
    }

    @Test fun tickerRejectsWrongSymbolInvalidPriceAndImpossibleTimestamps() {
        val valid = BtcTicker("BTCUSDT", 81000.0, -2.5, time)
        listOf(valid.copy(symbol = "ETHUSDT"), valid.copy(price = 0.0), valid.copy(price = Double.NaN),
            valid.copy(change24hPct = Double.POSITIVE_INFINITY), valid.copy(change24hPct = -100.1),
            valid.copy(updatedAt = time + 1), valid.copy(updatedAt = time - 15_001)).forEach { bad ->
            assertThrows(DashboardError::class.java) { validateSnapshot(snapshot().copy(btcTicker = bad), "All", "all") }
        }
    }

    @Test fun backtestCannotCarryLiveTickerOrEntryCapital() {
        val edge = snapshot(null).copy(historyMode = "edge", dataSource = "edge_csv", live = false, priceUpdatedAt = null)
        validateSnapshot(edge, "All", "all", "edge")
        assertThrows(DashboardError::class.java) { validateSnapshot(edge.copy(btcTicker =
            BtcTicker("BTCUSDT", 81000.0, 2.5, time)), "All", "all", "edge") }
        assertThrows(DashboardError::class.java) { validateSnapshot(edge.copy(openPerformance =
            OpenPerformance(null, null, null, null)), "All", "all", "edge") }
    }
}

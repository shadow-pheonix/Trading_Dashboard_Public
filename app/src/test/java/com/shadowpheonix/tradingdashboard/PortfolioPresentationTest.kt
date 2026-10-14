package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class PortfolioPresentationTest {
    private fun balance(equity: Double = 200.0) = BalanceSnapshot(2, "Main", 1000, 900, 950,
        "ok", equity, listOf(ModelBalance("M1", equity, "ok", 950),
            ModelBalance("M2", null, "not_configured", null), ModelBalance("M3", null, "not_configured", null)))

    @Test fun entryNotionalRetainsTheCombinedBtcSizeAndIgnoresLeverageAndMark() {
        val combined = Position("m1", "M1", "BTCUSDT", "LONG", 0.7,
            (0.2 * 80000 + 0.5 * 82000) / 0.7, 83000.0, 1100.0, 4.0)
        assertEquals(57000.0, positionNotionalUsd(combined)!!, 0.000001)
        assertEquals(0.7, combined.size, 0.0)
        assertEquals(positionNotionalUsd(combined), positionNotionalUsd(combined.copy(markPrice = null, leverage = 10.0)))
        assertNull(positionNotionalUsd(combined.copy(size = Double.POSITIVE_INFINITY)))
    }

    @Test fun modelDirectionKeepsOpposingPositionsExplicitAndFlatModelsVisible() {
        val long = Position("m1", "M1", "BTCUSDT", "LONG", 1.0, 80000.0, null, null, 2.0)
        val short = long.copy(id = "short", side = "SHORT")
        assertEquals("FLAT", positionDirection(emptyList()))
        assertEquals("LONG", positionDirection(listOf(long, long)))
        assertEquals("LONG + SHORT", positionDirection(listOf(short, long)))
    }

    @Test fun originalEquityReturnsAcceptProfitLossAndZeroAndRoundTrip() {
        listOf(200.0, 100.0, 150.0, 0.0).forEach { equity ->
            val pnl = equity - 150.0
            val sample = balance(equity).copy(originalEquity = 150.0, pnl = pnl, returnPct = pnl / 150 * 100)
            validateBalances(sample)
            assertEquals(sample, dashboardJson.decodeFromString<BalanceSnapshot>(dashboardJson.encodeToString(sample)))
        }
        val old = balance()
        validateBalances(old)
        assertNull(dashboardJson.decodeFromString<BalanceSnapshot>(dashboardJson.encodeToString(old)).originalEquity)
    }

    @Test fun incompleteBalancesAndMissingBaselinesCannotClaimAFullReturn() {
        val good = balance().copy(originalEquity = 150.0, pnl = 50.0, returnPct = 100.0 / 3)
        val failed = good.copy(status = "failed", equity = null, sampledAt = null,
            models = good.models.map { it.copy(status = "failed", equity = null, sampledAt = null) })
        validateBalances(failed.copy(pnl = null, returnPct = null))
        listOf(failed, good.copy(originalEquity = null), good.copy(originalEquity = 0.0),
            good.copy(originalEquity = Double.NaN), good.copy(pnl = 60.0),
            good.copy(returnPct = 30.0), good.copy(returnPct = Double.POSITIVE_INFINITY)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { validateBalances(invalid) }
        }
    }
}

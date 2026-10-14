package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class BtcTickerCacheTest {
    private val time = 100_000_000L
    private val ticker = BtcTicker("BTCUSDT", 82000.0, 2.3, time)
    private class Memory : BtcTickerPreferences {
        var text: String? = null
        var writes = 0
        var fail = false
        override fun read(): String? { if (fail) error("Synthetic failure"); return text }
        override fun write(value: String) { if (fail) error("Synthetic failure"); text = value; writes++ }
    }
    private fun snapshot(ticker: BtcTicker? = null) = DashboardSnapshot(instance = "Main",
        generatedAt = time, live = true, period = "All", accounts = listOf(Account("M1", "M1"), Account("M2", "M2"), Account("M3", "M3")),
        metrics = Metrics(), btcTicker = ticker, dataSource = "bot_files_binance")

    @Test fun missingTickerDuringReloadKeepsLastPriceWithoutChangingSnapshot() {
        val initial = DashboardState().withLiveSnapshot(snapshot(ticker))
        val updated = initial.withLiveSnapshot(snapshot())
        assertEquals(ticker, updated.btcTicker)
        assertNull(updated.snapshot!!.btcTicker)
        assertEquals(ticker, updated.withPage(Page.Analytics).withPage(Page.Dashboard).btcTicker)
        assertEquals(ticker, updated.copy(activeId = "another", liveSnapshot = null, snapshot = null).btcTicker)
    }

    @Test fun olderResponsesCannotReplaceNewerMarketData() {
        assertEquals(ticker, newestBtcTicker(ticker, ticker.copy(updatedAt = time - 1)))
        assertEquals(ticker.copy(price = 83000.0), newestBtcTicker(ticker, ticker.copy(price = 83000.0)))
        assertEquals(ticker, newestBtcTicker(null, ticker))
        assertNull(newestBtcTicker(null, null))
    }

    @Test fun priceMovementUsesConsecutiveQuotesRatherThanDayChange() {
        val first = DashboardState().withBtcTicker(ticker)
        assertEquals(BtcPriceDirection.Unknown, first.btcPriceDirection)
        val up = first.withLiveSnapshot(snapshot(ticker.copy(price = 82001.0, change24hPct = -2.3, updatedAt = time + 1)))
        assertEquals(BtcPriceDirection.Up, up.btcPriceDirection)
        val down = up.withBtcTicker(ticker.copy(price = 82000.0, change24hPct = 2.3, updatedAt = time + 2))
        assertEquals(BtcPriceDirection.Down, down.btcPriceDirection)
    }

    @Test fun unchangedMissingAndOlderQuotesKeepTheLastMovementAcrossNavigation() {
        val up = DashboardState().withBtcTicker(ticker)
            .withBtcTicker(ticker.copy(price = 82001.0, updatedAt = time + 2))
        val retained = up.withBtcTicker(ticker.copy(updatedAt = time + 1))
            .withBtcTicker(null).withLiveSnapshot(snapshot())
            .withBtcTicker(up.btcTicker!!.copy(updatedAt = time + 3))
            .withPage(Page.Analytics).withPage(Page.Dashboard)
            .copy(activeId = "another", liveSnapshot = null, snapshot = null)
        assertEquals(82001.0, retained.btcTicker!!.price, 0.0)
        assertEquals(BtcPriceDirection.Up, retained.btcPriceDirection)
    }

    @Test fun reopenedCacheStartsNeutralAndNewLiveQuotesCanSetDirection() {
        val prefs = Memory(); prefs.text = dashboardJson.encodeToString(ticker)
        val reopened = DashboardState(btcTicker = BtcTickerCache(prefs) { time }.load())
        assertEquals(BtcPriceDirection.Unknown, reopened.btcPriceDirection)
        assertEquals(BtcPriceDirection.Down,
            reopened.withBtcTicker(ticker.copy(price = 81999.0, updatedAt = time + 1)).btcPriceDirection)
        // Some provider samples share their timestamp; accepted price changes still count.
        assertEquals(BtcPriceDirection.Up, reopened.withBtcTicker(ticker.copy(price = 82001.0)).btcPriceDirection)
    }

    @Test fun staleOrDisconnectedPricesCannotShowLiveMovement() {
        val live = DashboardState(connectionHealthy = true, now = time + 1)
            .withBtcTicker(ticker).withBtcTicker(ticker.copy(price = 82001.0, updatedAt = time + 1))
        assertEquals(BtcPriceDirection.Up, live.liveBtcPriceDirection)
        assertEquals(BtcPriceDirection.Unknown, live.copy(connectionHealthy = false).liveBtcPriceDirection)
        assertEquals(BtcPriceDirection.Unknown, live.copy(now = time + 15_002).liveBtcPriceDirection)
        assertEquals(BtcPriceDirection.Unknown, live.copy(btcTicker = null).liveBtcPriceDirection)
        assertEquals(BtcPriceDirection.Up, live.copy(now = time + 15_001).liveBtcPriceDirection)
    }

    @Test fun cacheSurvivesReopeningAndWritesAtMostOncePerFifteenSeconds() {
        val prefs = Memory(); var clock = time
        val cache = BtcTickerCache(prefs) { clock }
        cache.save(ticker)
        repeat(14) { clock += 1000; cache.save(ticker.copy(updatedAt = clock)) }
        assertEquals(1, prefs.writes)
        assertEquals(ticker, BtcTickerCache(prefs) { clock }.load())
        clock += 1000
        cache.save(ticker.copy(updatedAt = clock, price = 83000.0))
        assertEquals(2, prefs.writes)
        assertEquals(83000.0, BtcTickerCache(prefs) { clock }.load()!!.price, 0.0)
    }

    @Test fun staleCacheRemainsLastPriceAndExpiresAfterOneDay() {
        val prefs = Memory(); prefs.text = dashboardJson.encodeToString(ticker)
        assertNotNull(BtcTickerCache(prefs) { time + 86_400_000 }.load())
        assertNull(BtcTickerCache(prefs) { time + 86_400_001 }.load())
        assertFalse(btcTickerIsFresh(ticker, time + 15_001))
        assertTrue(btcTickerIsFresh(ticker, time + 15_000))
        assertFalse(btcTickerIsFresh(ticker, time - 1))
    }

    @Test fun malformedFutureAndInvalidCacheCannotBeShown() {
        val prefs = Memory()
        val bad = listOf(ticker.copy(symbol = "ETHUSDT"), ticker.copy(price = 0.0),
            ticker.copy(change24hPct = -100.1), ticker.copy(updatedAt = time + 1), ticker.copy(updatedAt = 0))
        bad.forEach { prefs.text = dashboardJson.encodeToString(it); assertNull(BtcTickerCache(prefs) { time }.load()) }
        listOf("broken", "x".repeat(513)).forEach {
            prefs.text = it; assertNull(BtcTickerCache(prefs) { time }.load())
        }
        val cache = BtcTickerCache(prefs) { time }
        cache.save(ticker.copy(price = Double.NaN)); cache.save(ticker.copy(change24hPct = Double.POSITIVE_INFINITY))
        assertEquals(0, prefs.writes)
    }

    @Test fun cacheFailuresNeverInterruptLiveDataAndLoadedOrderingIsPreserved() {
        val prefs = Memory(); prefs.text = dashboardJson.encodeToString(ticker)
        val cache = BtcTickerCache(prefs) { time }
        assertEquals(ticker, cache.load())
        cache.save(ticker.copy(updatedAt = time - 1)); assertEquals(0, prefs.writes)
        prefs.fail = true
        assertNull(cache.load()); cache.save(ticker)
        assertEquals(ticker, newestBtcTicker(null, ticker))
    }

    @Test fun distinctStopsRoundTripAndInvalidListsAreRejected() {
        val position = Position("m1", "M1", "BTCUSDT", "SHORT", 0.7, 85000.0, 82000.0, 2100.0, 3.0,
            stopPrices = listOf(87160.0, 87169.0))
        val value = snapshot().copy(positions = listOf(position))
        validateSnapshot(value, "All", "all")
        assertEquals(position, dashboardJson.decodeFromString<DashboardSnapshot>(dashboardJson.encodeToString(value)).positions.single())
        listOf(listOf(0.0), listOf(Double.NaN), listOf(2.0, 1.0), listOf(1.0, 1.0), (1..7).map { it.toDouble() }).forEach { bad ->
            assertThrows(DashboardError::class.java) { validateSnapshot(value.copy(positions = listOf(position.copy(stopPrices = bad))), "All", "all") }
        }
        assertThrows(DashboardError::class.java) { validateSnapshot(value.copy(positions = listOf(position.copy(stopPrice = 87160.0))), "All", "all") }
        validateSnapshot(value.copy(positions = listOf(position.copy(stopPrice = 87160.0, stopPrices = emptyList()))), "All", "all")
    }
}

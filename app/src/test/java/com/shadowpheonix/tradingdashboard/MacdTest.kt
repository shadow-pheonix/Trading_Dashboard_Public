package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MacdTest {
    private val connection = Connection("main", "Main", DASHBOARD_URL, "x".repeat(43))
    private fun snapshot(status: String = "ok"): MacdSnapshot {
        val time = 1800000000000L
        val target = time / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL
        return MacdSnapshot(2, "Main", time, "binance_spot_closed", "BTCUSDT", 15, status, time,
            (1..3).map { n -> MacdModel("M$n", (n + 1) * 150, (n + 1) * 450, (n + 1) * 150,
                (0..95).map { i -> MacdPoint(target - (95 - i) * MACD_INTERVAL, i.toDouble(), 20.0, i - 20.0) }) })
    }
    @Test fun strictThreeModelsClosedCandlesAndStaleness() {
        val value = snapshot(); validateMacd(value)
        assertFalse(macdIsStale(value, value.generatedAt))
        assertTrue(macdIsStale(value, value.generatedAt + MACD_INTERVAL))
        assertTrue(macdIsStale(value.copy(status = "stale"), value.generatedAt))
        val first = value.models.first()
        val point = first.points.first()
        val invalid = listOf(value.copy(source = "binance_futures"), value.copy(models = value.models.reversed()),
            value.copy(models = listOf(first)), value.copy(models = value.models.map { it.copy(signalPeriod = 9) }),
            value.copy(models = value.models.map { it.copy(points = listOf(point)) }),
            value.copy(models = value.models.map { it.copy(points = it.points.map { p -> p.copy(histogram = Double.NaN) }) }),
            value.copy(models = value.models.map { it.copy(points = it.points.map { p -> p.copy(histogram = p.histogram + 1) }) }),
            value.copy(models = value.models.map { it.copy(points = it.points.map { p -> p.copy(timestamp = p.timestamp + 1) }) }),
            value.copy(models = value.models.map { it.copy(points = it.points.map { p -> p.copy(timestamp = p.timestamp + MACD_INTERVAL) }) }))
        invalid.forEach { try { validateMacd(it); fail("Invalid chart accepted") } catch (_: IllegalArgumentException) { } }
        validateMacd(value.copy(status = "loading", sampledAt = null, models = emptyList()))
    }
    @Test fun botCalculatedSourceUsesSameStrictChartAndStalenessValidation() {
        val bot = snapshot().copy(source = "bot_spot_closed")
        validateMacd(bot)
        assertFalse(macdIsStale(bot, bot.generatedAt))
        assertTrue(macdIsStale(bot, bot.generatedAt + MACD_INTERVAL))
        assertThrows(IllegalArgumentException::class.java) { validateMacd(bot.copy(source = "estimated")) }
    }
    @Test fun requestUsesExistingAuthenticatedRouteWithoutTradingOrHistoryFilters() {
        val request = macdRequest(connection)
        assertEquals("/v2/dashboard", request.url.encodedPath)
        assertEquals("view=macd", request.url.encodedQuery)
        assertEquals("Bearer ${connection.accessKey}", request.header("Authorization"))
        assertFalse(request.url.toString().contains(connection.accessKey))
    }
    @Test fun onlyStrategiesInForegroundFetchesAndNavigationCancelsPendingWork() = runBlocking {
        var fetches = 0
        val values = mutableListOf<MacdSnapshot>()
        val session = MacdSession(this, { fetches++; snapshot() }, { values.add(it) }, { fail(it) },
            pause = { awaitCancellation() })
        suspend fun drain() { repeat(12) { yield() } }
        try {
            session.select(connection, false); drain(); assertEquals(0, fetches)
            session.select(connection, true); drain(); assertEquals(1, fetches)
            session.select(connection.copy(label = "Server name"), true); drain(); assertEquals(1, fetches)
            session.select(connection, false); drain(); assertEquals(1, fetches)
            session.select(connection, true); drain(); assertEquals(2, fetches)
            session.select(connection, true, refresh = true); drain(); assertEquals(3, fetches)
            session.select(connection.copy(id = "vinay"), true); drain(); assertEquals(4, fetches)
            session.close(); drain(); assertEquals(4, values.size)
        } finally { session.close() }
    }
}

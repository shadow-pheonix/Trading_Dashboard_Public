package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class CandleChartTest {
    private val now = 1800000000000L
    private val last = now / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL
    private fun snapshot() = MacdSnapshot(2, "Main", now, "bot_spot_closed", "BTCUSDT", 15,
        "loading", candles = (0..95).map { PriceCandle(last - (95-it)*MACD_INTERVAL, 80000.0, 80100.0, 79900.0, 80050.0) },
        candleStatus = "ok", candleSampledAt = now)

    @Test fun validCandlesCanAppearBeforeMacdAndLegacyDocumentsRemainValid() {
        val value = snapshot(); validateMacd(value)
        assertFalse(candlesAreStale(value, now))
        assertTrue(candlesAreStale(value, now+MACD_INTERVAL))
        assertEquals(value, dashboardJson.decodeFromString<MacdSnapshot>(dashboardJson.encodeToString(MacdSnapshot.serializer(), value)))
        validateMacd(value.copy(candles = emptyList(), candleStatus = null, candleSampledAt = null))
        assertTrue(candlesAreStale(value.copy(candleStatus = "stale"), now))
    }

    @Test fun malformedCandlesAndFreshnessAreRejected() {
        val value = snapshot(); val first = value.candles.first()
        val invalid = listOf(
            value.copy(candles = value.candles.drop(1)), value.copy(candleStatus = null),
            value.copy(candleSampledAt = now+1), value.copy(candleStatus = "loading"),
            value.copy(candles = value.candles.map { it.copy(timestamp = it.timestamp+MACD_INTERVAL) }),
        ) + listOf(first.copy(open = 0.0), first.copy(high = 79999.0), first.copy(low = 80001.0),
            first.copy(close = Double.NaN), first.copy(close = Double.POSITIVE_INFINITY),
            first.copy(timestamp = first.timestamp+1)).map { bad -> value.copy(candles = listOf(bad)+value.candles.drop(1)) }
        invalid.forEach { bad -> assertThrows(IllegalArgumentException::class.java) { validateMacd(bad) } }
    }

    @Test fun previewDensityAndFlatPriceRangesRemainReadable() {
        assertEquals(60, previewCandleCount(300f)); assertEquals(96, previewCandleCount(900f))
        assertEquals(1, previewCandleCount(0f))
        val range = priceRange(listOf(PriceCandle(last, 100.0, 100.0, 100.0, 100.0)))
        assertTrue(range.high > range.low)
        assertEquals(0.5f, range.fraction(100.0), 0.001f)
    }

    @Test fun chartRequestSharesAuthenticationAndLegacyRouteIsPreserved() {
        val connection = Connection("main", "Main", "https://dashboard.example.test", "x".repeat(43))
        assertEquals("view=chart", chartRequest(connection).url.encodedQuery)
        assertEquals("view=macd", macdRequest(connection).url.encodedQuery)
        assertEquals("Bearer ${connection.accessKey}", chartRequest(connection).header("Authorization"))
        assertFalse(chartRequest(connection).url.toString().contains(connection.accessKey))
    }
}

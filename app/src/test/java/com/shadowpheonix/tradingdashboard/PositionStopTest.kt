package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class PositionStopTest {
    private fun position(stops: List<Double> = emptyList(), single: Double? = null) =
        Position("m1", "M1", "BTCUSDT", "SHORT", 0.7, 85000.0, 82000.0, 2100.0, 3.0,
            stopPrice = single, stopPrices = stops)

    @Test fun nearbyStopsShowMidpointAndPreserveExactSavedValues() {
        val p = position(listOf(87160.0, 87169.0))
        assertEquals(87164.5, positionStopMidpoint(p)!!, 0.0)
        assertEquals(listOf(87160.0, 87169.0), p.stopPrices)
    }
    @Test fun toleranceUsesSpreadOverMidpointWithInclusiveBoundary() {
        assertEquals(100000.0, positionStopMidpoint(position(listOf(99950.0, 100050.0)))!!, 0.0)
        assertNull(positionStopMidpoint(position(listOf(99949.9, 100050.1))))
        assertNull(positionStopMidpoint(position(listOf(80000.0, 87000.0))))
        assertEquals(85000.0, positionStopMidpoint(position(listOf(85000.0)))!!, 0.0)
    }
    @Test fun unavailableStopsStayUnavailableAndOlderServersRemainSupported() {
        assertNull(positionStopMidpoint(position()))
        assertEquals(85000.0, positionStopMidpoint(position(single = 85000.0))!!, 0.0)
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(positionStopMidpoint(position(listOf(85000.0, bad))))
    }
}

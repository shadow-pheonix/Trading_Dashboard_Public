package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class ChartViewportTest {
    @Test fun initialWindowShowsLatestReadableCandlesAndBoundsPreventEmptyPanning() {
        assertEquals(ChartViewport(36.0, 60.0), ChartViewport.latest(96))
        assertEquals(ChartViewport(0.0, 96.0), ChartViewport(-500.0, 1000.0).bounded(96))
        assertEquals(ChartViewport(84.0, 12.0), ChartViewport(500.0, 1.0).bounded(96))
        assertEquals(ChartViewport(0.0, 4.0), ChartViewport.latest(4))
    }

    @Test fun pinchKeepsCandleUnderCentroidAndPanUsesTheSharedTimeline() {
        val before = ChartViewport(20.0, 40.0)
        val after = before.transform(96, 2.0, 0.0, 0.25)
        assertEquals(ChartViewport(25.0, 20.0), after)
        assertEquals(before.first + before.count * 0.25, after.first + after.count * 0.25, 0.00001)
        assertEquals(ChartViewport(23.0, 20.0), after.transform(96, 1.0, 0.1, 0.5))
        assertEquals(0.0, after.transform(96, 1.0, 100.0, 0.5).first, 0.0)
        assertEquals(76.0, after.transform(96, 1.0, -100.0, 0.5).first, 0.0)
        for (zoom in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertEquals(after, after.transform(96, zoom, 0.0, 0.5))
    }

    @Test fun zoomButtonsKeepLatestCandleVisibleAndHaveFiniteLimits() {
        val zoomed = ChartViewport.latest(96).zoomControl(96, 2.0)
        assertEquals(ChartViewport(66.0, 30.0), zoomed)
        assertEquals(ChartViewport(84.0, 12.0), zoomed.zoomControl(96, 1e10))
        assertEquals(ChartViewport(0.0, 96.0), zoomed.zoomControl(96, 1e-10))
    }

    @Test fun fractionalCrosshairMapsToSameCandleFromEitherPaneAndClampsEdges() {
        val viewport = ChartViewport(1.5, 12.0)
        assertEquals(1, viewport.indexAt(-1.0, 96))
        assertEquals(7, viewport.indexAt(0.5, 96))
        assertEquals(13, viewport.indexAt(1.0, 96))
        assertEquals(95, ChartViewport.latest(96).indexAt(10.0, 96))
    }

    @Test fun rightBorderSelectsLastVisibleCandleInsteadOfAnOffscreenCandle() {
        assertEquals(49, ChartViewport(20.0, 30.0).indexAt(1.0, 96))
        assertEquals(49, ChartViewport(20.0, 30.0).indexAt(2.0, 96))
        assertEquals(50, ChartViewport(20.0, 30.5).indexAt(1.0, 96))
    }

    @Test fun newCandleFollowsLatestButPannedHistoryKeepsItsAbsoluteTime() {
        val start = 1800000000000L / MACD_INTERVAL * MACD_INTERVAL
        val historical = ChartViewport(20.0, 30.0)
        val moved = historical.rebased(start, start+MACD_INTERVAL, 96, 96)
        assertEquals(ChartViewport(19.0, 30.0), moved)
        assertEquals(start+20*MACD_INTERVAL, start+MACD_INTERVAL+moved.first.toLong()*MACD_INTERVAL)
        assertEquals(ChartViewport.latest(96), ChartViewport.latest(96).rebased(start, start+MACD_INTERVAL, 96, 96))
    }

    @Test fun mismatchedSeriesAreJoinedByTimestampWithoutInventingValues() {
        val point = MacdPoint(2*MACD_INTERVAL, 10.0, 8.0, 2.0)
        val model = MacdModel("M1", 300, 900, 300, listOf(point))
        assertEquals(listOf(null, point, null), alignedMacd(listOf(MACD_INTERVAL, 2*MACD_INTERVAL, 3*MACD_INTERVAL), model))
        assertEquals(listOf(null), alignedMacd(listOf(MACD_INTERVAL), null))
    }

    @Test fun priceAndMacdShareWidthAndMaintainSeventyThirtyAcrossScreenSizes() {
        for ((width, height) in listOf(360f to 640f, 800f to 240f, 280f to 400f)) {
            val layout = chartPaneLayout(width, height, 64f, 28f)
            assertEquals(width-64f, layout.plotWidth, 0.001f)
            assertEquals(0.7f, layout.priceHeight/layout.plotHeight, 0.00001f)
            assertEquals(0.3f, layout.macdHeight/layout.plotHeight, 0.00001f)
        }
    }
}

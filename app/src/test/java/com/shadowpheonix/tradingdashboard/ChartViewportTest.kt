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
        val old=(0..95).map {start+it*MACD_INTERVAL}
        val next=(1..96).map {start+it*MACD_INTERVAL}
        val historical = ChartViewport(20.0, 30.0)
        val moved = ChartWindow().moved(historical,old).viewport(next)
        assertEquals(ChartViewport(19.0, 30.0), moved)
        assertEquals(start+20*MACD_INTERVAL, start+MACD_INTERVAL+moved.first.toLong()*MACD_INTERVAL)
        assertEquals(ChartViewport.latest(96), ChartWindow().viewport(next))
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

    @Test fun mainBtcChartGivesTheEntirePlotToPriceWithNoMacdPane() {
        for ((width,height) in listOf(360f to 640f,800f to 240f,280f to 400f)) {
            val layout=chartPaneLayout(width,height,64f,28f,showMacd=false)
            assertEquals(width-64f,layout.plotWidth,0.001f)
            assertEquals(height-28f,layout.priceHeight,0.001f)
            assertEquals(0f,layout.macdHeight,0f)
        }
    }

    @Test fun automaticPriceRangeFitsCandlesWithoutFittingADistantStop() {
        val candles=(0..59).map { PriceCandle(it*MACD_INTERVAL,76600.0,76800.0,76400.0,76700.0) }
        val range=chartPriceRange(candles,ChartViewport.latest(60))
        assertEquals(priceRange(candles),range)
        assertTrue(range.high<83223.0)
        assertTrue(range.low<76400.0 && range.high>76800.0)
        val manual=ChartScale().transformed(range,0.02,0.0)
        assertTrue(manual.range(range).high>83223.0) // A deliberate axis drag can reveal the stop.
    }

    @Test fun manualScalePreservesItsRangeAcrossQuotesAndHorizontalWindowsUntilReset() {
        val auto=ChartRange(76000.0,77000.0)
        val manual=ChartScale().transformed(auto,0.5,0.2)
        assertFalse(manual.automatic)
        assertEquals(ChartRange(75900.0,77900.0),manual.range(auto))
        assertEquals(manual.range(auto),manual.range(ChartRange(70000.0,85000.0)))
        assertEquals(ChartRange(70000.0,85000.0),ChartScale().range(ChartRange(70000.0,85000.0)))
    }

    @Test fun verticalPinchKeepsThePriceUnderItsCentroidAndDragMovesBothBounds() {
        val auto=ChartRange(-1000.0,1000.0)
        val zoomed=ChartScale().transformed(auto,2.0,0.0,0.25)
        assertEquals(ChartRange(-250.0,750.0),zoomed.range(auto))
        assertEquals(auto.fraction(500.0),zoomed.range(auto).fraction(500.0),0.00001f)
        val panned=zoomed.transformed(auto,1.0,0.1)
        assertEquals(ChartRange(-150.0,850.0),panned.range(auto))
        assertEquals(zoomed,panned.transformed(auto,1.0,-0.1))
    }

    @Test fun invalidScaleInputIsIgnoredAndExtremeGesturesStayFinite() {
        val auto=ChartRange(76000.0,77000.0)
        val manual=ChartScale(75000.0,79000.0)
        for (factor in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertEquals(manual,manual.transformed(auto,factor,0.0))
        assertEquals(manual,manual.transformed(auto,1.0,Double.NaN))
        assertEquals(manual,manual.transformed(auto,1.0,0.0,Double.NaN))
        for (factor in listOf(1e-300,1e300)) {
            val range=manual.transformed(auto,factor,1e10).range(auto)
            assertTrue(range.low.isFinite() && range.high.isFinite() && range.high>range.low)
        }
        val far=manual.transformed(auto,1e-300,1e10)
        val tiny=far.transformed(auto,1e300,0.0)
        assertFalse(tiny.automatic)
        assertTrue(ChartScale(Double.NaN,0.0).automatic)
        assertTrue(ChartScale(1.0,0.0).automatic)
    }

    @Test fun timestampWindowRebasesInTheFirstRenderWithoutFollowingAfterManualPan() {
        val start=1800000000000L/MACD_INTERVAL*MACD_INTERVAL
        val old=(0..255).map {start+it*MACD_INTERVAL}
        val next=(1..256).map {start+it*MACD_INTERVAL}
        val manual=ChartWindow().moved(ChartViewport(120.25,40.0),old)
        assertEquals(ChartViewport(119.25,40.0),manual.viewport(next))
        assertEquals(manual.firstTime,next.first()+manual.viewport(next).first*MACD_INTERVAL,0.001)
        val atEdge=ChartWindow().moved(ChartViewport.latest(256),old)
        assertFalse(atEdge.following)
        assertEquals(ChartViewport(195.0,60.0),atEdge.viewport(next))
        assertEquals(ChartViewport.latest(256),ChartWindow().viewport(next))
        assertEquals(60.0,ChartWindow().viewport(old).count,0.0)
    }

    @Test fun reservedEmptySlotCannotIndexPastTheClosedCandleData() {
        val candles=(0..254).map {PriceCandle(it*MACD_INTERVAL,100.0,110.0,90.0,101.0)}
        val viewport=ChartViewport.latest(256,12.0)
        assertEquals(candles.takeLast(11),chartVisible(candles,viewport))
        assertEquals(priceRange(candles.takeLast(11)),chartPriceRange(candles,viewport))
        assertTrue(chartVisible(emptyList<PriceCandle>(),viewport).isEmpty())
        assertEquals(ChartRange(0.0,1.0),chartPriceRange(emptyList(),viewport))
    }
}

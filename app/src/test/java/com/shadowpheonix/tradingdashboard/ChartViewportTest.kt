package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class ChartViewportTest {
    @Test fun initialWindowShowsLatestReadableCandlesAndFuturePanningIsAllowed() {
        assertEquals(ChartViewport(36.0, 60.0), ChartViewport.latest(96))
        assertEquals(ChartViewport(0.0, 96.0), ChartViewport(-500.0, 1000.0).bounded(96))
        assertEquals(ChartViewport(500.0, 12.0), ChartViewport(500.0, 1.0).bounded(96))
        assertEquals(ChartViewport(0.0, 4.0), ChartViewport.latest(4))
    }

    @Test fun pinchKeepsCandleUnderCentroidAndPanUsesTheSharedTimeline() {
        val before = ChartViewport(20.0, 40.0)
        val after = before.transform(96, 2.0, 0.0, 0.25)
        assertEquals(ChartViewport(25.0, 20.0), after)
        assertEquals(before.first + before.count * 0.25, after.first + after.count * 0.25, 0.00001)
        assertEquals(ChartViewport(23.0, 20.0), after.transform(96, 1.0, 0.1, 0.5))
        assertEquals(0.0, after.transform(96, 1.0, 100.0, 0.5).first, 0.0)
        assertEquals(2025.0, after.transform(96, 1.0, -100.0, 0.5).first, 0.0)
        for (zoom in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertEquals(after, after.transform(96, zoom, 0.0, 0.5))
    }

    @Test fun zoomButtonsKeepLatestCandleVisibleAndHaveFiniteLimits() {
        val zoomed = ChartViewport.latest(96).zoomControl(96, 2.0)
        assertEquals(ChartViewport(66.0, 30.0), zoomed)
        assertEquals(ChartViewport(84.0, 12.0), zoomed.zoomControl(96, 1e10))
        assertEquals(ChartViewport(0.0, 96.0), zoomed.zoomControl(96, 1e-10))
    }

    @Test fun fullScreenZoomFitsAllRetainedSlotsAndKeepsShortHistoryAndZoomInBounds() {
        for (total in listOf(0, 1, 4, 12, 96, 255, 256, 300)) {
            val available = total.coerceAtLeast(1).toDouble()
            val maximum = minOf(256.0, available)
            val minimum = minOf(12.0, available)
            val initial = ChartViewport.latest(total)
            val button = initial.zoomControl(total, 1e-10)
            val pinch = initial.transform(total, 1e-10, 0.0, 0.25)
            assertEquals(maximum, button.count, 0.0)
            assertEquals(maximum, pinch.count, 0.0)
            assertEquals(maximum, ChartViewport(-1e10, 1e10).bounded(total).count, 0.0)
            assertEquals(minimum, button.zoomControl(total, 1e10).count, 0.0)
            assertEquals(minimum, pinch.transform(total, 1e10, 0.0, 0.25).count, 0.0)
            assertEquals(0.0, button.transform(total, 1.0, 100.0, 0.5).first, 0.0)
            assertTrue(button.transform(total, 1.0, -100.0, 0.5).first > available)
        }
        val start = 1800000000000L / MACD_INTERVAL * MACD_INTERVAL
        val times = (0..255).map { start+it*MACD_INTERVAL }
        val viewport = ChartViewport.latest(256).zoomControl(256, 1e-10)
        assertEquals(times, chartVisible(times, viewport))
        assertEquals(0, viewport.indexAt(0.0, 256))
        assertEquals(255, viewport.indexAt(1.0, 256))
        val window = ChartWindow().moved(viewport, times)
        assertEquals(viewport, window.viewport(times))
        assertEquals(256.0, window.viewport(times.map { it+MACD_INTERVAL }).count, 0.0)
    }

    @Test fun futurePanLeavesActualBarsOnTheLeftAndBlankSpaceHasNoSelectableData() {
        val latest=ChartViewport.latest(256)
        val future=latest.transform(256,1.0,-0.5,0.5)
        assertEquals(ChartViewport(226.0,60.0),future)
        assertEquals(226..255,future.visibleIndices(256))
        assertEquals(255,future.indexAt(0.49,256))
        assertNull(future.indexAt(0.75,256))
        val allFuture=future.transform(256,1.0,-1.0,0.5)
        assertTrue(allFuture.visibleIndices(256).isEmpty())
        assertTrue(chartVisible((0..255).toList(),allFuture).isEmpty())
        assertNull(allFuture.indexAt(0.0,256))
        assertNull(allFuture.indexAt(1.0,256))
        assertNull(latest.indexAt(0.0,0))
        assertNull(latest.indexAt(Double.NaN,256))
        val zoomed=future.zoomControl(256,2.0)
        assertEquals(future.first+future.count/2,zoomed.first+zoomed.count/2,0.00001)
        val extreme=allFuture.transform(256,1.0,-1e300,0.5)
        assertTrue(extreme.first.isFinite() && extreme.first+extreme.count < Int.MAX_VALUE)
    }

    @Test fun futureAxisExtendsFifteenMinuteTimesWithoutInventingCandlesOrMacd() {
        val times=(0..255).map {1800000000000L+it*MACD_INTERVAL}
        assertEquals(times.last(),chartTimestamp(times,255))
        assertEquals(times.last()+MACD_INTERVAL,chartTimestamp(times,256))
        assertEquals(times.first()+300*MACD_INTERVAL,chartTimestamp(times,300))
        assertNull(chartTimestamp(emptyList(),256))
        assertNull(chartTimestamp(listOf(Long.MAX_VALUE),1))
        val future=ChartViewport(226.0,60.0)
        assertEquals(times.takeLast(30),chartVisible(times,future))
        val empty=ChartViewport(300.0,60.0)
        val candles=(0..255).map {PriceCandle(times[it],100.0,110.0,90.0,105.0)}
        val macd=times.map {MacdPoint(it,-4.0,-3.0,-1.0)}
        assertTrue(chartVisible(candles,empty).isEmpty())
        assertTrue(chartVisible(macd,empty).isEmpty())
        assertEquals(chartPriceRange(candles,ChartViewport.latest(256)),chartPriceRange(candles,empty))
        assertEquals(chartMacdRange(macd,ChartViewport.latest(256)),chartMacdRange(macd,empty))
    }

    @Test fun futureTimestampWindowSurvivesRolloverAndFollowLatestRestoresDefaultGap() {
        val times=(0..255).map {1800000000000L+it*MACD_INTERVAL}
        val future=ChartViewport(226.0,60.0)
        val window=ChartWindow().moved(future,times)
        assertFalse(window.following)
        assertEquals(future,window.viewport(times))
        val next=times.map {it+MACD_INTERVAL}
        assertEquals(ChartViewport(225.0,60.0),window.viewport(next))
        assertEquals(window.firstTime,next.first()+window.viewport(next).first*MACD_INTERVAL,0.001)
        assertEquals(ChartViewport.latest(256),ChartWindow().viewport(next))
        assertEquals(future,future.transform(256,Double.NaN,0.0,0.5))
        assertEquals(future,future.transform(256,1.0,Double.NaN,0.5))
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

    @Test fun pannedCandlesInsideDefaultGapStayVisibleSelectableAndIncludedInScales() {
        val times=(0..255).map {1800000000000L+it*MACD_INTERVAL}
        for ((width,height) in listOf(360f to 640f,800f to 240f,280f to 400f)) {
            for (showMacd in listOf(false,true)) {
                val layout=chartPaneLayout(width,height,64f,28f,showMacd)
                val content=chartContentWidth(layout.plotWidth,56f)
                val nominal=ChartViewport(100.0,60.0)
                val inGap=times.indices.filter {nominal.candleX(it,content)>content && nominal.candleX(it,content)<layout.plotWidth}
                assertTrue(inGap.isNotEmpty())
                val index=inGap[inGap.size/2]
                val full=nominal.forPlot(layout.plotWidth,content)
                assertTrue("A real candle in the right gap must remain drawable",index in full.visibleIndices(times.size))
                assertEquals(nominal.candleX(index,content),full.candleX(index,layout.plotWidth),0.001f)
                val x=nominal.candleX(index,content)
                assertEquals(index,full.indexAt((x/layout.plotWidth).toDouble(),times.size))
                assertEquals(x,stopLineStart(index,full,layout.plotWidth)!!,0.001f)
                val candles=times.indices.map {PriceCandle(times[it],100.0,if(it==index)200.0 else 110.0,90.0,105.0)}
                assertTrue(candles[index] in chartVisible(candles,full))
                assertTrue(chartPriceRange(candles,full).high>200.0)
                val points=times.indices.map {if(it==index)MacdPoint(times[it],25.0,20.0,5.0) else null}
                assertTrue(chartMacdRange(points,full).high>25.0)
                assertTrue(full.visibleIndices(times.size).all {full.candleX(it,layout.plotWidth)<layout.plotWidth+layout.plotWidth/full.count})
            }
        }
    }

    @Test fun fullPlotDoesNotFillDefaultGapWithFakeDataOrChangeCandleSpacing() {
        val times=(0..255).map {it*MACD_INTERVAL}
        for (width in listOf(216f,296f,736f)) {
            val content=chartContentWidth(width,56f)
            for (count in listOf(12.0,60.0,256.0)) {
                val nominal=ChartViewport.latest(256,count)
                val full=nominal.forPlot(width,content)
                assertEquals(count,nominal.count,0.0)
                assertEquals(content/count,width/full.count,0.00001)
                assertEquals(chartVisible(times,nominal),chartVisible(times,full))
                assertNull(full.indexAt(1.0,256))
                assertTrue(full.candleX(255,width)<content)
            }
        }
    }

    @Test fun pinchInRightGapKeepsItsCandleUnderTheFingers() {
        for (width in listOf(296f,736f)) {
            val content=chartContentWidth(width,56f)
            val before=ChartViewport(100.0,60.0)
            val index=(0..255).first {before.candleX(it,content)>content && before.candleX(it,content)<width}
            val x=before.candleX(index,content)
            val zoomed=before.transform(256,2.0,0.0,(x/content).toDouble())
            assertEquals(x,zoomed.candleX(index,content),0.001f)
        }
    }

    @Test fun rightMarginKeepsLiveCandleAwayFromAxisInBothOrientationsAndAllZoomLevels() {
        for ((width,height) in listOf(360f to 640f,800f to 240f,280f to 400f)) {
            for (showMacd in listOf(false,true)) {
                val layout=chartPaneLayout(width,height,64f,28f,showMacd)
                val content=chartContentWidth(layout.plotWidth,56f)
                assertTrue(layout.plotWidth-content>=43f)
                assertTrue(content>=layout.plotWidth*0.8f-0.001f)
                for (count in listOf(12.0,60.0,256.0)) {
                    val viewport=ChartViewport.latest(256,count)
                    val last=viewport.candleX(255,content)
                    assertTrue(last<content && last<layout.plotWidth-40f)
                    assertEquals(255,viewport.indexAt((last/content).toDouble(),256))
                    assertEquals(last,stopLineStart(255,viewport,content)!!,0.001f)
                    assertEquals(count,viewport.count,0.0)
                }
            }
        }
        assertEquals(1f,chartContentWidth(0f,56f),0f)
        assertEquals(80f,chartContentWidth(100f,1000f),0f)
        assertEquals(100f,chartContentWidth(100f,0f),0f)
        assertEquals(100f,chartContentWidth(100f,-1f),0f)
    }

    @Test fun insetCoordinatesKeepInspectionPinchAndPanOnTheSameCandles() {
        for (plotWidth in listOf(296f,736f)) {
            val content=chartContentWidth(plotWidth,56f)
            val before=ChartViewport(20.0,40.0)
            val x=before.candleX(30,content)
            val anchor=(x/content).toDouble()
            assertEquals(30,before.indexAt(anchor,256))
            val zoomed=before.transform(256,2.0,0.0,anchor)
            assertEquals(x,zoomed.candleX(30,content),0.001f)
            val panned=before.transform(256,1.0,(24f/content).toDouble(),anchor)
            assertEquals(x+24f,panned.candleX(30,content),0.001f)
            assertEquals(30,panned.indexAt(((x+24f)/content).toDouble(),256))
        }
        val times=(0..255).map {it*MACD_INTERVAL}
        val window=ChartWindow().moved(ChartViewport(100.0,60.0),times)
        for (plotWidth in listOf(296f,736f)) {
            val content=chartContentWidth(plotWidth,56f)
            val view=window.viewport(times)
            assertEquals(130,view.indexAt((view.candleX(130,content)/content).toDouble(),256))
        }
        assertEquals(ChartViewport(99.0,60.0),window.viewport(times.map {it+MACD_INTERVAL}))
        assertEquals(times,chartVisible(times,ChartViewport.latest(256,256.0)))
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

    @Test fun plotPinchDoesNotSqueezeUnlockedVerticalRangesEvenAtHorizontalZoomLimit() {
        val auto=ChartRange(76000.0,80000.0)
        val initial=ChartScale(74000.0,82000.0)
        var price=initial
        var indicator=ChartScale(-1000.0,1000.0)
        val indicatorAuto=ChartRange(-500.0,500.0)
        var viewport=ChartViewport.latest(256)
        repeat(40) {
            viewport=viewport.transform(256,0.8,0.0,0.5)
            price=price.fromPlotGesture(auto,0.8,0.0)
            indicator=indicator.fromPlotGesture(indicatorAuto,0.8,0.0)
        }
        assertEquals(256.0,viewport.count,0.0)
        assertEquals(initial,price)
        assertEquals(ChartScale(-1000.0,1000.0),indicator)
        for (zoom in listOf(0.5,1.0,2.0)) {
            val panned=initial.fromPlotGesture(auto,zoom,0.1)
            assertEquals(ChartRange(74800.0,82800.0),panned.range(auto))
            assertEquals(8000.0,panned.high-panned.low,0.00001)
        }
        for (zoom in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertEquals(initial,initial.fromPlotGesture(auto,zoom,0.1))
        assertEquals(initial,initial.fromPlotGesture(auto,1.0,Double.NaN))
        assertTrue(ChartScale().fromPlotGesture(auto,0.8,0.1).automatic)
        assertTrue(initial.transformed(auto,0.5,0.0).high-initial.transformed(auto,0.5,0.0).low>8000.0)
    }

    @Test fun explicitVerticalScaleKeepsItsAnchorAndDragMovesBothBounds() {
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

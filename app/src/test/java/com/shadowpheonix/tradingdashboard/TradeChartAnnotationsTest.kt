package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class TradeChartAnnotationsTest {
    private val now = 1800000000000L
    private val first = now / MACD_INTERVAL * MACD_INTERVAL - 96*MACD_INTERVAL
    private val times = (0..95).map { first+it*MACD_INTERVAL }
    private fun trade(wallet: String = "A", enteredAt: Long = times[20]+5000) =
        ChartTradeAnnotation(if (wallet=="A") "a".repeat(24) else "b".repeat(24), "M1", wallet, "LONG", enteredAt, 80120.0, 79640.0, "confirmed")
    private fun snapshot() = MacdSnapshot(2,"Main",now,"bot_spot_closed","BTCUSDT",15,"loading",
        tradeAnnotations=listOf(trade()), annotationStatus="ok", annotationSampledAt=now)

    @Test fun strictMetadataAndOptionalLegacyPayloads() {
        val value = snapshot(); validateMacd(value)
        validateMacd(value.withoutTradeAnnotations())
        val invalid = listOf(value.copy(annotationSampledAt=now+1), value.copy(annotationStatus=null),
            value.copy(tradeAnnotations=listOf(trade(),trade()))) + listOf(
            trade().copy(enteredAt=now+1), trade().copy(entryPrice=Double.NaN), trade().copy(modelId="M4"),
            trade().copy(wallet="private wallet"), trade().copy(side="BUY"), trade().copy(id="private"),
            trade().copy(stopPrice=0.0), trade().copy(stopState="unknown"), trade().copy(stopPrice=null)
        ).map { bad -> value.copy(tradeAnnotations=listOf(bad)) }
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { validateMacd(it) } }
        validateMacd(value.copy(tradeAnnotations=listOf(trade().copy(stopPrice=81000.0)))) // A profitable moved stop is valid.
    }

    @Test fun entryMapsToOpeningBucketWithoutClampingOldOrFormingEntriesToVisibleCandles() {
        assertEquals(20, entryCandleIndex(trade(),times))
        assertEquals(21, entryCandleIndex(trade(enteredAt=times[21]),times))
        assertEquals(-1, entryCandleIndex(trade(enteredAt=first-1),times))
        assertNull(entryCandleIndex(trade(enteredAt=times.last()+MACD_INTERVAL),times))
        assertTrue(entryChartMarkers(listOf(trade(enteredAt=first-1)),times).isEmpty())
    }

    @Test fun stopRayBeginsAtEntryCenterAndOnlyClipsAtLeftWhenEntryIsEarlier() {
        val view = ChartViewport(10.0,20.0)
        assertEquals(55f, stopLineStart(15,view,200f)!!,0.001f)
        assertEquals(0f, stopLineStart(5,view,200f)!!,0.001f)
        assertNull(stopLineStart(30,view,200f))
        assertEquals(110f, stopLineStart(15,view,400f)!!,0.001f)
    }

    @Test fun sameCandleWalletsShareAnArrowWithoutInventingAnAverageFillPrice() {
        val marker=entryChartMarkers(listOf(trade(),trade("B").copy(entryPrice=80125.0)),times).single()
        assertEquals(20,marker.index)
        assertEquals("LONG A/B 2 entries",entryAnnotationLabel(marker))
        assertEquals("SL A/B 79,640",stopAnnotationLabel(marker.trades))
        val opposite=entryChartMarkers(listOf(trade(),trade("B").copy(side="SHORT")),times)
        assertEquals(2,opposite.size)
        assertEquals("SL? A 79,640",stopAnnotationLabel(listOf(trade().copy(stopState="pending"))))
    }

    @Test fun mainChartIncludesAllModelsWithoutMergingSameCandleArrowsAcrossModels() {
        val trades = listOf(trade(),trade("B"),trade().copy(id="c".repeat(24),modelId="M2"),
            trade().copy(id="d".repeat(24),modelId="M3",side="SHORT"))
        val value=snapshot().copy(tradeAnnotations=trades)
        assertEquals(trades,visibleTradeAnnotations(value,BTC_CHART_ID,now,false,false))
        assertEquals(2,visibleTradeAnnotations(value,"M1",now,false,false).size)
        val markers=entryChartMarkers(trades,times)
        assertEquals(3,markers.size)
        assertEquals(setOf("M1","M2","M3"),markers.map { it.trades.first().modelId }.toSet())
        assertTrue(markers.all { it.index==20 && it.trades.map { t -> t.modelId }.distinct().size==1 })
        val m1=markers.first { it.trades.first().modelId=="M1" }
        assertEquals("M1 LONG A/B 80,120",entryAnnotationLabel(m1,includeModel=true))
        val stops=stopAnnotationLabel(trades,includeModel=true)
        assertEquals("M1 A/B · M2 A · M3 A SL 79,640",stops)
        assertTrue(visibleTradeAnnotations(value,BTC_CHART_ID,now,true,false).isEmpty())
        assertTrue(visibleTradeAnnotations(value,BTC_CHART_ID,now,false,true).isEmpty())
    }

    @Test fun stopAxisShowsOneAveragePerModelWithoutChangingWalletStops() {
        val a = trade().copy(stopPrice=79000.0)
        val b = trade("B").copy(stopPrice=79020.0)
        val m2 = trade().copy(id="c".repeat(24),modelId="M2",stopPrice=78000.0)
        val m3 = trade().copy(id="d".repeat(24),modelId="M3",stopPrice=80000.0)
        assertEquals(listOf(79010.0),stopAxisPrices(listOf(a,b)))
        assertEquals(listOf(79010.0,78000.0,80000.0),stopAxisPrices(listOf(b,m3,a,m2)))
        assertEquals("79,010",tradePrice(stopAxisPrices(listOf(a,b)).single()))
        assertEquals(listOf(79000.0,79020.0),listOf(a,b).map { it.stopPrice })
        assertEquals(listOf(79000.0),stopAxisPrices(listOf(a)))
        assertEquals(listOf(79000.0),stopAxisPrices(listOf(a.copy(wallet=""))))
        assertEquals(listOf(79000.0),stopAxisPrices(listOf(a,b.copy(stopPrice=79000.0))))
        assertEquals(listOf(79000.0),stopAxisPrices(listOf(a,m2.copy(stopPrice=79000.0))))
        assertTrue(stopAxisPrices(emptyList()).isEmpty())
        assertTrue(stopAxisPrices(listOf(a,b.copy(stopPrice=null,stopState="unavailable"))).isEmpty())
        assertEquals(listOf(79010.0),stopAxisPrices(listOf(a,b.copy(stopState="pending"))))
        assertEquals("79,010.25",tradePrice(stopAxisPrices(listOf(a,b.copy(stopPrice=79020.5))).single()))
    }

    @Test fun oldOfflineAndFailedRefreshMetadataCannotShowActiveStops() {
        val value=snapshot()
        assertEquals(listOf(trade()),visibleTradeAnnotations(value,"M1",now,false,false))
        assertTrue(visibleTradeAnnotations(value,"M2",now,false,false).isEmpty())
        assertTrue(visibleTradeAnnotations(value,"M1",now,true,false).isEmpty())
        assertTrue(visibleTradeAnnotations(value,"M1",now,false,true).isEmpty())
        assertTrue(visibleTradeAnnotations(value,"M1",now+90_001,false,false).isEmpty())
        assertTrue(visibleTradeAnnotations(value,"M1",now-1,false,false).isEmpty())
        assertTrue(value.withoutTradeAnnotations().tradeAnnotations.isEmpty())
    }

    @Test fun nearbyPriceBadgesAndEntryTagsStayReadableWithoutChangingPriceOrTime() {
        val centers=priceLabelCenters(listOf(100f,101f,99f),20f,0f,300f)
        assertTrue(centers.max()-centers.min()>=44f)
        val a=entryLabelRect(100f,100f,100f,20f,300f,0f,300f,emptyList())
        val b=entryLabelRect(102f,100f,100f,20f,300f,0f,300f,listOf(a))
        assertFalse(a.overlaps(b)); assertTrue(b.top>=0 && b.bottom<=300)
        val range=priceRange(listOf(PriceCandle(first,80000.0,80500.0,79900.0,80400.0)),listOf(79640.0))
        assertTrue(range.low<79640.0 && range.high>80500.0)
    }
}

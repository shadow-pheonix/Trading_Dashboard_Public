package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LiveChartTest {
    private val now=1800000000000L
    private val current=now/MACD_INTERVAL*MACD_INTERVAL
    private val connection=Connection("main","Main","https://dashboard.example.test","x".repeat(43))
    private fun value(): MacdSnapshot {
        val candles=(255 downTo 1).map { PriceCandle(current-it*MACD_INTERVAL,100.0,110.0,90.0,100.0) }
        val history=(1..3).map { n -> MacdModel("M$n",(n+1)*150,(n+1)*450,(n+1)*150,
            candles.map { MacdPoint(it.timestamp,0.0,0.0,0.0) }) }
        return MacdSnapshot(2,"Main",now,"bot_spot_closed","BTCUSDT",15,"ok",now,
            history.map { it.copy(points=it.points.takeLast(96)) },candles,"ok",now,
            chartWindow=256,formingCandle=PriceCandle(current,100.0,112.0,90.0,111.0),chartModels=history,
            formingModels=history.map { it.copy(points=listOf(MacdPoint(current,1.0,0.5,0.5))) },chartMacdStatus="ok")
    }

    @Test fun fullHistoryIncludesOneExplicitPartialAndBothPanelsShareItsTime() {
        val v=value();validateMacd(v)
        assertEquals(v,dashboardJson.decodeFromString<MacdSnapshot>(dashboardJson.encodeToString(MacdSnapshot.serializer(),v)))
        assertEquals(256,chartCandles(v,now).size)
        assertEquals(current,chartCandles(v,now).last().timestamp)
        assertEquals(256,chartModel(v,"M1",now)!!.points.size)
        assertEquals(current,chartModel(v,"M1",now)!!.points.last().timestamp)
        assertEquals(96,v.models.first().points.size) // Existing strategy-card values stay captured.
        assertEquals("view=chart&history=256&live=1",liveChartRequest(connection).url.encodedQuery)
        assertEquals("Bearer ${connection.accessKey}",liveChartRequest(connection).header("Authorization"))
    }

    @Test fun futureMalformedAndMisalignedPartialOrMacdIsRejected() {
        val v=value()
        val bad=listOf(v.copy(chartWindow=257),v.copy(formingCandle=v.formingCandle!!.copy(timestamp=current+MACD_INTERVAL)),
            v.copy(formingCandle=v.formingCandle!!.copy(high=100.0)),v.copy(formingCandle=null),
            v.copy(candleSampledAt=now-15_001),v.copy(chartModels=v.chartModels.drop(1)),
            v.copy(formingModels=v.formingModels.reversed()),v.copy(chartMacdStatus="failed"),
            v.copy(chartModels=v.chartModels.map { it.copy(points=it.points.drop(1)) }),
            v.copy(formingModels=v.formingModels.map { it.copy(points=listOf(it.points.single().copy(histogram=2.0))) }))
        bad.forEach { assertThrows(IllegalArgumentException::class.java) { validateMacd(it) } }
        validateMacd(v.withoutLivePreview())
    }

    @Test fun staleOfflineFailedOrRolledOverSamplesNeverMasqueradeAsFinalCandles() {
        val v=value()
        assertFalse(formingIsFresh(v,now+15_001));assertFalse(formingIsFresh(v,now-1))
        assertEquals(255,chartCandles(v,now,cached=true).size)
        assertEquals(255,chartCandles(v,now,failed=true).size)
        assertEquals(255,chartCandles(v,current+MACD_INTERVAL).size)
        assertEquals(255,chartModel(v,"M1",now,cached=true)!!.points.size)
        val unavailable=v.copy(chartModels=emptyList(),formingModels=emptyList(),chartMacdStatus="failed")
        validateMacd(unavailable)
        assertEquals(96,chartModel(unavailable,"M1",now)!!.points.size)
    }

    @Test fun historyPansAcrossAll256ButBothZoomMethodsStayWithin12To96() {
        assertEquals(ChartViewport(196.0,60.0),ChartViewport.latest(256))
        assertEquals(ChartViewport(160.0,96.0),ChartViewport.latest(256,1000.0))
        val max=ChartViewport.latest(256).zoomControl(256,1e-10)
        assertEquals(96.0,max.count,0.0)
        assertEquals(12.0,max.zoomControl(256,1e10).count,0.0)
        assertEquals(0.0,max.transform(256,1.0,100.0,0.5).first,0.0)
        assertEquals(160.0,max.transform(256,1.0,-100.0,0.5).first,0.0)
        assertEquals(255,ChartViewport.latest(256).indexAt(1.0,256))
        assertEquals(195.0,ChartViewport.latest(256).rebased(current,current+MACD_INTERVAL,256,255).first,0.0)
    }

    @Test fun diskCacheStoresClosedHistoryOnlyAndDoesNotRetainPartialMacd()=runBlocking {
        val dir=Files.createTempDirectory("live-chart-cache").toFile()
        try {
            val cache=MacdCacheStore(dir) { now }
            cache.save(connection,value())
            val saved=cache.load(connection)!!;validateMacd(saved)
            assertEquals(255,saved.candles.size);assertEquals(255,saved.chartModels.first().points.size)
            assertNull(saved.formingCandle);assertTrue(saved.formingModels.isEmpty());assertEquals("stale",saved.candleStatus)
        } finally { dir.deleteRecursively() }
    }

    @Test fun partialPriceRefreshRetainsCompleteMacdHistoryWithAnHonestGapAtTheNewCandle()=runBlocking {
        val saved=value().withoutLivePreview()
        val incoming=saved.copy(generatedAt=now+MACD_INTERVAL,status="stale",
            candles=saved.candles.drop(1)+saved.candles.last().copy(timestamp=current),
            chartModels=emptyList(),formingModels=emptyList(),chartMacdStatus="failed",candleStatus="stale")
        var result:MacdSnapshot?=null
        val cache=object:MacdCache {
            override suspend fun load(connection:Connection)=saved
            override suspend fun save(connection:Connection,value:MacdSnapshot){}
            override suspend fun forget(id:String){}
        }
        val session=MacdSession(this,{incoming},{result=it},{fail(it)},pause={awaitCancellation()},cache=cache)
        try {
            session.select(connection,true);repeat(20){yield()}
            val merged=result!!;validateMacd(merged)
            assertEquals(saved.chartModels,merged.chartModels);assertEquals("stale",merged.chartMacdStatus)
            val aligned=alignedMacd(merged.candles.map{it.timestamp},chartModel(merged,"M1",now+MACD_INTERVAL))
            assertNull(aligned.last());assertNotNull(aligned.first());assertTrue(merged.formingModels.isEmpty())
        } finally {session.close()}
    }

    @Test fun foregroundUsesFiveSecondRefreshWithoutRewritingCacheForPartialTicks()=runBlocking {
        var requests=0;var writes=0;val pauses=mutableListOf<Long>()
        val online=value()
        val cache=object:MacdCache {
            override suspend fun load(connection:Connection):MacdSnapshot?=null
            override suspend fun save(connection:Connection,value:MacdSnapshot){writes++}
            override suspend fun forget(id:String){}
        }
        val session=MacdSession(this, { requests++;online.copy(formingCandle=online.formingCandle!!.copy(close=110.0+requests)) },
            {}, { fail(it) },pause={pauses.add(it);if(requests>=2) awaitCancellation() else yield()},cache=cache)
        try {
            session.select(connection,true);repeat(20){yield()}
            assertEquals(2,requests);assertEquals(listOf(5000L,5000L),pauses);assertEquals(1,writes)
            session.select(connection,false);repeat(5){yield()};assertEquals(2,requests)
        } finally { session.close() }
    }
}

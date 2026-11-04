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
        val times=chartCandles(v,now).map { it.timestamp }
        for (id in listOf("M1","M2","M3")) {
            val aligned=alignedMacd(times,chartModel(v,id,now))
            assertEquals(256,aligned.size)
            assertTrue(aligned.all { it!=null })
            assertEquals(times,aligned.map { it!!.timestamp })
            // Panning to the oldest window must have MACD too, not just the saved 96-point tail.
            assertTrue(aligned.take(96).all { it!=null })
        }
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

    @Test fun historyPansAcrossAll256AndBothZoomMethodsStayWithin12To256() {
        assertEquals(ChartViewport(196.0,60.0),ChartViewport.latest(256))
        assertEquals(ChartViewport(0.0,256.0),ChartViewport.latest(256,1000.0))
        val max=ChartViewport.latest(256).zoomControl(256,1e-10)
        assertEquals(256.0,max.count,0.0)
        assertEquals(12.0,max.zoomControl(256,1e10).count,0.0)
        assertEquals(0.0,max.transform(256,1.0,100.0,0.5).first,0.0)
        assertEquals(25600.0,max.transform(256,1.0,-100.0,0.5).first,0.0)
        assertEquals(255,ChartViewport.latest(256).indexAt(1.0,256))
        assertEquals(195.0,ChartWindow().viewport((1..255).map {current+it*MACD_INTERVAL}).first,0.0)
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

    @Test fun refreshBetweenClockTicksKeepsLiveCandleStopsAndViewportStable() {
        val trade=ChartTradeAnnotation("a".repeat(24),"M1","A","SHORT",current-MACD_INTERVAL,
            100.0,180.0,"confirmed")
        val initial=value().copy(tradeAnnotations=listOf(trade),annotationStatus="ok",annotationSampledAt=now)
        var state=DashboardState(macd=initial,now=now)
        val window=ChartWindow()
        val range=priceRange(initial.candles.takeLast(59)+initial.formingCandle!!,listOf(trade.stopPrice!!))
        for (step in 1..3) {
            // The one-second UI ticker has not reached the newly sampled response yet.
            val sampledAt=now+step*5000+250
            state=state.copy(now=sampledAt-250)
            val response=initial.copy(generatedAt=sampledAt,candleSampledAt=sampledAt,annotationSampledAt=sampledAt)
            validateMacd(response)
            state=state.withMacdSnapshot(response,receivedAt=sampledAt+50)
            val candles=chartCandles(state.macd,state.now,state.macdCached,state.macdError!=null)
            val stops=visibleTradeAnnotations(state.macd,"M1",state.now,state.macdCached,state.macdError!=null)
            assertEquals("Fresh response must not lose the forming candle before the next tick",256,candles.size)
            assertEquals(listOf(trade),stops)
            assertEquals(256,chartModel(state.macd,"M1",state.now)!!.points.size)
            assertEquals(range,priceRange(candles.takeLast(60),stops.mapNotNull { it.stopPrice }))
            assertEquals(ChartViewport.latest(256),window.viewport(candles.map {it.timestamp}))
            assertEquals(sampledAt+50,state.now)
        }
    }

    @Test fun receiptClockClearsRecoveryFlagsWithoutRelaxingFreshnessChecks() {
        val v=value()
        val before=DashboardState(now=now-500,macdCached=true,macdError="Refresh failed",page=Page.Strategies)
        val received=before.withMacdSnapshot(v,receivedAt=now+50)
        assertFalse(received.macdCached);assertNull(received.macdError)
        assertEquals(Page.Strategies,received.page)
        assertTrue(formingIsFresh(received.macd,received.now,received.macdCached,received.macdError!=null))
        assertTrue(formingIsFresh(v,now+15_000))
        val expired=received.copy(now=now+15_001)
        assertFalse(formingIsFresh(expired.macd,expired.now))
        val future=before.withMacdSnapshot(v,receivedAt=now-1)
        assertFalse(formingIsFresh(future.macd,future.now))
        assertFalse(formingIsFresh(v,received.now,cached=true))
        assertFalse(formingIsFresh(v,received.now,failed=true))
    }

    @Test fun responseAcrossCandleRolloverPublishesTheNewTimelineBeforeTheNextUiTick() {
        val boundary=current+MACD_INTERVAL
        val old=value()
        fun shift(m:MacdModel)=m.copy(points=m.points.map { it.copy(timestamp=it.timestamp+MACD_INTERVAL) })
        val trade=ChartTradeAnnotation("b".repeat(24),"M1","B","LONG",boundary+25,101.0,90.0,"confirmed")
        val response=old.copy(generatedAt=boundary+100,sampledAt=boundary+100,
            candles=old.candles.map { it.copy(timestamp=it.timestamp+MACD_INTERVAL) },candleSampledAt=boundary+100,
            models=old.models.map(::shift),chartModels=old.chartModels.map(::shift),formingModels=old.formingModels.map(::shift),
            formingCandle=old.formingCandle!!.copy(timestamp=boundary),tradeAnnotations=listOf(trade),
            annotationStatus="ok",annotationSampledAt=boundary+100)
        validateMacd(response)
        val received=DashboardState(macd=old,now=boundary-50).withMacdSnapshot(response,receivedAt=boundary+200)
        val times=chartCandles(received.macd,received.now).map { it.timestamp }
        assertEquals(256,times.size);assertEquals(boundary,times.last())
        assertEquals(times,chartModel(received.macd,"M1",received.now)!!.points.map { it.timestamp })
        val trades=visibleTradeAnnotations(received.macd,"M1",received.now,false,false)
        assertEquals(listOf(trade),trades)
        assertEquals(255,entryChartMarkers(trades,times).single().index)
        val first=old.candles.first().timestamp
        assertEquals(ChartViewport.latest(256),ChartWindow().viewport(times))
        val oldTimes=chartTimes(old,chartCandles(old,now),chartModel(old,"M1",now))
        val panned=ChartWindow().moved(ChartViewport(20.0,60.0),oldTimes).viewport(times)
        assertEquals(ChartViewport(19.0,60.0),panned)
        assertEquals(first+20*MACD_INTERVAL,times.first()+panned.first.toLong()*MACD_INTERVAL)
    }

    @Test fun manualRefreshDoesNotReplaceAnOnlineChartWithItsClosedOnlyDiskCopy()=runBlocking {
        val trade=ChartTradeAnnotation("a".repeat(24),"M1","A","SHORT",current-MACD_INTERVAL,100.0,180.0,"confirmed")
        val online=value().copy(tradeAnnotations=listOf(trade),annotationStatus="ok",annotationSampledAt=now)
        val saved=online.withoutLivePreview().withoutTradeAnnotations()
        var state=DashboardState(now=now);var requests=0;var loads=0
        val gate=CompletableDeferred<Unit>()
        val cache=object:MacdCache {
            override suspend fun load(connection:Connection):MacdSnapshot { loads++;return saved }
            override suspend fun save(connection:Connection,value:MacdSnapshot){}
            override suspend fun forget(id:String){}
        }
        val session=MacdSession(this,{requests++;if(requests>1)gate.await();online},
            {state=state.withMacdSnapshot(it,now)},{fail(it)},pause={awaitCancellation()},cache=cache,
            onCached={state=state.copy(macd=it,macdCached=true)})
        try {
            session.select(connection,true);repeat(20){yield()}
            assertFalse(state.macdCached);assertEquals(256,chartCandles(state.macd,now).size)
            session.select(connection,true,refresh=true);repeat(20){yield()}
            assertEquals(2,requests)
            assertFalse("Refresh must retain the accepted live snapshot while fetching",state.macdCached)
            assertEquals(1,loads)
            assertEquals(online,state.macd)
            assertEquals(256,chartCandles(state.macd,now,state.macdCached).size)
            assertEquals(listOf(trade),visibleTradeAnnotations(state.macd,"M1",now,state.macdCached,false))
            gate.complete(Unit);repeat(20){yield()}
            session.close();session.select(connection,true);repeat(20){yield()}
            assertEquals(2,loads) // A new foreground session still loads the safe disk copy.
        } finally {session.close()}
    }

    @Test fun expiringALiveSampleLeavesOneEmptySlotInsteadOfShiftingTheTimeAxis() {
        val v=value()
        val live=chartTimes(v,chartCandles(v,now),chartModel(v,"M1",now))
        for (hidden in listOf(v.withoutLivePreview(),v)) {
            val expired=now+15_001
            val candles=chartCandles(hidden,expired)
            val model=chartModel(hidden,"M1",expired)
            val times=chartTimes(hidden,candles,model)
            assertEquals(live,times);assertEquals(256,times.size)
            assertEquals(255,candles.size)
            assertNull(alignedMacd(times,model).last())
            assertEquals(ChartWindow().viewport(live),ChartWindow().viewport(times))
        }
    }

    @Test fun cancelledRefreshCannotOverwriteTheNextResponseAndCredentialsNeverReuseOnlineData()=runBlocking {
        val old=value();val newer=old.copy(generatedAt=now+100)
        val gate=CompletableDeferred<Unit>();val values=mutableListOf<MacdSnapshot>()
        var requests=0;var loads=0
        val cache=object:MacdCache {
            override suspend fun load(connection:Connection):MacdSnapshot? {loads++;return null}
            override suspend fun save(connection:Connection,value:MacdSnapshot){}
            override suspend fun forget(id:String){}
        }
        val session=MacdSession(this,{
            requests++
            if(requests==2) withContext(NonCancellable){gate.await();old}
            else if(requests>2)newer else old
        },{values.add(it)},{fail(it)},pause={awaitCancellation()},cache=cache)
        try {
            session.select(connection,true);repeat(20){yield()}
            session.select(connection,true,refresh=true);repeat(20){yield()}
            session.select(connection,true,refresh=true);repeat(20){yield()}
            gate.complete(Unit);repeat(20){yield()}
            assertEquals(listOf(old,newer),values);assertEquals(1,loads)
            session.select(connection.copy(accessKey="y".repeat(43)),true);repeat(20){yield()}
            assertEquals(2,loads)
            session.select(connection.copy(id="other"),true);repeat(20){yield()}
            assertEquals(3,loads)
        } finally {gate.complete(Unit);session.close()}
    }
}

package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MacdCacheTest {
    private val connection = Connection("main", "Main", DASHBOARD_URL, "x".repeat(43))
    private val time = 1800000000000L
    private fun snapshot(): MacdSnapshot {
        val target = time / MACD_INTERVAL * MACD_INTERVAL - MACD_INTERVAL
        return MacdSnapshot(2, "Main", time, "binance_spot_closed", "BTCUSDT", 15, "ok", time,
            (1..3).map { n -> MacdModel("M$n", (n + 1) * 150, (n + 1) * 450, (n + 1) * 150,
                (0..95).map { i -> MacdPoint(target - (95 - i) * MACD_INTERVAL, i.toDouble(), 20.0, i - 20.0) }) })
    }
    @Test fun cacheSurvivesRestartAndSeparatesCredentialsWithoutSavingKeys() = runBlocking {
        val dir = Files.createTempDirectory("macd-test").toFile()
        try {
            MacdCacheStore(dir) { time }.save(connection, snapshot())
            val restarted = MacdCacheStore(dir) { time }
            assertEquals(snapshot(), restarted.load(connection.copy(label = "Renamed")))
            assertNull(restarted.load(connection.copy(accessKey = "y".repeat(43))))
            assertNull(restarted.load(connection.copy(id = "vinay")))
            assertFalse(dir.listFiles()!!.single().readText().contains(connection.accessKey))
            restarted.forget(connection.id); assertNull(restarted.load(connection))
        } finally { dir.deleteRecursively() }
    }
    @Test fun corruptOversizedAndFutureCacheCannotBecomeFreshChart() = runBlocking {
        val dir = Files.createTempDirectory("macd-test").toFile()
        try {
            val cache = MacdCacheStore(dir) { time }
            cache.save(connection, snapshot())
            val file = dir.listFiles()!!.single()
            file.writeText("invalid json"); assertNull(cache.load(connection))
            file.writeText("x".repeat(262145)); assertNull(cache.load(connection))
            cache.save(connection, snapshot())
            assertNull(MacdCacheStore(dir) { time - 120_000 }.load(connection))
        } finally { dir.deleteRecursively() }
    }
    @Test fun loadingNeverReplacesValidDiskChartAndOldCandlesAreMarkedStale() = runBlocking {
        val dir = Files.createTempDirectory("macd-test").toFile()
        try {
            val cache = MacdCacheStore(dir) { time }
            cache.save(connection, snapshot())
            cache.save(connection, snapshot().copy(status = "loading", sampledAt = null, models = emptyList()))
            val saved = cache.load(connection)!!
            assertEquals(snapshot(), saved)
            assertTrue(macdIsStale(saved, time + MACD_INTERVAL))
        } finally { dir.deleteRecursively() }
    }
    @Test fun savedChartAppearsBeforeNetworkAndSurvivesLoadingAndFailure() = runBlocking {
        val dir = Files.createTempDirectory("macd-test").toFile()
        val values = mutableListOf<MacdSnapshot>()
        val errors = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var request = 0
        val cache = MacdCacheStore(dir) { time }
        cache.save(connection, snapshot())
        val session = MacdSession(this, {
            gate.await(); request++
            if (request == 1) snapshot().copy(status = "loading", sampledAt = null, models = emptyList()) else throw IllegalStateException()
        }, { values.add(it) }, { errors.add(it) }, pause = { if (request >= 2) awaitCancellation() else yield() }, cache = cache)
        try {
            session.select(connection, true)
            withTimeout(3000) { while (values.isEmpty()) delay(5) }
            assertEquals(0, request); assertEquals(snapshot(), values.single())
            gate.complete(Unit)
            withTimeout(3000) { while (errors.isEmpty()) yield() }
            assertEquals(1, values.size)
            session.close(); yield(); assertEquals(2, request)
        } finally { session.close(); dir.deleteRecursively() }
    }
    @Test fun serverRetryClearsPreviousErrorWithoutErasingPhoneChart() = runBlocking {
        val values = mutableListOf<MacdSnapshot>()
        var error: String? = null
        var requests = 0
        var refreshing = 0
        val saved = snapshot()
        val cache = object : MacdCache {
            override suspend fun load(connection: Connection) = saved
            override suspend fun save(connection: Connection, value: MacdSnapshot) { }
            override suspend fun forget(id: String) { }
        }
        val session = MacdSession(this, {
            requests++
            saved.copy(status = if (requests == 1) "failed" else "loading", sampledAt = null, models = emptyList())
        }, { values.add(it) }, { error = it },
            pause = { if (requests >= 2) awaitCancellation() else yield() }, cache = cache,
            onRefreshing = { error = null; refreshing++ })
        try {
            session.select(connection, true); repeat(20) { yield() }
            assertEquals(2, requests); assertEquals(1, refreshing)
            assertNull(error); assertEquals(listOf(saved), values)
        } finally { session.close() }
    }

    @Test fun inaccessibleCacheDoesNotPreventOnlineChartsOrIdleCancellation() = runBlocking {
        val values = mutableListOf<MacdSnapshot>()
        val broken = object : MacdCache {
            override suspend fun load(connection: Connection): MacdSnapshot? = throw java.io.IOException()
            override suspend fun save(connection: Connection, value: MacdSnapshot) { throw java.io.IOException() }
            override suspend fun forget(id: String) { }
        }
        var requests = 0
        val session = MacdSession(this, { requests++; snapshot() }, { values.add(it) }, { fail(it) },
            pause = { awaitCancellation() }, cache = broken)
        try {
            session.select(connection, false); repeat(5) { yield() }; assertEquals(0, requests)
            session.select(connection, true); repeat(15) { yield() }
            assertEquals(listOf(snapshot()), values)
            session.close(); repeat(5) { yield() }; assertEquals(1, requests)
        } finally { session.close() }
    }

    @Test fun switchingToIdenticalBotValuesUpdatesSavedSource() = runBlocking {
        val saved = snapshot()
        val bot = saved.copy(source = "bot_spot_closed")
        val writes = mutableListOf<MacdSnapshot>()
        val cache = object : MacdCache {
            override suspend fun load(connection: Connection) = saved
            override suspend fun save(connection: Connection, value: MacdSnapshot) { writes.add(value) }
            override suspend fun forget(id: String) { }
        }
        val session = MacdSession(this, { bot }, {}, { fail(it) },
            pause = { awaitCancellation() }, cache = cache)
        try {
            session.select(connection, true); repeat(20) { yield() }
            assertEquals(listOf(bot), writes)
        } finally { session.close() }
    }

    @Test fun repeatedIdenticalCandlesDoNotRewritePhoneStorage() = runBlocking {
        var writes = 0
        var requests = 0
        val unchanged = snapshot()
        val changed = unchanged.copy(models = unchanged.models.map { model -> model.copy(points = model.points.map {
            it.copy(macd = it.macd + 1, histogram = it.histogram + 1)
        }) })
        val cache = object : MacdCache {
            override suspend fun load(connection: Connection) = unchanged
            override suspend fun save(connection: Connection, value: MacdSnapshot) { writes++ }
            override suspend fun forget(id: String) { }
        }
        val session = MacdSession(this, { requests++; if (requests < 3) unchanged else changed }, {}, { fail(it) },
            pause = { if (requests >= 3) awaitCancellation() else yield() }, cache = cache)
        try {
            session.select(connection, true); repeat(20) { yield() }
            assertEquals(3, requests); assertEquals(1, writes)
        } finally { session.close() }
    }

}

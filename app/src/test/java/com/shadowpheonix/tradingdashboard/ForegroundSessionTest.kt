package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test

class ForegroundSessionTest {
    private val connection = Connection("main", "Main", DASHBOARD_URL, "x".repeat(43))
    private fun snapshot(query: DashboardQuery = liveQuery, instance: String = "Main") = DashboardSnapshot(
        instance = instance, generatedAt = 100_000, live = false, period = query.period,
        selectedModel = query.model, historyMode = query.mode, accounts = emptyList(), metrics = Metrics())

    private inner class Harness(scope: CoroutineScope) {
        val feed = MutableSharedFlow<DashboardSnapshot>(replay = 1).apply { tryEmit(snapshot()) }
        var opens = 0
        var closes = 0
        val queries = mutableListOf<DashboardQuery>()
        val filtered = mutableListOf<DashboardSnapshot>()
        val live = mutableListOf<DashboardSnapshot>()
        val statuses = mutableListOf<Pair<Boolean, String?>>()
        val loading = mutableListOf<Pair<Boolean, String?>>()
        var fetchAction: suspend (Connection, DashboardQuery) -> DashboardSnapshot = { c, q -> snapshot(q, c.label) }
        val session = ForegroundSession(scope,
            stream = { flow { opens++; try { emitAll(feed) } finally { closes++ } } },
            fetch = { c, q -> queries.add(q); fetchAction(c, q) },
            onLive = { live.add(it) }, onFiltered = { filtered.add(it) },
            onStatus = { healthy, error -> statuses.add(healthy to error) },
            onQueryLoading = { active, error -> loading.add(active to error) })
    }

    private suspend fun drain() { repeat(12) { yield() } }

    @Test fun navigationAndFiltersRetainOneForegroundConnection() = runBlocking {
        val h = Harness(this)
        try {
            h.session.open(connection); drain()
            for (page in Page.entries) {
                h.session.select(queryFor(page, "1M", "M2", "edge"), followLive = page != Page.Analytics)
                h.session.open(connection.copy(label = "Server name"))
                drain()
            }
            h.session.select(DashboardQuery("1D", "M1", "real")); drain()
            assertEquals(1, h.opens); assertEquals(0, h.closes)
            assertTrue(h.queries.contains(DashboardQuery("1M", "M2", "edge")))
            assertTrue(h.queries.contains(DashboardQuery("1D", "M1", "real")))
            assertEquals(listOf(false, true), h.statuses.map { it.first })
        } finally { h.session.close(); drain() }
        assertEquals(1, h.closes)
    }

    @Test fun backgroundDisconnectsAndResumeOpensOnlyOneNewConnection() = runBlocking {
        val h = Harness(this)
        try {
            h.session.open(connection); drain()
            h.session.close(); drain()
            assertEquals(1, h.closes)
            h.session.select(DashboardQuery("1D", "M1", "edge")); h.session.refresh(); drain()
            assertTrue(h.queries.isEmpty())
            h.session.open(connection); drain()
            assertEquals(2, h.opens); assertEquals(1, h.closes)
            assertEquals(1, h.queries.size)
        } finally { h.session.close(); drain() }
        assertEquals(2, h.closes)
    }

    @Test fun manualRefreshAndFilteredRequestFailureDoNotDisconnectTheStream() = runBlocking {
        val h = Harness(this)
        try {
            h.session.open(connection); drain()
            h.session.refresh(); drain()
            assertEquals(listOf(liveQuery), h.queries)
            h.fetchAction = { _, _ -> throw DashboardError("Selected data unavailable") }
            h.session.select(DashboardQuery("1Y", "M3", "edge")); drain()
            assertEquals(1, h.opens); assertEquals(0, h.closes)
            assertTrue(h.statuses.last().first)
            assertEquals(false to "Selected data unavailable", h.loading.last())
        } finally { h.session.close(); drain() }
    }

    @Test fun instanceSwitchRejectsLateResponsesFromThePreviousKey() = runBlocking {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        try {
            h.fetchAction = { c, q ->
                if (c.id == "main") withContext(NonCancellable) { release.await() }
                snapshot(q, c.label)
            }
            h.session.select(DashboardQuery("All", "M1", "edge"))
            h.session.open(connection); drain()
            h.session.open(connection.copy(id = "foreign", label = "Foreign", accessKey = "y".repeat(43))); drain()
            release.complete(Unit); drain()
            assertEquals(listOf("Foreign"), h.filtered.map { it.instance })
            assertEquals(2, h.opens); assertEquals(1, h.closes)
        } finally { release.complete(Unit); h.session.close(); drain() }
    }

    @Test fun repeatedStreamUpdatesShareAnInFlightFilteredRead() = runBlocking {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        try {
            h.fetchAction = { c, q -> release.await(); snapshot(q, c.label) }
            h.session.select(DashboardQuery("1W", "all", "real"))
            h.session.open(connection); drain()
            h.feed.emit(snapshot()); h.feed.emit(snapshot()); drain()
            assertEquals(1, h.queries.size)
            release.complete(Unit); drain()
            assertEquals(1, h.filtered.size)
            assertEquals(1, h.opens)
        } finally { release.complete(Unit); h.session.close(); drain() }
    }

    @Test fun actualNetworkFailureRetriesAndBackgroundCancelsPendingRetry() = runBlocking {
        var opens = 0
        val statuses = mutableListOf<Boolean>()
        val session = ForegroundSession(this,
            stream = { flow {
                opens++
                if (opens == 1) throw DashboardError("Network lost")
                emit(snapshot()); awaitCancellation()
            } }, fetch = { _, q -> snapshot(q) }, onLive = {}, onFiltered = {},
            onStatus = { healthy, _ -> statuses.add(healthy) }, onQueryLoading = { _, _ -> }, initialRetryMillis = 1)
        try {
            session.open(connection)
            withTimeout(1000) { while (!statuses.contains(true)) delay(1) }
            assertEquals(2, opens)
            session.close(); delay(10)
            assertEquals(2, opens)
        } finally { session.close() }
    }

    @Test fun analyticsLoadsOnEntryAndExplicitActionsWithoutFollowingStreamTicks() = runBlocking {
        val h = Harness(this)
        val query = DashboardQuery("1M", "all", "real")
        try {
            h.session.open(connection); drain()
            h.session.select(query, followLive = false); drain()
            repeat(8) { h.feed.emit(snapshot()); drain() }
            assertEquals(listOf(query), h.queries)
            assertEquals(1, h.filtered.size)
            assertEquals(9, h.live.size)
            h.session.select(query, followLive = false); drain()
            assertEquals(1, h.queries.size)
            h.session.refresh(); drain()
            h.session.select(query.copy(period = "YTD"), followLive = false); drain()
            assertEquals(3, h.queries.size)
            h.session.select(liveQuery); drain()
            h.session.select(query, followLive = false); drain()
            assertEquals(4, h.queries.size) // Re-entering Analytics fetches a new snapshot.
            assertEquals(1, h.opens); assertEquals(0, h.closes)
        } finally { h.session.close(); drain() }
    }

    @Test fun analyticsAllModelsAllTimeGetsItsOwnSnapshotAndResumeFetchesOnce() = runBlocking {
        val h = Harness(this)
        try {
            h.session.select(liveQuery, followLive = false)
            h.session.open(connection); h.session.refresh(); drain()
            assertEquals(listOf(liveQuery), h.queries)
            assertEquals(1, h.filtered.size)
            repeat(3) { h.feed.emit(snapshot()); drain() }
            assertEquals(1, h.queries.size)
            h.session.close(); drain()
            h.session.open(connection); h.session.refresh(); drain()
            assertEquals(2, h.queries.size)
            assertEquals(2, h.filtered.size)
            assertEquals(2, h.opens); assertEquals(1, h.closes)
        } finally { h.session.close(); drain() }
    }

    @Test fun analyticsFailureDoesNotPollAndTradesKeepUpdating() = runBlocking {
        val h = Harness(this)
        val query = DashboardQuery("1Y", "M1", "real")
        try {
            h.session.open(connection); drain()
            h.fetchAction = { _, _ -> throw DashboardError("Snapshot unavailable") }
            h.session.select(query, followLive = false); drain()
            repeat(3) { h.feed.emit(snapshot()); drain() }
            assertEquals(1, h.queries.size)
            assertEquals(false to "Snapshot unavailable", h.loading.last())
            assertTrue(h.statuses.last().first)
            h.fetchAction = { c, q -> snapshot(q, c.label) }
            h.session.refresh(); drain()
            assertEquals(2, h.queries.size)
            h.session.select(query, followLive = true); drain()
            assertEquals(3, h.queries.size)
            h.feed.emit(snapshot()); drain()
            assertEquals(4, h.queries.size)
            assertEquals(1, h.opens); assertEquals(0, h.closes)
        } finally { h.session.close(); drain() }
    }

    @Test fun switchingToSnapshotModeRejectsLateAutomaticResponses() = runBlocking {
        val h = Harness(this)
        val release = CompletableDeferred<Unit>()
        val query = DashboardQuery("1M", "all", "real")
        var requests = 0
        try {
            h.fetchAction = { c, q ->
                requests++
                val number = requests
                if (number == 1) withContext(NonCancellable) { release.await() }
                snapshot(q, c.label).copy(generatedAt = 100000L + number)
            }
            h.session.open(connection); drain()
            h.session.select(query); drain()
            h.session.select(query, followLive = false); drain()
            assertEquals(100002L, h.filtered.single().generatedAt)
            release.complete(Unit); drain()
            assertEquals(100002L, h.filtered.single().generatedAt)
            h.feed.emit(snapshot()); drain()
            assertEquals(2, h.queries.size)
        } finally { release.complete(Unit); h.session.close(); drain() }
    }
}

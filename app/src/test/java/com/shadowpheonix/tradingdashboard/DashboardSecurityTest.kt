package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DashboardSecurityTest {
    @Test fun sharedTimeframesRequestYtdAndNormalizedEquityIsValidatedInEachScope() {
        assertEquals(listOf("1W", "1M", "3M", "1Y", "YTD", "All"), periods)
        val connection = Connection("test", "Main", DASHBOARD_URL, "x".repeat(43))
        assertEquals("YTD", dashboardRequest(connection, "YTD", "all").url.queryParameter("period"))
        assertThrows(IllegalArgumentException::class.java) { dashboardRequest(connection, "1D", "all") }
        val curve = NormalizedEquity(10000.0, false, listOf(EquityPoint(999999, 10100.0)))
        val value = snapshot().copy(metrics = Metrics(realizedPnl = 0.0), normalizedEquity = curve)
        validateSnapshot(value, "All", "all")
        validateSnapshot(value.copy(selectedModel = "M1"), "All", "M1")
        validateSnapshot(value.copy(historyMode = "edge", dataSource = "edge_csv"), "All", "all", "edge")
        for (invalid in listOf(curve.copy(baseEquity = 0.0), curve.copy(points = emptyList()),
                curve.copy(points = listOf(EquityPoint(1000001, 10100.0))),
                curve.copy(points = listOf(EquityPoint(999999, Double.NaN))),
                curve.copy(points = listOf(EquityPoint(999999, -1.0))),
                curve.copy(points = curve.points + curve.points))) {
            assertThrows(DashboardError::class.java) { validateSnapshot(value.copy(normalizedEquity = invalid), "All", "all") }
        }
        assertThrows(DashboardError::class.java) { validateSnapshot(value.copy(metrics = Metrics()), "All", "all") }
        val reported = curve.copy(drawdown = Drawdown(1000.0, 10.0))
        for (mode in listOf("real", "edge")) {
            val payload = value.copy(normalizedEquity = reported, historyMode = mode,
                dataSource = if (mode == "edge") "edge_csv" else "bot_files_binance")
            val decoded = dashboardJson.decodeFromString<DashboardSnapshot>(dashboardJson.encodeToString(payload))
            validateSnapshot(decoded, "All", "all", mode)
            assertEquals(reported.drawdown, decoded.normalizedEquity?.drawdown)
        }
        for (invalid in listOf(Drawdown(-1.0, 1.0), Drawdown(1.0, -1.0), Drawdown(1.0, 101.0),
                Drawdown(Double.NaN, 1.0), Drawdown(1.0, Double.POSITIVE_INFINITY), Drawdown(0.0, 1.0)))
            assertThrows(DashboardError::class.java) {
                validateSnapshot(value.copy(normalizedEquity = curve.copy(drawdown = invalid)), "All", "all")
            }
    }
    private val connection = Connection("test", "Old custom label", DASHBOARD_URL, "x".repeat(43))
    private fun snapshot(period: String = "All", model: String = "all", mode: String = "real") = DashboardSnapshot(
        instance = "Main", generatedAt = 1_000_000, live = false, period = period, selectedModel = model,
        accounts = listOf(Account("M1", "M1"), Account("M2", "M2"), Account("M3", "M3")), metrics = Metrics(),
        historyMode = mode, dataSource = if (mode == "edge") "edge_csv" else "bot_files_binance")

    @Test fun migrationPreservesSavedAddressesIdsLabelsAndKeysAndIsIdempotent() {
        val original = listOf(connection, connection.copy(id = "vinay", baseUrl = "https://second.example.test", accessKey = "y".repeat(43)))
        val encryptedPlaintext = dashboardJson.encodeToString(original)
        val migrated = migrateConnections(dashboardJson.decodeFromString(encryptedPlaintext))
        assertEquals(original.map { it.id }, migrated.map { it.id })
        assertEquals(original.map { it.label }, migrated.map { it.label })
        assertEquals(original.map { it.accessKey }, migrated.map { it.accessKey })
        assertEquals(original.map { it.baseUrl }, migrated.map { it.baseUrl })
        assertEquals(migrated, migrateConnections(migrated))
        assertEquals(emptyList<Connection>(), migrateConnections(emptyList()))
    }

    @Test fun firstFormHidesAfterConnectionAndAddAnotherOpensIt() {
        val first = DashboardState()
        assertTrue(first.showConnectionForm)
        val connected = first.copy(connections = listOf(connection), activeId = connection.id)
        assertFalse(connected.showConnectionForm)
        val adding = connected.copy(addKeyRequested = true)
        assertTrue(adding.showConnectionForm)
        assertFalse(adding.copy(addKeyRequested = false).showConnectionForm)
        assertFalse(adding.copy(connections = listOf(connection, connection.copy(id = "second")),
            addKeyRequested = false).showConnectionForm)
        assertTrue(connected.copy(connections = emptyList()).showConnectionForm)
    }

    @Test fun homeOrderingAndIndependentRealScopeAreStable() {
        assertEquals(listOf("Portfolio overview", "Active trades", "Account balances"), homeSections)
        for (page in listOf(Page.Dashboard, Page.Strategies, Page.Settings)) {
            assertEquals(DashboardQuery("All", "all", "real"), queryFor(page, "1D", "M2", "edge"))
        }
        for (page in listOf(Page.Analytics, Page.Trades)) {
            assertEquals(DashboardQuery("1D", "M2", "edge"), queryFor(page, "1D", "M2", "edge"))
        }
        assertEquals(listOf("All models", "M1", "M2", "M3"), models.values.toList())
    }

    @Test fun navigatingThroughSettingsCannotLeaveBacktestDataOnHome() {
        val edge = DashboardState(page = Page.Analytics, period = "All", mode = "edge", account = "M2",
            snapshot = snapshot(model = "M2", mode = "edge"), connectionHealthy = true)
        val settings = edge.withPage(Page.Settings)
        assertNull(settings.snapshot)
        assertTrue(settings.connectionHealthy)
        val home = settings.withPage(Page.Dashboard)
        assertEquals(DashboardQuery("All", "all", "real"), home.query)
        assertNull(home.snapshot)
        assertNull(edge.withPage(Page.Dashboard).snapshot)
        assertSame(edge.snapshot, edge.withPage(Page.Trades).snapshot)
        val cached = edge.copy(liveSnapshot = snapshot(), filteredSnapshot = edge.snapshot)
        assertSame(cached.liveSnapshot, cached.withPage(Page.Dashboard).snapshot)
        assertSame(cached.liveSnapshot, cached.withPage(Page.Settings).snapshot)
        assertTrue(cached.withPage(Page.Dashboard).connectionHealthy)
        assertSame(cached.filteredSnapshot, cached.withPage(Page.Dashboard).withPage(Page.Analytics).snapshot)
        assertNull(cached.copy(period = "1D").withPage(Page.Dashboard).withPage(Page.Analytics).snapshot)
    }

    @Test fun analyticsAllTimeStaysFrozenWhileHomeAndTradesReceiveNewLiveSnapshots() {
        val captured = snapshot().copy(generatedAt = 1000000)
        val newer = snapshot().copy(generatedAt = 1003000)
        val analytics = DashboardState(page = Page.Analytics, period = "All", snapshot = captured,
            filteredSnapshot = captured, liveSnapshot = captured, connectionHealthy = true)
        assertFalse(analytics.followLive)
        val updated = analytics.withLiveSnapshot(newer).copy(now = 99999999)
        assertSame(captured, updated.snapshot)
        assertSame(newer, updated.liveSnapshot)
        assertEquals("● Snapshot", updated.connectionLabel)
        assertSame(newer, updated.withPage(Page.Dashboard).snapshot)
        assertSame(newer, updated.withPage(Page.Trades).snapshot)
        assertSame(captured, updated.withPage(Page.Dashboard).withPage(Page.Analytics).snapshot)
        assertTrue(updated.withPage(Page.Trades).followLive)
        val filtered = snapshot(period = "1M", model = "M1")
        val modelView = analytics.copy(period = "1M", account = "M1", snapshot = filtered, filteredSnapshot = filtered)
        assertSame(filtered, modelView.withLiveSnapshot(newer).snapshot)
    }

    @Test fun allRequestsUseTheSavedHttpsHostAndHeaderAuth() {
        listOf(dashboardRequest(connection, "1M", "M1"),
            dashboardStreamRequest(connection, "All", "M3", "edge"), balanceRequest(connection)).forEach { request ->
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertTrue(request.url.isHttps)
            assertEquals("dashboard.example.test", request.url.host)
            assertEquals("Bearer ${connection.accessKey}", request.header("Authorization"))
            assertFalse(request.url.toString().contains(connection.accessKey))
        }
        val dashboard = dashboardRequest(connection, "1M", "M1")
        assertEquals("/v2/dashboard", dashboard.url.encodedPath)
        assertEquals("M1", dashboard.url.queryParameter("model"))
        assertEquals("1M", dashboard.url.queryParameter("period"))
        assertNull(dashboard.url.queryParameter("account"))
        val stream = dashboardStreamRequest(connection, "All", "M3", "edge")
        assertEquals("/v2/live", stream.url.encodedPath)
        assertEquals("edge", stream.url.queryParameter("mode"))
        val balances = balanceRequest(connection)
        assertEquals("/v2/balances", balances.url.encodedPath)
        assertNull(balances.url.query)
    }

    @Test fun invalidKeysPeriodsModelsAndSourcesAreRejected() {
        for (key in listOf("short", "x".repeat(257), "x".repeat(35) + "\r\nheader", "x".repeat(35) + " ")) {
            assertThrows(IllegalArgumentException::class.java) { dashboardRequest(connection.copy(accessKey = key), "1M", "all") }
        }
        assertThrows(IllegalArgumentException::class.java) { dashboardRequest(connection, "bad", "all") }
        assertThrows(IllegalArgumentException::class.java) { dashboardRequest(connection, "All", "wallet-a") }
        assertThrows(IllegalArgumentException::class.java) { dashboardRequest(connection, "All", "all", "backtest") }
        assertFalse(connection.toString().contains(connection.accessKey))
    }

    @Test fun edgeKeepsBothSourcesButRejectsRealMarketData() {
        val trades = listOf("live", "backtest").map { source ->
            Trade(source, "M1", "M1", "BTCUSDT", "LONG", null, 999999, 100.0, 110.0, 9.0, 1.0, source = source)
        }
        val edge = snapshot(mode = "edge").copy(trades = trades)
        validateSnapshot(edge, "All", "all", "edge")
        for (value in listOf(edge.copy(live = true), edge.copy(priceUpdatedAt = 1),
            edge.copy(dataSource = "bot_files_binance"))) {
            assertThrows(DashboardError::class.java) { validateSnapshot(value, "All", "all", "edge") }
        }
        assertThrows(DashboardError::class.java) { validateSnapshot(edge, "All", "all", "real") }
        assertThrows(DashboardError::class.java) { validateSnapshot(snapshot().copy(trades = trades), "All", "all") }
    }

    @Test fun modelScopeAndSchemaMismatchAreRejected() {
        val value = snapshot(model = "M1")
        validateSnapshot(value, "All", "M1")
        val trade = Trade("t", "M2", "M2", "BTCUSDT", "LONG", null, 999999, 100.0, 110.0, 9.0, 1.0)
        for (invalid in listOf(value.copy(schemaVersion = 1), value.copy(selectedModel = "all"),
            value.copy(period = "1D"), value.copy(trades = listOf(trade)),
            value.copy(accounts = value.accounts + value.accounts.first()), value.copy(generatedAt = 0))) {
            assertThrows(DashboardError::class.java) { validateSnapshot(invalid, "All", "M1") }
        }
    }

    @Test fun balanceFailuresCannotMasqueradeAsCompleteSums() {
        val model = ModelBalance("M1", 100.0, "ok", 100)
        val balance = BalanceSnapshot(2, "Main", 110, 90, 100, "ok", 100.0,
            listOf(model, ModelBalance("M2", null, "not_configured", null), ModelBalance("M3", null, "not_configured", null)))
        validateBalances(balance)
        assertThrows(IllegalArgumentException::class.java) { validateBalances(balance.copy(equity = 99.0)) }
        assertThrows(IllegalArgumentException::class.java) { validateBalances(balance.copy(status = "failed")) }
        assertThrows(IllegalArgumentException::class.java) { validateBalances(balance.copy(models = balance.models.drop(1))) }
        val failed = balance.copy(status = "failed", equity = null, sampledAt = null,
            models = balance.models.map { it.copy(equity = null, status = "failed", sampledAt = null) })
        validateBalances(failed)
        assertNull(failed.equity)
        val stale = DashboardState(balances = balance, balancesStale = true, balanceError = "Unavailable")
        assertTrue(stale.balancesStale)
        assertNotNull(stale.balances?.sampledAt)
    }

    @Test fun freshnessRequiresConnectionAndFreshPricesAndKeepsUnknownMetricsNull() {
        val now = 1_000_000L
        val live = snapshot().copy(live = true, priceUpdatedAt = now)
        assertTrue(isFresh(live, now))
        assertFalse(isFresh(live.copy(priceUpdatedAt = now - 15001), now))
        assertFalse(isFresh(live.copy(generatedAt = now + 1), now))
        assertFalse(DashboardState(snapshot = live, now = now, connectionHealthy = false).fresh)
        assertTrue(DashboardState(snapshot = live, now = now, connectionHealthy = true).fresh)
        val metrics = dashboardJson.decodeFromString<Metrics>("{}")
        assertNull(metrics.equity); assertNull(metrics.sharpeRatio); assertNull(metrics.closedTrades)
    }
}

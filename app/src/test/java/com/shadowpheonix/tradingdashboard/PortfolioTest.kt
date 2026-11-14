package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test

class PortfolioTest {
    private fun dashboard(name: String) = DashboardSnapshot(instance = name, generatedAt = 100000,
        live = true, period = "All", accounts = listOf(Account("M1", "M1"), Account("M2", "M2"), Account("M3", "M3")),
        metrics = Metrics(unrealizedPnl = 0.0), dataSource = "bot_files_binance", priceUpdatedAt = 100000)
    private fun entry(id: String, equity: Double) = PortfolioInstance(id, id, "ok", dashboard(id),
        BalanceSnapshot(2, id, 100000, 100000, 100000, "ok", equity,
            listOf(ModelBalance("M1", equity, "ok", 100000), ModelBalance("M2", null, "not_configured", null),
                ModelBalance("M3", null, "not_configured", null)), 1000.0, equity-1000.0, (equity/1000.0-1)*100))
    private fun portfolio() = PortfolioSnapshot(2, 100000, "USD", "ok",
        PortfolioTotals(6000.0, 3000.0, 0.0, 0, 100.0), listOf(entry("main", 1000.0), entry("second", 2000.0), entry("third", 3000.0)))

    @Test fun menuUsesServerCapabilityNotEditableConnectionName() {
        val connection = Connection("a", "Main", DASHBOARD_URL, "synthetic-key-" + "x".repeat(32))
        val state = DashboardState(connections = listOf(connection), activeId = "a", snapshot = dashboard("Main"))
        assertFalse(portfolioAvailable(state))
        assertTrue(portfolioAvailable(state.copy(snapshot = state.snapshot!!.copy(portfolioAvailable = true))))
        assertFalse(portfolioAvailable(state.copy(activeId = null)))
        assertFalse(portfolioAvailable(state.copy(liveSnapshot = dashboard("Other"))))
    }

    @Test fun requestIsAuthenticatedGetWithNoOtherInstanceSelector() {
        val request = portfolioRequest(Connection("a", "Main", DASHBOARD_URL, "synthetic-key-" + "x".repeat(32)))
        assertEquals("GET", request.method)
        assertEquals("/v2/dashboard", request.url.encodedPath)
        assertEquals("view=portfolio", request.url.encodedQuery)
        assertTrue(request.header("Authorization")!!.startsWith("Bearer "))
        assertNull(request.header("X-Dashboard-Margin"))
        assertFalse(request.url.toString().contains("synthetic-key"))
    }

    @Test fun exactTotalsAreValidatedAndUnavailableInstanceNeverSuppliesZero() {
        val value = portfolio()
        validatePortfolio(value)
        assertThrows(DashboardError::class.java) { validatePortfolio(value.copy(totals = value.totals.copy(equity = 5000.0))) }
        assertThrows(DashboardError::class.java) { validatePortfolio(value.copy(instances = listOf(value.instances[0], value.instances[0]))) }
        val partial = value.copy(status = "partial", instances = value.instances.mapIndexed { index, item ->
            if (index == 1) item.copy(status = "unavailable", dashboard = null, balances = null) else item },
            totals = PortfolioTotals(null, null, null, null, null))
        validatePortfolio(partial)
        assertThrows(DashboardError::class.java) { validatePortfolio(partial.copy(totals = partial.totals.copy(equity = 0.0))) }
    }
}

package com.shadowpheonix.tradingdashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class Page { Dashboard, Strategies, Trades, Analytics, Settings }

data class DashboardQuery(val period: String, val model: String, val mode: String)
fun queryFor(page: Page, period: String, model: String, mode: String): DashboardQuery =
    if (page == Page.Analytics || page == Page.Trades) DashboardQuery(period, model, mode)
    else DashboardQuery("All", "all", "real")

val homeSections = listOf("Portfolio overview", "Active trades", "Account balances")

enum class BtcPriceDirection { Unknown, Up, Down }

data class DashboardState(
    val appearance: Appearance = Appearance.Light,
    val connections: List<Connection> = emptyList(),
    val activeId: String? = null,
    val page: Page = Page.Dashboard,
    val period: String = "1M",
    val account: String = "all",
    val mode: String = "real",
    val snapshot: DashboardSnapshot? = null,
    val liveSnapshot: DashboardSnapshot? = null,
    val btcTicker: BtcTicker? = null,
    val btcPriceDirection: BtcPriceDirection = BtcPriceDirection.Unknown,
    val filteredSnapshot: DashboardSnapshot? = null,
    val queryLoading: Boolean = false,
    val balances: BalanceSnapshot? = null,
    val balancesStale: Boolean = true,
    val balancesLoading: Boolean = false,
    val balanceError: String? = null,
    val macd: MacdSnapshot? = null,
    val macdError: String? = null,
    val macdCached: Boolean = false,
    val addKeyRequested: Boolean = false,
    val refreshing: Boolean = false,
    val connecting: Boolean = false,
    val connectionHealthy: Boolean = false,
    val error: String? = null,
    val now: Long = System.currentTimeMillis(),
) {
    val showConnectionForm: Boolean get() = connections.isEmpty() || addKeyRequested
    val connectionLabel: String get() = when {
        connectionHealthy && snapshot?.historyMode == "edge" -> "● Historical data"
        connectionHealthy && page == Page.Analytics -> "● Snapshot"
        fresh -> "● Market live"
        connectionHealthy -> "● Connected"
        refreshing || connecting -> "Connecting…"
        else -> "○ Offline"
    }
    val active: Connection? get() = connections.find { it.id == activeId }
    val fresh: Boolean get() = connectionHealthy && snapshot?.let { isFresh(it, now) } == true
    val liveBtcPriceDirection: BtcPriceDirection get() =
        if (connectionHealthy && btcTickerIsFresh(btcTicker, now)) btcPriceDirection else BtcPriceDirection.Unknown
    val query: DashboardQuery get() = queryFor(page, period, account, mode)
    val followLive: Boolean get() = page != Page.Analytics
    fun withPage(nextPage: Page): DashboardState {
        val next = copy(page = nextPage)
        return if (next.query == query && (next.followLive == followLive || query != liveQuery)) next else next.forQuery()
    }
    fun withBtcTicker(incoming: BtcTicker?): DashboardState {
        val accepted = newestBtcTicker(btcTicker, incoming) ?: return this
        val direction = when {
            btcTicker == null -> BtcPriceDirection.Unknown
            accepted.price > btcTicker.price -> BtcPriceDirection.Up
            accepted.price < btcTicker.price -> BtcPriceDirection.Down
            else -> btcPriceDirection
        }
        return copy(btcTicker = accepted, btcPriceDirection = direction)
    }
    fun withLiveSnapshot(value: DashboardSnapshot): DashboardState = copy(liveSnapshot = value,
        snapshot = if (query == liveQuery && followLive) value else snapshot).withBtcTicker(value.btcTicker)
    fun forQuery(): DashboardState = copy(snapshot =
        (if (query == liveQuery && followLive) liveSnapshot else filteredSnapshot)?.takeIf {
            it.period == query.period && it.selectedModel == query.model && it.historyMode == query.mode })
}

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val appearanceStore = AppearanceStore(application)
    private val store = ConnectionStore(application)
    private val api = DashboardApi()
    private val btcCache = BtcTickerCache(application)
    private val mutable = MutableStateFlow(DashboardState())
    val state = mutable.asStateFlow()
    val alertInbox = AlertInboxNavigation()
    private var balanceJob: Job? = null
    private var connectionJob: Job? = null
    private var balanceGeneration = 0L
    private var visible = false
    private val macdSession = MacdSession(viewModelScope, api::macd,
        onValue = { value -> mutable.update { it.copy(macd = value, macdError = null, macdCached = false) } },
        onError = { error -> mutable.update { it.copy(macdError = error, macdCached = false) } },
        cache = MacdCacheStore(java.io.File(application.filesDir, "macd-charts")),
        onCached = { value -> mutable.update { it.copy(macd = value, macdError = null, macdCached = true) } },
        onRefreshing = { mutable.update { it.copy(macdError = null) } })
    private val session = ForegroundSession(viewModelScope,
        stream = { api.stream(it, liveQuery.period, liveQuery.model, liveQuery.mode) },
        fetch = { connection, query -> api.fetch(connection, query.period, query.model, query.mode) },
        onLive = { snapshot ->
            snapshot.btcTicker?.let { btcCache.save(it) }
            val connections = mutable.value.connections.map {
                if (it.id == mutable.value.activeId) it.copy(label = snapshot.instance) else it
            }
            if (connections != mutable.value.connections) store.save(connections)
            mutable.update { old ->
                if ((old.liveSnapshot?.generatedAt ?: 0) > snapshot.generatedAt) old
                else old.withLiveSnapshot(snapshot).copy(connections = connections,
                    now = System.currentTimeMillis())
            }
        },
        onFiltered = { snapshot -> mutable.update { old ->
            old.copy(filteredSnapshot = snapshot, snapshot = snapshot, now = System.currentTimeMillis())
        } },
        onStatus = { healthy, error -> mutable.update {
            it.copy(connectionHealthy = healthy, refreshing = !healthy && error == null, error = error)
        } },
        onQueryLoading = { loading, error -> mutable.update {
            it.copy(queryLoading = loading, error = error ?: it.error)
        } })

    init {
        mutable.update { it.copy(btcTicker = btcCache.load()) }
        viewModelScope.launch { alertInbox.route.collect { selectMacd() } }
        try { mutable.update { it.copy(appearance = appearanceStore.load()) } }
        catch (_: Exception) { mutable.update { it.copy(error = "Appearance preference could not be loaded.") } }
        try {
            val saved = store.load()
            mutable.update { it.copy(connections = saved, activeId = saved.firstOrNull()?.id) }
        } catch (_: Exception) {
            mutable.update { it.copy(error = "Saved connections could not be unlocked. Add the instance again.") }
        }
    }

    fun page(page: Page) {
        mutable.update { it.withPage(page) }
        selectQuery()
        selectMacd()
    }
    private fun selectMacd(refresh: Boolean = false) {
        macdSession.select(mutable.value.active, visible && mutable.value.page == Page.Strategies && alertInbox.route.value == null, refresh)
    }
    private fun selectQuery() {
        val current = mutable.value
        session.select(current.query, current.followLive)
    }
    fun appearance(value: Appearance) {
        if (value == mutable.value.appearance) return
        try {
            appearanceStore.save(value)
            mutable.update { it.copy(appearance = value) }
        } catch (_: Exception) {
            mutable.update { it.copy(error = "Appearance could not be saved. Please try again.") }
        }
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun addKey() {
        mutable.update { it.copy(addKeyRequested = true) }
        page(Page.Settings)
    }
    fun cancelAddKey() { mutable.update { it.copy(addKeyRequested = false) } }

    fun select(id: String) {
        if (mutable.value.connections.none { it.id == id } || mutable.value.connecting) return
        if (id == mutable.value.activeId) return
        session.close()
        mutable.update { it.copy(activeId = id, account = "all", snapshot = null, balances = null,
            macd = null, macdError = null, macdCached = false,
            liveSnapshot = null, filteredSnapshot = null,
            balanceError = null, balancesStale = true, error = null, connectionHealthy = false) }
        refresh()
    }

    fun period(value: String) {
        if (value !in periods || value == mutable.value.period) return
        mutable.update { it.copy(period = value, error = null).forQuery() }
        selectQuery()
    }

    fun account(value: String) {
        if (value !in models || value == mutable.value.account) return
        mutable.update { it.copy(account = value, error = null).forQuery() }
        selectQuery()
    }

    fun mode(value: String) {
        if (value !in historyModes || value == mutable.value.mode) return
        mutable.update { it.copy(mode = value, error = null).forQuery() }
        selectQuery()
    }

    fun connect(key: String) {
        if (!visible || mutable.value.connecting) return
        connectionJob = viewModelScope.launch {
            mutable.update { it.copy(connecting = true, error = null) }
            try {
                val draft = connectionFromKey(UUID.randomUUID().toString(), key)
                val snapshot = api.fetch(draft, "All", "all", "real")
                val connection = draft.copy(label = snapshot.instance)
                val connections = mutable.value.connections + connection
                store.save(connections)
                mutable.update { it.copy(connections = connections, activeId = connection.id,
                    account = "all", snapshot = snapshot, liveSnapshot = snapshot, filteredSnapshot = null,
                    page = Page.Dashboard, addKeyRequested = false,
                    balances = null, balanceError = null, balancesStale = true,
                    refreshing = false, error = null, connectionHealthy = true, now = System.currentTimeMillis())
                    .withBtcTicker(snapshot.btcTicker) }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(error = if (e is DashboardError || e is IllegalArgumentException)
                    e.message ?: "Could not connect." else "Could not connect securely or save the key. Check your connection.") }
            } finally { mutable.update { it.copy(connecting = false) } }
        }
    }

    fun forget(id: String) {
        val connections = mutable.value.connections.filterNot { it.id == id }
        try { store.save(connections) }
        catch (_: Exception) { mutable.update { it.copy(error = "Could not remove this saved connection.") }; return }
        macdSession.forget(id)
        val wasActive = mutable.value.activeId == id
        if (wasActive) {
            session.close(); balanceGeneration++
            balanceJob?.cancel()
        }
        mutable.update { it.copy(connections = connections,
            activeId = if (wasActive) connections.firstOrNull()?.id else it.activeId,
            snapshot = if (wasActive) null else it.snapshot, refreshing = if (wasActive) false else it.refreshing,
            liveSnapshot = if (wasActive) null else it.liveSnapshot,
            filteredSnapshot = if (wasActive) null else it.filteredSnapshot,
            balances = if (wasActive) null else it.balances, balancesStale = if (wasActive) true else it.balancesStale,
            balanceError = if (wasActive) null else it.balanceError,
            macd = if (wasActive) null else it.macd, macdError = if (wasActive) null else it.macdError, macdCached = if (wasActive) false else it.macdCached,
            connectionHealthy = if (wasActive) false else it.connectionHealthy) }
        if (wasActive) refresh()
    }

    // Only explicit refresh, instance opening, and foreground resume call this method.
    fun refresh() {
        openSession()
        session.refresh()
        refreshBalances()
        selectMacd(refresh = true)
    }

    private fun refreshBalances() {
        val generation = ++balanceGeneration
        balanceJob?.cancel()
        val connection = mutable.value.active ?: return
        if (!visible) return
        balanceJob = viewModelScope.launch {
            mutable.update { it.copy(balancesLoading = true, balancesStale = true, balanceError = null) }
            try {
                val value = api.balances(connection)
                require(mutable.value.snapshot?.let { it.instance == value.instance } != false)
                if (generation == balanceGeneration) mutable.update {
                    it.copy(balances = value, balancesStale = false, balanceError = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == balanceGeneration) mutable.update {
                    it.copy(balancesStale = true, balanceError = "Balances unavailable. Refresh to retry.")
                }
            } finally {
                if (generation == balanceGeneration) mutable.update { it.copy(balancesLoading = false) }
            }
        }
    }

    private fun openSession() {
        if (!visible) return
        val before = mutable.value
        val connection = before.active ?: return
        selectQuery()
        session.open(connection)
    }

    suspend fun streamWhileVisible() {
        visible = true
        refresh()
        try {
            while (true) {
                mutable.update { it.copy(now = System.currentTimeMillis()) }
                delay(1_000)
            }
        } finally {
            visible = false
            session.close(); balanceGeneration++
            macdSession.close()
            balanceJob?.cancel(); connectionJob?.cancel()
            mutable.update { it.copy(connectionHealthy = false, refreshing = false,
                balancesLoading = false, balancesStale = true) }
        }
    }
}

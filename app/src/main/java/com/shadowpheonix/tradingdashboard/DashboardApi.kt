package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val dashboardJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
val periods = listOf("1W", "1M", "3M", "1Y", "YTD", "All")
val historyModes = linkedMapOf("real" to "Real money", "edge" to "Backtest")

fun validatedBaseUrl(raw: String): HttpUrl {
    val url = raw.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS server URL.")
    require(url.isHttps) { "The dashboard server must use HTTPS." }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Use the server URL without a username, password, query or fragment."
    }
    return url
}

fun dashboardRequest(connection: Connection, period: String, account: String, mode: String = "real"): Request {
    require(period in periods) { "Invalid period." }
    require(account in models) { "Invalid model." }
    require(mode in historyModes) { "Invalid data source." }
    require(validAccessKey(connection.accessKey)) {
        "Enter the full dashboard access key (32–256 characters)."
    }
    val base = validatedBaseUrl(connection.baseUrl)
    val url = base.newBuilder()
        .encodedPath(base.encodedPath.trimEnd('/') + "/v2/dashboard")
        .addQueryParameter("period", period)
        .addQueryParameter("model", account)
        .apply { if (mode != "real") addQueryParameter("mode", mode) }
        .build()
    return Request.Builder().url(url).get()
        .header("Authorization", "Bearer ${connection.accessKey}")
        .header("Accept", "application/json").build()
}

fun dashboardStreamRequest(connection: Connection, period: String, account: String, mode: String = "real"): Request {
    val get = dashboardRequest(connection, period, account, mode)
    val url = get.url.newBuilder().encodedPath(get.url.encodedPath.removeSuffix("/dashboard") + "/live").build()
    return get.newBuilder().url(url).build()
}

class DashboardApi {
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()

    fun stream(connection: Connection, period: String, account: String, mode: String = "real") = callbackFlow {
        val request = dashboardStreamRequest(connection, period, account, mode)
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) throw DashboardError("Dashboard response is too large.")
                    val value = dashboardJson.decodeFromString<DashboardSnapshot>(text)
                    validateSnapshot(value, period, account, mode)
                    if (!trySend(value).isSuccess) {
                        webSocket.cancel()
                        close(DashboardError("Dashboard updates could not be processed."))
                    }
                } catch (_: Exception) {
                    webSocket.cancel()
                    close(DashboardError("Could not read dashboard updates securely."))
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.cancel()
                close(DashboardError("Unsupported dashboard update format."))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                close(DashboardError("Connection lost. Check the dashboard server and your network."))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(DashboardError("Dashboard connection closed."))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        })
        awaitClose { socket.cancel() }
    }

    suspend fun fetch(connection: Connection, period: String, account: String, mode: String = "real"): DashboardSnapshot {
        val request = dashboardRequest(connection, period, account, mode)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(DashboardError("Connection lost. Check your network and dashboard server."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val snapshot = response.use { parseResponse(it, period, account, mode) }
                        if (continuation.isActive) continuation.resume(snapshot)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is DashboardError) e else DashboardError("Could not read dashboard data securely."))
                    }
                }
            })
        }
    }

    suspend fun balances(connection: Connection): BalanceSnapshot {
        val request = balanceRequest(connection)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(DashboardError("Balances unavailable. Refresh to retry."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use { dashboardJson.decodeFromString<BalanceSnapshot>(readBody(it)) }
                        validateBalances(value)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(DashboardError("Balances unavailable. Refresh to retry."))
                    }
                }
            })
        }
    }

    suspend fun macd(connection: Connection): MacdSnapshot {
        val request = macdRequest(connection)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(DashboardError("MACD data unavailable."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use { dashboardJson.decodeFromString<MacdSnapshot>(readBody(it)) }
                        validateMacd(value)
                        if (continuation.isActive) continuation.resume(value)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(DashboardError("MACD data unavailable."))
                    }
                }
            })
        }
    }

    private fun readBody(response: Response): String {
        when (response.code) {
            401, 403 -> throw DashboardError("Access denied. Check the dashboard key.")
            429 -> throw DashboardError("Too many requests. Wait a moment before refreshing.")
        }
        if (response.code in 300..399) throw DashboardError("Server redirects are blocked.")
        if (!response.isSuccessful) throw DashboardError("The dashboard server is unavailable. Try again shortly.")
        val body = response.body ?: throw DashboardError("The server returned no data.")
        val maxSize = 2L * 1024 * 1024
        if (body.contentLength() > maxSize || body.source().request(maxSize + 1)) {
            throw DashboardError("Dashboard response is too large.")
        }
        return body.string()
    }

    private fun parseResponse(response: Response, period: String, account: String, mode: String): DashboardSnapshot {
        val value = dashboardJson.decodeFromString<DashboardSnapshot>(readBody(response))
        validateSnapshot(value, period, account, mode)
        return value
    }

}

fun validateSnapshot(snapshot: DashboardSnapshot, period: String, account: String, mode: String = "real") {
    snapshot.normalizedEquity?.let { equity ->
        equity.drawdown?.let { dd ->
            if (!dd.usd.isFinite() || !dd.pct.isFinite() || dd.usd < 0 || dd.pct !in 0.0..100.0 ||
                (dd.usd == 0.0) != (dd.pct == 0.0)) throw DashboardError("Invalid Edge Monitor drawdown.")
        }
        if (equity.baseEquity != 10000.0 || snapshot.metrics.realizedPnl == null || equity.points.isEmpty() ||
            equity.points.size > 20000 || equity.points.map { it.timestamp }.distinct().size != equity.points.size ||
            equity.points.any { it.timestamp <= 0 || it.timestamp > snapshot.generatedAt || !it.equity.isFinite() || it.equity < 0 }) {
            throw DashboardError("Invalid normalized equity curve.")
        }
    }
    snapshot.positions.forEach { position ->
        val stops = position.stopPrices
        if (stops.size > 6 || stops.any { !it.isFinite() || it <= 0 || it > 1e20 } ||
            stops != stops.distinct().sorted() ||
            stops.isNotEmpty() && position.stopPrice != null && stops != listOf(position.stopPrice)) {
            throw DashboardError("Invalid saved stop prices.")
        }
    }
    snapshot.btcTicker?.let { ticker ->
        if (mode != "real" || ticker.symbol != "BTCUSDT" || !ticker.price.isFinite() || ticker.price <= 0 ||
            !ticker.change24hPct.isFinite() || ticker.change24hPct < -100 || ticker.updatedAt <= 0 ||
            snapshot.generatedAt - ticker.updatedAt !in 0L..15_000L) {
            throw DashboardError("The server returned invalid BTC price data.")
        }
    }
    snapshot.openPerformance?.let { performance ->
        if (mode != "real") throw DashboardError("Unexpected live return data.")
        val idleTime = performance.idleBalanceSampledAt
        if (idleTime != null && (!performance.usesIdleBalances || idleTime <= 0 || idleTime > snapshot.generatedAt) ||
            performance.usesIdleBalances && performance.entryEquityUsd != null && idleTime == null) {
            throw DashboardError("The server returned invalid idle balance sampling data.")
        }
        listOf(performance.investedUsd to performance.investedReturnPct,
            performance.entryEquityUsd to performance.entryEquityReturnPct).forEach { (capital, percentage) ->
            val pnl = snapshot.metrics.unrealizedPnl
            val expected = if (capital != null && capital > 0 && pnl != null) pnl / capital * 100 else null
            if (capital != null && (!capital.isFinite() || capital < 0) ||
                (percentage == null) != (expected == null) ||
                percentage != null && (!percentage.isFinite() || expected == null || !expected.isFinite() ||
                    kotlin.math.abs(percentage - expected) > 0.000001)) {
                throw DashboardError("The server returned invalid open return data.")
            }
        }
    }
    val accounts = snapshot.accounts.map { it.id }.toSet()
    if (snapshot.instance.isBlank() || snapshot.instance.length > 240) throw DashboardError("Invalid instance name.")
    val sources = if (mode == "real") setOf("live") else setOf("backtest", "live")
    if (snapshot.schemaVersion != 2 || snapshot.period != period || snapshot.selectedModel != account ||
        snapshot.historyMode != mode || mode !in historyModes ||
        (mode == "edge" && (snapshot.dataSource != "edge_csv" || snapshot.live || snapshot.positions.isNotEmpty() ||
            snapshot.strategies.isNotEmpty() || snapshot.stateUpdatedAt != null || snapshot.priceUpdatedAt != null)) ||
        (mode == "real" && snapshot.dataSource != "bot_files_binance") ||
        snapshot.trades.any { it.source !in sources } ||
        accounts != models.keys - "all" || accounts.size != snapshot.accounts.size || snapshot.generatedAt <= 0 ||
        snapshot.monthlyReturns.any { it.month !in 1..12 } ||
        snapshot.trades.map { it.id }.distinct().size != snapshot.trades.size ||
        snapshot.strategies.map { it.id }.distinct().size != snapshot.strategies.size ||
        (account != "all" && account !in accounts) ||
        snapshot.positions.any { it.accountId !in accounts || (account != "all" && it.accountId != account) } ||
        snapshot.trades.any { it.accountId !in accounts || (account != "all" && it.accountId != account) }) {
        throw DashboardError("The server returned an unsupported dashboard or a different model/period.")
    }
}

class DashboardError(message: String) : IOException(message)

fun balanceRequest(connection: Connection): Request = dashboardRequest(connection, "All", "all")
    .newBuilder().url(validatedBaseUrl(connection.baseUrl).newBuilder().encodedPath("/v2/balances").build()).build()

fun macdRequest(connection: Connection): Request = dashboardRequest(connection, "All", "all")
    .let { it.newBuilder().url(it.url.newBuilder().query(null).addQueryParameter("view", "macd").build()).build() }

fun validateBalances(value: BalanceSnapshot) {
    val original = value.originalEquity
    val expectedPnl = if (original != null && value.equity != null && value.status == "ok") value.equity - original else null
    val expectedReturn = if (expectedPnl != null) expectedPnl / original!! * 100 else null
    require(original == null || original.isFinite() && original > 0)
    listOf(value.pnl to expectedPnl, value.returnPct to expectedReturn).forEach { (actual, expected) ->
        require((actual == null) == (expected == null) &&
            (actual == null || actual.isFinite() && expected!!.isFinite() && kotlin.math.abs(actual - expected) < 0.000001))
    }
    fun valid(equity: Double?, status: String, sampled: Long?): Boolean =
        if (status == "ok") equity != null && equity.isFinite() && sampled != null && sampled > 0 && sampled <= value.generatedAt
        else status in setOf("failed", "unconfigured", "not_configured") && equity == null && sampled == null
    require(value.schemaVersion == 2 && value.instance.isNotBlank() && value.generatedAt > 0 &&
        value.attemptedAt in 1L..value.generatedAt && value.models.map { it.id } == models.keys.filter { it != "all" } &&
        valid(value.equity, value.status, value.sampledAt) &&
        value.models.all { valid(it.equity, it.status, it.sampledAt) } &&
        (value.status != "ok" || value.models.all { it.status in setOf("ok", "not_configured") } &&
            kotlin.math.abs(value.models.mapNotNull { it.equity }.sum() - value.equity!!) < 0.000001))
    value.models.forEach { model ->
        val allocations = model.walletAllocations
        require(allocations.size <= 2 && allocations.map { it.wallet }.distinct().size == allocations.size)
        require(allocations.all { it.wallet in setOf("A", "B") &&
            (it.pct == null || it.pct.isFinite() && value.status == "ok" && model.status == "ok") })
        require(model.status != "not_configured" || allocations.isEmpty())
    }
}

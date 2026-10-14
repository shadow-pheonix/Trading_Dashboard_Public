package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

val liveQuery = DashboardQuery("All", "all", "real")

// A foreground instance owns one real stream. Screen filters use cancellable
// HTTP reads, so changing a page/query never closes that stream or polls balances.
internal class ForegroundSession(
    private val scope: CoroutineScope,
    private val stream: (Connection) -> Flow<DashboardSnapshot>,
    private val fetch: suspend (Connection, DashboardQuery) -> DashboardSnapshot,
    private val onLive: (DashboardSnapshot) -> Unit,
    private val onFiltered: (DashboardSnapshot) -> Unit,
    private val onStatus: (Boolean, String?) -> Unit,
    private val onQueryLoading: (Boolean, String?) -> Unit,
    private val initialRetryMillis: Long = 5_000,
) {
    private var connection: Connection? = null
    private var streamJob: Job? = null
    private var queryJob: Job? = null
    private var generation = 0L
    private var queryGeneration = 0L
    private var query = liveQuery
    private var followLive = true

    fun open(value: Connection) {
        if (connection?.id == value.id && connection?.accessKey == value.accessKey && streamJob?.isActive == true) return
        close()
        connection = value
        val session = generation
        streamJob = scope.launch {
            var retryMillis = initialRetryMillis
            while (session == generation) {
                onStatus(false, null)
                try {
                    stream(value).collect { snapshot ->
                        if (session == generation) {
                            retryMillis = initialRetryMillis
                            onLive(snapshot)
                            onStatus(true, null)
                            if (followLive) loadQuery()
                        }
                    }
                    if (session == generation) onStatus(false, "Dashboard connection closed.")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (session == generation) onStatus(false,
                        if (e is DashboardError) e.message else "Connection lost. Showing the last received data.")
                }
                delay(retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(30_000)
            }
        }
        if (!followLive) loadQuery()
    }

    fun select(value: DashboardQuery, followLive: Boolean = true) {
        if (query == value && this.followLive == followLive) return
        query = value
        this.followLive = followLive
        queryGeneration++
        queryJob?.cancel()
        queryJob = null
        onQueryLoading(false, null)
        loadQuery()
    }

    fun refresh() = loadQuery(includeLive = true)

    private fun loadQuery(includeLive: Boolean = false) {
        val active = connection ?: return
        if ((!includeLive && query == liveQuery && followLive) || queryJob?.isActive == true) return
        val selected = query
        val session = generation
        val request = queryGeneration
        val selectedFollowsLive = followLive
        queryJob = scope.launch {
            onQueryLoading(true, null)
            var error: String? = null
            try {
                val value = fetch(active, selected)
                if (session == generation && request == queryGeneration) {
                    if (selected == liveQuery && selectedFollowsLive) onLive(value) else onFiltered(value)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                error = if (e is DashboardError) e.message else "Selected data unavailable. Refresh to retry."
            } finally {
                if (session == generation && request == queryGeneration) onQueryLoading(false, error)
            }
        }
    }

    fun close() {
        generation++; queryGeneration++
        streamJob?.cancel(); queryJob?.cancel()
        streamJob = null; queryJob = null; connection = null
        onQueryLoading(false, null)
    }
}

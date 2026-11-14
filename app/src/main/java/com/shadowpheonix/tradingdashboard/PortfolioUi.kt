package com.shadowpheonix.tradingdashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun PortfolioScreen(connection: Connection, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val owner = LocalLifecycleOwner.current
    val api = remember { DashboardApi() }
    var value by remember(connection.id) { mutableStateOf<PortfolioSnapshot?>(null) }
    var error by remember(connection.id) { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(connection.id, refresh, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                loading = true
                try { value = api.portfolio(connection); error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    value = null
                    error = if (failure is DashboardError) failure.message else "Portfolio unavailable. Refresh to retry."
                } finally { loading = false }
                delay(10_000)
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    fun money(amount: Double?, signed: Boolean = false): String = amount?.takeIf { it.isFinite() }?.let {
        (if (it < 0) "−" else if (signed && it > 0) "+" else "") +
            NumberFormat.getCurrencyInstance(Locale.US).format(kotlin.math.abs(it))
    } ?: "—"
    Scaffold(containerColor = colors.background, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Text("Portfolio", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { refresh++ }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Refresh portfolio") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { Text(message, color = colors.error) } }
            value?.let { portfolio ->
                item {
                    Surface(shape = MaterialTheme.shapes.large, color = colors.surface) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Total equity", color = colors.onSurfaceVariant)
                            Text(money(portfolio.totals.equity), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            portfolio.totals.returnPct?.let { Text(String.format(Locale.US, "%+.1f%% on original equity", it),
                                color = if (it < 0) colors.error else colors.secondary) }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("Open P&L", color = colors.onSurfaceVariant)
                            Text(money(portfolio.totals.unrealizedPnl, true), fontSize = 28.sp, fontWeight = FontWeight.Bold,
                                color = if ((portfolio.totals.unrealizedPnl ?: 0.0) < 0) colors.error else colors.secondary)
                            Text(portfolio.totals.openPositions?.let { "$it open positions" } ?: "Position count unavailable",
                                color = colors.onSurfaceVariant, fontSize = 12.sp)
                            Text(DateTimeFormatter.ofPattern("dd MMM · HH:mm:ss").withZone(ZoneId.systemDefault())
                                .format(Instant.ofEpochMilli(portfolio.generatedAt)), color = colors.onSurfaceVariant, fontSize = 11.sp)
                            if (portfolio.status != "ok") Text("Some values are unavailable. Totals require complete data.",
                                color = colors.error, fontSize = 12.sp)
                        }
                    }
                }
                portfolio.instances.forEach { instance ->
                    item(key = instance.id + ":title") {
                        Text(instance.label, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    item(key = instance.id + ":balances") {
                        BalancesCard(DashboardState(snapshot = instance.dashboard, balances = instance.balances,
                            balancesStale = instance.balances?.status != "ok"))
                    }
                    item(key = instance.id + ":positions") {
                        instance.dashboard?.let { PositionsCard(it) }
                            ?: Text("Positions unavailable", color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

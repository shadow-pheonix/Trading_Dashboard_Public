package com.shadowpheonix.tradingdashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.LocalActivity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Blue: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val Green: Color
    @Composable get() = MaterialTheme.colorScheme.secondary
private val Red: Color
    @Composable get() = MaterialTheme.colorScheme.error
private val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Backdrop: Color
    @Composable get() = MaterialTheme.colorScheme.background
private val Line: Color
    @Composable get() = MaterialTheme.colorScheme.outlineVariant
private val CardSurface: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val Numeric = TextStyle(fontFeatureSettings = "tnum")
private val CardShape = RoundedCornerShape(22.dp)
private val HomeCardShape = RoundedCornerShape(24.dp)

private fun number(value: Double?, decimals: Int = 2): String = value?.takeIf { it.isFinite() }?.let {
    NumberFormat.getNumberInstance(Locale.US).apply { minimumFractionDigits = decimals; maximumFractionDigits = decimals }.format(it)
} ?: "—"
private fun money(value: Double?, signed: Boolean = false, currency: String = "USD"): String {
    if (value == null || !value.isFinite()) return "—"
    val prefix = if (currency == "USD") "$" else "$currency "
    return (if (value < 0) "−" else if (signed && value > 0) "+" else "") + prefix + number(abs(value))
}
private fun percent(value: Double?): String = if (value == null || !value.isFinite()) "—" else (if (value > 0) "+" else "") + number(value, 1) + "%"
@Composable
private fun tone(value: Double?): Color = when { value == null -> Ink; value < 0 -> Red; else -> Green }
private fun date(timestamp: Long, pattern: String = "dd MMM, HH:mm"): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))

@Composable
fun DashboardApp(vm: DashboardViewModel, appLockEnabled: Boolean = true, appLockError: String? = null,
                 onAppLockChange: (Boolean) -> Unit = {}, updateState: AppUpdateState = AppUpdateState(),
                 onUpdateCheck: () -> Unit = {}, onUpdateInstall: () -> Unit = {}, onUpdateLater: () -> Unit = {},
                 alertsEnabled: Boolean = true, onAlertsChange: (Boolean) -> Unit = {}, alertStatus: List<Pair<String, String>> = emptyList(),
                 alertHistory: Map<String, List<DashboardAlert>> = emptyMap()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val inbox by vm.alertInbox.route.collectAsStateWithLifecycle()
    val savedScreens = rememberSaveableStateHolder()
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = state.appearance.isDark(systemDark)
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, dark) {
        val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        onDispose { }
    }
    DashboardTheme(state.appearance) {
        AppUpdatePrompt(updateState, onUpdateInstall, onUpdateLater)
        if (inbox != null) {
            AlertInboxPage(state.connections, alertHistory, inbox!!, vm.alertInbox::back)
        } else savedScreens.SaveableStateProvider("dashboard") {
            Scaffold(containerColor = Backdrop, bottomBar = {
                Surface(color = CardSurface, shadowElevation = 0.dp) {
                    NavigationBar(containerColor = CardSurface, tonalElevation = 0.dp) {
                        val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.BarChart, Icons.Outlined.History,
                            Icons.Outlined.Insights, Icons.Outlined.Settings)
                        Page.entries.forEachIndexed { i, page ->
                            NavigationBarItem(selected = state.page == page, onClick = { vm.page(page) },
                                icon = { Icon(icons[i], null, Modifier.size(22.dp)) },
                                label = { Text(if (page == Page.Dashboard) "Home" else page.name,
                                    fontSize = 11.sp, fontWeight = if (state.page == page) FontWeight.SemiBold else FontWeight.Normal) },
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue,
                                    indicatorColor = Blue.copy(alpha = 0.09f), unselectedIconColor = Muted, unselectedTextColor = Muted))
                        }
                    }
                }
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    Header(state, vm::refresh, vm::select) { vm.page(Page.Settings) }
                    if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
                    state.error?.let { message ->
                        Notice(message, Red, action = "Dismiss", onAction = vm::dismissError)
                    }
                    when {
                        state.page == Page.Settings -> SettingsScreen(state, vm, appLockEnabled, appLockError, onAppLockChange, updateState, onUpdateCheck, onUpdateInstall, alertsEnabled, onAlertsChange, alertStatus, { vm.alertInbox.open() })
                        state.active == null -> SettingsScreen(state, vm, appLockEnabled, appLockError, onAppLockChange, updateState, onUpdateCheck, onUpdateInstall, alertsEnabled, onAlertsChange, alertStatus, { vm.alertInbox.open() })
                        else -> ContentScreen(state, vm)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Header(state: DashboardState, onRefresh: () -> Unit, onSelect: (String) -> Unit, onManage: () -> Unit) {
    var instanceMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).background(CardSurface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Dashboard, null, tint = Blue, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Trading Dashboard", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Lock, null, tint = Muted, modifier = Modifier.size(11.dp))
                    Text("Read-only portfolio", color = Muted, fontSize = 11.sp)
                    (state.liveSnapshot ?: state.snapshot)?.let {
                        Text("· ${date(it.generatedAt, "dd MMM HH:mm:ss")}", color = Muted, fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { contentDescription = "Live data updated ${date(it.generatedAt)}" })
                    }
                }
            }
            OutlinedIconButton(onClick = onRefresh, enabled = !state.refreshing && state.active != null,
                shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Line),
                colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = CardSurface, contentColor = Ink)) {
                Icon(Icons.Outlined.Refresh, "Refresh dashboard", Modifier.size(21.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                Surface(onClick = { instanceMenu = true }, color = CardSurface, shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Line)) {
                    Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Dns, null, Modifier.size(17.dp), tint = Blue)
                        Text(state.snapshot?.instance ?: state.active?.label ?: "Connect an instance", color = Ink,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.ExpandMore, "Choose instance", Modifier.size(18.dp), tint = Muted)
                    }
                }
                DropdownMenu(expanded = instanceMenu, onDismissRequest = { instanceMenu = false }) {
                    state.connections.forEach { connection ->
                        DropdownMenuItem(text = { Text(connection.label) }, onClick = { instanceMenu = false; onSelect(connection.id) })
                    }
                    DropdownMenuItem(text = { Text("Add / manage instances") }, onClick = { instanceMenu = false; onManage() })
                }
            }
            Badge(state.connectionLabel, if (state.connectionHealthy && (state.page == Page.Analytics || !state.fresh)) Blue
                else if (state.fresh) Green else Muted)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
private fun Notice(text: String, color: Color, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(color.copy(alpha = 0.06f)).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = color) }
    }
}

@Composable
private fun Filters(state: DashboardState, vm: DashboardViewModel) {
    var accountsOpen by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState()).padding(4.dp)) {
            periods.forEach { period ->
                Text(period, fontSize = 13.sp, fontWeight = if (period == state.period) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (period == state.period) MaterialTheme.colorScheme.onPrimary else Muted,
                    modifier = Modifier.background(if (period == state.period) Blue else Color.Transparent, RoundedCornerShape(11.dp))
                        .clickable { vm.period(period) }.padding(horizontal = 19.dp, vertical = 12.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Box {
                TextButton(onClick = { sourceMenu = true }) {
                    Text(historyModes.getValue(state.mode))
                    Icon(Icons.Outlined.ExpandMore, "Choose trade data source", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                    historyModes.forEach { (mode, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { sourceMenu = false; vm.mode(mode) })
                    }
                }
            }
            Box {
                TextButton(onClick = { accountsOpen = true }) {
                    Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(models.getValue(state.account))
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = accountsOpen, onDismissRequest = { accountsOpen = false }) {
                    models.forEach { (id, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { accountsOpen = false; vm.account(id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentScreen(state: DashboardState, vm: DashboardViewModel) {
    val snapshot = state.snapshot
    val balanceSample = scopedBalanceSample(state)
    var tradeDetail by remember { mutableStateOf<Trade?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.page == Page.Analytics || state.page == Page.Trades) {
            item { SectionHeading(if (state.page == Page.Analytics) "Strategy analytics" else "Trade history",
                if (state.page == Page.Analytics) "Performance for ${state.period}"
                else if (snapshot == null) "Loading trade history…"
                else if (snapshot.trades.isEmpty() && snapshot.metrics.closedTrades == null) "History unavailable"
                else "${snapshot.trades.size} trades in this period") }
            item { Filters(state, vm) }
        }
        if (state.page == Page.Strategies) strategyItems(state)
        else if (snapshot == null) {
            if (state.page == Page.Dashboard) item(key = homeSections[0]) {
                PortfolioOverviewCard(state)
            }
            item { Panel { EmptyState(if (state.refreshing || state.queryLoading) "Loading your dashboard…" else "No dashboard received yet. Tap refresh to try again.") } }
        } else {
            when (state.page) {
                Page.Dashboard -> {
                    homeSections.forEach { section ->
                        item(key = section) {
                            when (section) {
                                "Portfolio overview" -> PortfolioOverviewCard(state)
                                "Active trades" -> PositionsCard(snapshot)
                                "Account balances" -> BalancesCard(state)
                            }
                        }
                    }
                }
                Page.Analytics -> {
                    item { PnlCard(snapshot) }
                    item { MetricGrid(snapshot) }
                    item { CurveCard(snapshot) }
                    item { MonthlyCard(snapshot) }
                    item { Panel {
                        Text("Performance details", fontWeight = FontWeight.Bold)
                        KeyValue("Realized P&L", money(snapshot.metrics.realizedPnl, true, snapshot.currency))
                        KeyValue("Open P&L", money(snapshot.metrics.unrealizedPnl, true, snapshot.currency))
                        KeyValue(if (state.balancesStale && balanceSample?.equity != null) "Current equity (stale)" else "Current equity",
                            money(balanceSample?.equity, currency = snapshot.currency))
                        KeyValue("Closed trades", snapshot.metrics.closedTrades?.toString() ?: "—")
                        ExpandableHelp("About these values") { Text("Cash-flow-adjusted return and Sharpe need historical capital and cash-flow data. Max drawdown follows Edge Monitor trade equity.", color = Muted, fontSize = 12.sp)
                        if (snapshot.historyMode == "real") Text("Equity includes open P&L and refreshes when you open the instance or tap refresh.", color = Muted, fontSize = 11.sp) }
                    } }
                }
                Page.Strategies -> Unit
                Page.Trades -> {
                    val filtered = snapshot.trades.sortedByDescending { it.closedAt }
                    if (filtered.isEmpty()) item { Panel { EmptyState(
                        if (snapshot.trades.isEmpty() && snapshot.metrics.closedTrades == null)
                            "Trade history is not available yet." else "No closed trades in this period.") } }
                    items(filtered, key = { "trade-${it.id}" }) { trade ->
                        Panel { TradeRow(trade, snapshot, onClick = { tradeDetail = trade }) }
                    }
                }
                Page.Settings -> Unit
            }
        }
    }
    tradeDetail?.let { trade ->
        AlertDialog(onDismissRequest = { tradeDetail = null }, title = { Text("${trade.symbol} · ${trade.side}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KeyValue("Model", trade.strategy)
                KeyValue("Source", if (trade.source == "backtest") "Backtest" else "Live")
                KeyValue("Opened", trade.openedAt?.let { date(it) } ?: "Unknown")
                KeyValue("Closed", date(trade.closedAt))
                KeyValue("Entry reference", number(trade.entryPrice))
                KeyValue("Exit reference", number(trade.exitPrice))
                KeyValue("Fees", money(trade.fees, currency = snapshot?.currency ?: "USD"))
                KeyValue("Funding", money(trade.funding, true, snapshot?.currency ?: "USD"))
                KeyValue("Net P&L", money(trade.netPnl, true, snapshot?.currency ?: "USD"))
            } }, confirmButton = { TextButton(onClick = { tradeDetail = null }) { Text("Close") } })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.strategyItems(state: DashboardState) {
    item { SectionHeading("Strategies", if (state.macd?.source == "bot_spot_closed")
        "MACD · Bot-calculated spot candles" else "MACD · 15-minute spot candles") }
    if (state.macdCached) item { Text("Saved chart · refreshing", color = Muted, fontSize = 12.sp) }
    state.macdError?.let { message -> item { Text(message, color = Red, fontSize = 12.sp) } }
    items(listOf("M1", "M2", "M3"), key = { "strategy-$it" }) { model ->
        val strategy = state.snapshot?.strategies?.find { it.id == model }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ShowChart, null, tint = Blue)
                Spacer(Modifier.width(12.dp))
                Text(strategyLabel(model), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Badge(strategy?.status ?: "Model", Blue)
            }
            MacdChart(state.macd?.models?.find { it.id == model },
                stale = state.macdError != null || macdIsStale(state.macd, state.now),
                loading = state.macd == null && state.macdError == null || state.macd?.status == "loading")
        }
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = CardShape, color = CardSurface, border = BorderStroke(1.dp, Line.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyState(message: String) { Text(message, color = Muted, modifier = Modifier.padding(vertical = 20.dp)) }

@Composable
private fun PnlCard(snapshot: DashboardSnapshot) {
    val metrics = snapshot.metrics
    val total = metrics.realizedPnl
    val surface = CardSurface
    val start = Blue.copy(alpha = 0.18f).compositeOver(surface)
    val end = Blue.copy(alpha = 0.05f).compositeOver(surface)
    Surface(shape = CardShape, modifier = Modifier.fillMaxWidth(), color = surface) {
        Column(Modifier.background(Brush.linearGradient(listOf(start, end))).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Text(if (snapshot.historyMode == "edge") "Backtest P&L" else "Realized P&L", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                Badge(snapshot.currency, Muted)
            }
            Text(money(total, true, snapshot.currency), fontSize = 35.sp, fontWeight = FontWeight.Bold, color = tone(total),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (metrics.totalReturnPct != null) Badge(percent(metrics.totalReturnPct), tone(metrics.totalReturnPct))
                else Badge("${metrics.closedTrades?.toString() ?: "—"} closed trades", Blue)
                Spacer(Modifier.weight(1f))
                if (metrics.profitableDays != null && metrics.observedDays != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Profitable days", fontSize = 11.sp, color = Muted)
                        Text("${metrics.profitableDays} / ${metrics.observedDays}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text(if (snapshot.historyMode == "edge")
                "Combined backtest and live closed trades · logged fees included" else if (snapshot.dataSource == "bot_files_binance")
                "Logged closed P&L · funding unavailable" else
                "Realized for ${snapshot.period}", fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
internal fun MetricGrid(snapshot: DashboardSnapshot) {
    val stats = remember(snapshot) { tradeStatistics(snapshot) }
    val metrics = snapshot.metrics
    val drawdown = snapshot.normalizedEquity?.drawdown
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Win rate", metrics.winRatePct?.let { "${number(it, 1)}%" } ?: "—", Blue, Icons.Outlined.TrackChanges, Modifier.weight(1f).fillMaxHeight())
            Metric("Profit factor", if (stats?.hasWinsWithoutLosses == true) "No losses" else number(stats?.profitFactor),
                Ink, Icons.Outlined.TrendingUp, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Average trade", money(stats?.averagePnl, true, snapshot.currency), tone(stats?.averagePnl), Icons.Outlined.BarChart, Modifier.weight(1f).fillMaxHeight())
            Metric("Max drawdown", "${money(drawdown?.usd, currency = snapshot.currency)} (${drawdown?.pct?.let { "${number(it, 1)}%" } ?: "—"})",
                Red, Icons.Outlined.Shield, Modifier.weight(1f).fillMaxHeight(), note = "Edge Monitor · $10k base")
        }
        Panel {
            Text("Closed-trade statistics", fontWeight = FontWeight.Bold)
            KeyValue("Wins / losses / breakeven", stats?.let { "${it.wins} / ${it.losses} / ${it.breakeven}" } ?: "—")
            KeyValue("Best trade", money(stats?.bestTrade, true, snapshot.currency))
            KeyValue("Worst trade", money(stats?.worstTrade, true, snapshot.currency))
            KeyValue("Logged fees", money(stats?.fees, currency = snapshot.currency))
            ExpandableHelp("Calculation details") { Text("Fees are already included in net P&L. Profit factor = winning P&L ÷ absolute losing P&L. Max drawdown uses Edge Monitor's compounded closed-trade equity: USD on its $10k reference curve and % below the running peak.", color = Muted, fontSize = 11.sp) }
            if (drawdown == null) Text(if (snapshot.normalizedEquity == null)
                "Recorded trade capital is unavailable for drawdown."
                else "Update the dashboard backend to load Edge Monitor drawdown.", color = Muted, fontSize = 11.sp)
            if (metrics.totalReturnPct != null) KeyValue("Total return", percent(metrics.totalReturnPct))
            if (metrics.sharpeRatio != null) KeyValue("Sharpe ratio", number(metrics.sharpeRatio))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, icon: ImageVector, modifier: Modifier, note: String? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = CardSurface, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.background(color.copy(alpha = 0.06f), RoundedCornerShape(10.dp)).padding(8.dp).size(22.dp))
            Text(label, color = Muted, fontSize = 12.sp)
            Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = color)
            if (note != null) Text(note, color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
internal fun CurveCard(snapshot: DashboardSnapshot) {
    var requestedView by rememberSaveable { mutableStateOf(ChartView.Cumulative) }
    var menu by remember { mutableStateOf(false) }
    val choices = remember(snapshot) { availableChartViews(snapshot) }
    val view = requestedView.takeIf { it in choices } ?: ChartView.Cumulative
    val bars = view == ChartView.Daily || view == ChartView.Monthly
    val equity = view == ChartView.Equity || view == ChartView.NormalizedEquity
    val series = remember(snapshot, view) { chartSeries(snapshot, view) }
    val recorded = series.recorded
    val samples = series.samples
    val live = series.captured
    var selection by remember(snapshot.instance, snapshot.period, snapshot.selectedModel, snapshot.historyMode, view) {
        mutableStateOf<Int?>(null)
    }
    val index = (selection ?: samples.lastIndex).coerceIn(0, maxOf(0, samples.lastIndex))
    val title = when (view) {
        ChartView.Cumulative -> "P&L curve"
        ChartView.Daily -> "Daily P&L"
        ChartView.Monthly -> "Monthly P&L"
        ChartView.NormalizedEquity -> "Equity · $10k base"
        ChartView.Equity -> "Recorded equity"
    }
    val positive = Green
    val negative = Red
    val accent = Blue
    val grid = Line
    val muted = Muted
    fun sampleDate(timestamp: Long, axis: Boolean = false): String = DateTimeFormatter.ofPattern(
        if (view == ChartView.Monthly) "MMM yyyy" else if (axis) "dd MMM" else "dd MMM yyyy · HH:mm")
        .withZone(if (bars) java.time.ZoneOffset.UTC else ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timestamp))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menu = true }) {
                    Text(view.label, fontSize = 12.sp)
                    Icon(Icons.Outlined.ExpandMore, "Choose chart", Modifier.size(16.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) },
                        onClick = { requestedView = choice; menu = false }) }
                }
            }
        }
        if (samples.isEmpty()) EmptyState("No chart data for this period.") else {
            val inspected = samples[index]
            val values = samples.map { it.value }
            val bounds = chartBounds(values, bars)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(money(inspected.value, !equity, snapshot.currency), style = Numeric,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (equity) Ink else tone(inspected.value))
                Text(sampleDate(inspected.timestamp), color = Muted, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(positive, RoundedCornerShape(50)))
                    Text(if (view == ChartView.Equity) "Recorded" else "Closed trades", fontSize = 10.sp, color = Muted)
                }
                if (!bars && view != ChartView.Equity) Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(7.dp).background(if (live != null) accent else muted, RoundedCornerShape(50)))
                    Text(if (live != null) "Open at snapshot" else "Open P&L unavailable", fontSize = 10.sp, color = Muted)
                }
            }
            Row(Modifier.fillMaxWidth().height(210.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Canvas(Modifier.weight(1f).fillMaxHeight().semantics {
                    contentDescription = "$title, ${sampleDate(inspected.timestamp)}, ${number(inspected.value)}"
                }.pointerInput(samples) {
                    detectTapGestures { point ->
                        val inset = 6.dp.toPx()
                        selection = closestChartSample(samples, ((point.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).toDouble())
                    }
                }.pointerInput(samples) {
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        val inset = 6.dp.toPx()
                        selection = closestChartSample(samples, ((change.position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).toDouble())
                    }
                }) {
                    val inset = 6.dp.toPx()
                    fun x(i: Int): Float = inset + (size.width - 2 * inset) *
                        chartTimeFraction(samples, samples[i].timestamp).toFloat()
                    fun y(value: Double): Float = (size.height * (1 - (value - bounds.low) / (bounds.high - bounds.low))).toFloat()
                    (0..3).forEach { n ->
                        val height = size.height * n / 3
                        drawLine(grid.copy(alpha = 0.6f), Offset(0f, height), Offset(size.width, height), 0.7.dp.toPx())
                    }
                    if (bars) {
                        drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
                        samples.forEachIndexed { i, value ->
                            val width = (size.width / maxOf(samples.size * 2, 12)).coerceIn(2.dp.toPx(), 18.dp.toPx())
                            drawRoundRect((if (value.value < 0) negative else positive).copy(alpha = if (i == index) 1f else 0.55f),
                                topLeft = Offset((x(i) - width / 2).coerceIn(0f, size.width - width), minOf(y(0.0), y(value.value))),
                                size = androidx.compose.ui.geometry.Size(width, abs(y(0.0) - y(value.value)).coerceAtLeast(1.dp.toPx())),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        }
                    } else if (recorded.size > 1) {
                        val line = Path()
                        recorded.forEachIndexed { i, value ->
                            if (i == 0) line.moveTo(x(i), y(value.value)) else line.lineTo(x(i), y(value.value))
                        }
                        val fill = Path().apply { addPath(line); lineTo(x(recorded.lastIndex), size.height); lineTo(x(0), size.height); close() }
                        drawPath(fill, Brush.verticalGradient(listOf(positive.copy(alpha = 0.16f), positive.copy(alpha = 0.0f))))
                        drawPath(line, positive, style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                    if (live != null) {
                        val lastX = x(samples.lastIndex)
                        recorded.lastOrNull()?.let { lastClose ->
                            val closeX = x(recorded.lastIndex)
                            drawLine(accent.copy(alpha = 0.85f), Offset(closeX, y(lastClose.value)), Offset(lastX, y(live.value)),
                                2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                            drawCircle(positive, 3.dp.toPx(), Offset(closeX, y(lastClose.value)))
                        }
                        drawCircle(accent, 4.dp.toPx(), Offset(lastX, y(live.value)))
                    }
                    drawLine(accent.copy(alpha = 0.25f), Offset(x(index), 0f), Offset(x(index), size.height), 1.dp.toPx())
                    drawCircle(accent.copy(alpha = 0.15f), 9.dp.toPx(), Offset(x(index), y(inspected.value)))
                    drawCircle(accent, 4.dp.toPx(), Offset(x(index), y(inspected.value)))
                }
                Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End) {
                    (0..3).forEach { n -> Text(chartAxisLabel(bounds.high - (bounds.high - bounds.low) * n / 3), color = Muted, fontSize = 9.sp) }
                }
            }
            if (samples.size > 1) Row(Modifier.fillMaxWidth().padding(end = 54.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sampleDate(samples.first().timestamp, true), fontSize = 10.sp, color = Muted)
                Text(sampleDate(samples.last().timestamp, true), fontSize = 10.sp, color = Muted)
            }
            if (live != null && recorded.isNotEmpty()) Text("Dashed segment: last close → snapshot estimate",
                color = Muted, fontSize = 10.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap or drag to inspect", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("${index + 1} / ${samples.size}", fontSize = 10.sp, color = Muted)
                IconButton(onClick = { selection = index - 1 }, enabled = index > 0, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ChevronLeft, "Previous chart point", Modifier.size(18.dp))
                }
                IconButton(onClick = { selection = index + 1 }, enabled = index < samples.lastIndex, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.ChevronRight, "Next chart point", Modifier.size(18.dp))
                }
            }
        }
        if (view == ChartView.NormalizedEquity) Text(
            if (snapshot.normalizedEquity?.legacyCapitalEstimate == true) "$10k reference · includes estimated legacy capital"
            else "$10k reference · compounded trade returns", color = Muted, fontSize = 10.sp)
        else if (snapshot.normalizedEquity == null && view == ChartView.Cumulative)
            Text("Equity needs recorded trade capital. Update the dashboard if needed.", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun MonthlyCard(snapshot: DashboardSnapshot) {
    val years = snapshot.monthlyReturns.map { it.year }.distinct().sortedDescending()
    var selectedYear by remember(snapshot.instance) { mutableStateOf<Int?>(null) }
    var yearMenu by remember { mutableStateOf(false) }
    var selectedMonth by remember { mutableStateOf<MonthlyReturn?>(null) }
    val year = selectedYear?.takeIf { it in years } ?: years.firstOrNull()
    val largest = snapshot.monthlyReturns.filter { it.year == year }
        .mapNotNull { it.returnPct ?: it.pnl }.maxOfOrNull { abs(it) } ?: 0.0
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (snapshot.dataSource == "bot_files_binance") "Monthly logged P&L" else "Monthly returns", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { yearMenu = true }, enabled = years.isNotEmpty()) {
                    Text(year?.toString() ?: "—")
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
                }
                DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                    years.forEach { value -> DropdownMenuItem(text = { Text(value.toString()) }, onClick = { selectedYear = value; yearMenu = false }) }
                }
            }
        }
        if (year == null) EmptyState("Monthly performance is not available yet.") else {
            val labels = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
            (0..1).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..6).forEach { col ->
                        val month = row * 6 + col
                        val cell = snapshot.monthlyReturns.find { it.year == year && it.month == month }
                        val value = cell?.returnPct ?: cell?.pnl
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(labels[month - 1], color = Muted, fontSize = 10.sp)
                            Surface(Modifier.fillMaxWidth().height(40.dp)
                                .clickable { selectedMonth = cell ?: MonthlyReturn(year, month, null, null) }
                                .semantics { contentDescription = "${labels[month - 1]} $year, ${if (cell?.returnPct != null) percent(value) else money(value)}" },
                                color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, Line.copy(alpha = 0.65f))) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(if (value == null) "—" else number(value, 1), fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium, color = if (value == null) Muted else tone(value))
                                    if (value != null && value != 0.0) Box(Modifier.align(Alignment.BottomCenter)
                                        .fillMaxWidth((0.35 + if (largest > 0) 0.65 * abs(value) / largest else 0.0).toFloat())
                                        .height(2.dp).background(tone(value).copy(alpha = 0.65f)))
                                }
                            }
                        }
                    }
                }
            }
            Text("Tap a month for details · — means unavailable", color = Muted, fontSize = 11.sp)
        }
    }
    selectedMonth?.let { month -> AlertDialog(onDismissRequest = { selectedMonth = null },
        title = { Text("${java.time.Month.of(month.month).name.lowercase().replaceFirstChar { it.uppercase() }} ${month.year}") },
        text = { Text(if (month.returnPct != null) "Return: ${percent(month.returnPct)}" else if (month.pnl != null) "Logged P&L: ${money(month.pnl, true)}" else "No monthly result is available for this month in the selected period and source.") },
        confirmButton = { TextButton(onClick = { selectedMonth = null }) { Text("Close") } }) }
}

@Composable
internal fun PortfolioOverviewCard(state: DashboardState) {
    val snapshot = state.snapshot
    val ticker = state.btcTicker
    val priceFresh = state.connectionHealthy && btcTickerIsFresh(ticker, state.now)
    val direction = state.liveBtcPriceDirection
    val dotColor = when (direction) {
        BtcPriceDirection.Up -> Green
        BtcPriceDirection.Down -> Red
        BtcPriceDirection.Unknown -> Muted
    }
    val dotDescription = when (direction) {
        BtcPriceDirection.Up -> "BTC price rising"
        BtcPriceDirection.Down -> "BTC price falling"
        BtcPriceDirection.Unknown -> "BTC price movement unavailable"
    }
    val balance = state.balances
    val instance = balance?.instance ?: state.active?.label ?: snapshot?.instance ?: "Instance"
    val pnl = snapshot?.metrics?.unrealizedPnl
    val surface = CardSurface
    val topGradient = Brush.linearGradient(listOf(Blue.copy(alpha = 0.16f).compositeOver(surface), surface))
    val pnlGradient = Brush.linearGradient(listOf(Green.copy(alpha = 0.13f).compositeOver(surface), surface))
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    Surface(shape = HomeCardShape, modifier = Modifier.fillMaxWidth(), color = surface) {
        BoxWithConstraints {
            // Fit complete currency values into equal columns, including system font scaling.
            val columnWidth = with(density) { ((maxWidth - 36.dp - 25.dp) / 2f - 13.dp).toPx() }
            val widest = listOf(money(ticker?.price), money(balance?.equity)).maxOf {
                measurer.measure(it, Numeric.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), softWrap = false).size.width
            }.coerceAtLeast(1)
            val numberSize = (28f * (columnWidth / widest).coerceIn(0.1f, 1f)).sp
            Column {
                Row(Modifier.fillMaxWidth().background(topGradient).padding(18.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        OverviewLabel("BTC / USDT", Icons.Outlined.CurrencyBitcoin)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(8.dp).background(dotColor, CircleShape).semantics { contentDescription = dotDescription })
                            Text(money(ticker?.price), style = Numeric, fontSize = numberSize,
                                fontWeight = FontWeight.Bold, color = if (priceFresh) Ink else Muted)
                        }
                        Text("(${percent(ticker?.change24hPct)}) · 24h", style = Numeric, fontSize = (numberSize.value / 2).sp,
                            color = if (priceFresh) tone(ticker?.change24hPct) else Muted)
                        Text(if (priceFresh) "Binance futures" else if (ticker != null) "Last price" else "Price unavailable",
                            color = Muted, fontSize = 10.sp)
                    }
                    VerticalDivider(Modifier.fillMaxHeight(), color = Blue.copy(alpha = 0.2f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        OverviewLabel("$instance total equity", Icons.Outlined.AccountBalanceWallet)
                        Text(money(balance?.equity), style = Numeric, fontSize = numberSize, fontWeight = FontWeight.Bold,
                            color = if (state.balancesStale) Muted else Ink)
                        if (balance?.pnl != null && balance.returnPct != null) {
                            Text("${money(balance.pnl, true)} (${percent(balance.returnPct)})", style = Numeric,
                                fontSize = 11.sp, color = if (state.balancesStale) Muted else tone(balance.pnl))
                        }
                        Text(when {
                            state.balancesStale && balance?.equity != null -> "Stale balance"
                            state.balancesLoading -> "Updating balance…"
                            balance?.equity == null -> "Balance unavailable"
                            else -> "On original equity"
                        }, color = Muted, fontSize = 10.sp)
                    }
                }
                HorizontalDivider(color = Blue.copy(alpha = 0.12f))
                Column(Modifier.fillMaxWidth().background(pnlGradient).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OverviewLabel("Combined open P&L", Icons.Outlined.ShowChart)
                    Text(money(pnl, true, snapshot?.currency ?: "USD"), style = Numeric, fontSize = numberSize,
                        fontWeight = FontWeight.Bold, color = tone(pnl))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val returns = listOf("On invested USD" to snapshot?.openPerformance?.investedReturnPct,
                            "On all-wallet capital" to snapshot?.openPerformance?.entryEquityReturnPct)
                        returns.forEachIndexed { index, (label, value) ->
                            if (index > 0) VerticalDivider(Modifier.height(42.dp), color = Line.copy(alpha = 0.6f))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(percent(value), style = Numeric, fontSize = (numberSize.value / 2).sp,
                                    fontWeight = FontWeight.SemiBold, color = tone(value))
                                Text(label, color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewLabel(label: String, icon: ImageVector) {
    Row(Modifier.heightIn(min = 36.dp * LocalDensity.current.fontScale), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(16.dp))
        Text(label, color = Ink, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HomeHeading(title: String, icon: ImageVector, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = Blue, modifier = Modifier.size(19.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ModelTag(model: String) {
    Box(Modifier.size(36.dp).background(Blue.copy(alpha = 0.08f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center) {
        Text(model, color = Blue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun DirectionBadge(side: String) {
    val color = if (side == "LONG") Green else Red
    Row(Modifier.background(color.copy(alpha = 0.08f), RoundedCornerShape(50))
        .padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (side == "LONG") Icons.Outlined.TrendingUp else Icons.Outlined.TrendingDown,
            null, Modifier.size(13.dp), tint = color)
        Text(side, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun PositionsCard(snapshot: DashboardSnapshot) {
    var detail by remember { mutableStateOf<Position?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HomeHeading(homeSections[1], Icons.Outlined.SwapVert) {
            Badge("${snapshot.positions.size} open", if (snapshot.positions.isEmpty()) Muted else Blue)
        }
        models.keys.filter { it != "all" }.forEach { model ->
            val positions = snapshot.positions.filter { it.accountId == model }
            Surface(shape = HomeCardShape, color = CardSurface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (positions.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ModelTag(model)
                            Text("No active trade", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Badge("Flat", Muted)
                        }
                    }
                    positions.forEachIndexed { index, position ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Column(Modifier.fillMaxWidth().clickable { detail = position }
                            .semantics { contentDescription = "View $model ${position.side} position details" },
                          verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(38.dp).background(Blue.copy(alpha = 0.08f), RoundedCornerShape(11.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.BarChart, null, tint = Blue, modifier = Modifier.size(19.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(model, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    if (position.symbol != "BTCUSDT") Text(position.symbol, color = Muted, fontSize = 11.sp)
                                }
                                DirectionBadge(position.side)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Combined size · USD", color = Muted, fontSize = 11.sp)
                                    Text(money(positionNotionalUsd(position)), style = Numeric, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Open P&L", color = Muted, fontSize = 11.sp)
                                    Text(money(position.unrealizedPnl, true, snapshot.currency), style = Numeric,
                                        fontSize = 22.sp, fontWeight = FontWeight.Bold, color = tone(position.unrealizedPnl),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                                }
                            }
                            HorizontalDivider(color = Line)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PriceCell("Entry (USDT)", position.entryPrice, Modifier.weight(1f))
                                val midpoint = positionStopMidpoint(position)
                                val combined = position.stopPrices.size > 1 && midpoint != null
                                PriceCell(if (combined) "Stop loss (midpoint)" else if (position.stopPrices.size > 1) "Stop loss prices" else "Stop loss price",
                                    midpoint ?: position.stopPrice, Modifier.weight(1f), right = true,
                                    values = if (midpoint == null) position.stopPrices else emptyList())
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("Position details", color = Blue, fontSize = 12.sp)
                                Icon(Icons.Outlined.ChevronRight, null, tint = Blue, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { position -> AlertDialog(onDismissRequest = { detail = null },
        title = { Text("${position.accountId} · ${position.symbol} · ${position.side}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            KeyValue("Combined size (${position.symbol.removeSuffix("USDT")})", number(position.size, 4))
            KeyValue("Combined size (USD)", money(positionNotionalUsd(position)))
            KeyValue("Size-weighted entry", number(position.entryPrice))
            KeyValue("Binance mark", number(position.markPrice))
            KeyValue("Open P&L", money(position.unrealizedPnl, true, snapshot.currency))
            KeyValue("Saved leverage (weighted)", "${number(position.leverage, 2)}×")
            KeyValue("Saved stop prices", if (position.stopPrices.isEmpty()) number(position.stopPrice)
                else position.stopPrices.joinToString(" / ") { number(it) })
            Text("Saved bot state, not an exchange order check. Home shows the midpoint when wallet stops differ by at most 0.1%; exact stops are listed here. Larger differences stay separate. If any saved stop is unavailable, the combined stop remains —.", color = Muted, fontSize = 11.sp)
        } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("Close") } }) }
}

@Composable
private fun PriceCell(label: String, value: Double?, modifier: Modifier, right: Boolean = false, values: List<Double> = emptyList()) {
    Column(modifier, horizontalAlignment = if (right) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(if (values.isEmpty()) number(value) else values.joinToString("\n") { number(it) },
            style = Numeric, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            textAlign = if (right) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start)
    }
}

@Composable
internal fun BalancesCard(state: DashboardState) {
    val value = state.balances
    val showWallets = value?.models.orEmpty().flatMap { it.walletAllocations }.map { it.wallet }.distinct().size > 1
    Surface(shape = HomeCardShape, color = CardSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HomeHeading(homeSections[2], Icons.Outlined.AccountBalanceWallet) {
                when {
                    state.balancesLoading -> Badge("Updating", Blue)
                    state.balancesStale && value != null -> Badge("Stale", Red)
                    value?.status == "ok" -> Badge("Equity", Blue)
                    else -> Badge("Unavailable", Muted)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${value?.instance ?: state.active?.label ?: "Instance"} total", color = Muted, fontSize = 12.sp)
                Text(money(value?.equity), style = Numeric, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                    color = if (state.balancesStale) Muted else Ink)
                Text(value?.pnl?.let { "${money(it, true).replace("$", "")} USDT (${percent(value?.returnPct)})" } ?: "—",
                    style = Numeric, color = if (state.balancesStale) Muted else tone(value?.pnl), fontSize = 14.sp)
                Text("P&L on original equity", color = Muted, fontSize = 11.sp)
            }
            state.balanceError?.let { Text(it, color = Red, fontSize = 12.sp) }
            if (value != null && value.status != "ok") Text("Full balance unavailable. Refresh to retry.", color = Red, fontSize = 12.sp)
            models.keys.filter { it != "all" }.forEach { model ->
                val sample = value?.models?.find { it.id == model }
                HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(model, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (sample?.status != "ok") Text(if (sample?.status == "not_configured") "No account configured" else "Unavailable",
                            color = Muted, fontSize = 10.sp)
                        else if (state.balancesStale) Text("Stale", color = Red, fontSize = 10.sp)
                    }
                    Text(buildAnnotatedString {
                        append(money(sample?.equity))
                        val allocation = walletAllocationLabel(sample, showWallets)
                        if (allocation.isNotEmpty()) withStyle(SpanStyle(color = Muted, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium)) { append(" $allocation") }
                    }, modifier = Modifier.weight(5f), style = Numeric, fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        color = if (state.balancesStale) Muted else Ink)
                }
            }
        }
    }
}

@Composable
private fun TradesCard(trades: List<Trade>, snapshot: DashboardSnapshot, title: String, onViewAll: () -> Unit, onTrade: (Trade) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onViewAll) { Text("View all", fontSize = 12.sp) }
        }
        if (trades.isEmpty()) EmptyState(if (snapshot.metrics.closedTrades == null)
            "Trade history is not available yet." else "No closed trades in this period.")
        trades.sortedByDescending { it.closedAt }.forEachIndexed { index, trade ->
            if (index > 0) HorizontalDivider(color = Backdrop)
            TradeRow(trade, snapshot) { onTrade(trade) }
        }
    }
}

@Composable
private fun TradeRow(trade: Trade, snapshot: DashboardSnapshot, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(trade.strategy, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Badge(trade.side, if (trade.side == "LONG") Green else Red)
            }
            Text(date(trade.closedAt), color = Muted, fontSize = 11.sp)
            if (snapshot.historyMode == "edge") Text(if (trade.source == "backtest") "Backtest" else "Live",
                color = if (trade.source == "backtest") Blue else Green, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(money(trade.netPnl, true, snapshot.currency), fontWeight = FontWeight.SemiBold, color = tone(trade.netPnl), fontSize = 14.sp)
            Text("Closed trade", color = Muted, fontSize = 11.sp)
        }
        Icon(Icons.Outlined.ChevronRight, "View trade details", Modifier.size(20.dp), tint = Muted)
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun AppearanceCard(appearance: Appearance, onAppearance: (Appearance) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.DarkMode, null, tint = Blue)
            Text("Appearance", fontWeight = FontWeight.Bold, fontSize = 17.sp)
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Appearance.entries.forEachIndexed { index, option ->
                SegmentedButton(selected = appearance == option, onClick = { onAppearance(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, Appearance.entries.size)) {
                    Text(if (option == Appearance.System) "System" else option.label, fontSize = 12.sp)
                }
            }
        }
        Text("Saved on this phone and applied to every screen.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SettingsScreen(state: DashboardState, vm: DashboardViewModel, appLockEnabled: Boolean,
                           appLockError: String?, onAppLockChange: (Boolean) -> Unit,
                           updateState: AppUpdateState, onUpdateCheck: () -> Unit, onUpdateInstall: () -> Unit,
                           alertsEnabled: Boolean, onAlertsChange: (Boolean) -> Unit, alertStatus: List<Pair<String, String>>, onAlertHistory: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf<Connection?>(null) }
    LaunchedEffect(state.showConnectionForm) { if (!state.showConnectionForm) key = "" }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionHeading("Settings", "Preferences for this phone") }
        item { AppearanceCard(state.appearance, vm::appearance) }
        item { Panel { AlertsCard(alertsEnabled, onAlertsChange, alertStatus, onAlertHistory) } }
        item { Panel { AppUpdateCard(updateState, onUpdateCheck, onUpdateInstall) } }
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fingerprint unlock", fontWeight = FontWeight.Bold)
                    Text("Use fingerprint or Android screen lock when opening the app.", color = Muted, fontSize = 12.sp)
                }
                Switch(checked = appLockEnabled, onCheckedChange = onAppLockChange,
                    modifier = Modifier.semantics { contentDescription = "Fingerprint unlock" })
            }
            Text(if (appLockEnabled) "Locks after closing. Backgrounding and changing tabs keep this session unlocked."
                else "Disabled. The app opens without authentication.", color = Muted, fontSize = 12.sp)
            if (appLockError != null) Text(appLockError, color = Red, fontSize = 12.sp)
        } }
        item { SectionHeading("Connections", "One dashboard key for each instance") }
        items(state.connections, key = { it.id }) { connection ->
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connection.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.select(connection.id); vm.page(Page.Dashboard) }, enabled = !state.connecting) { Text("Open Home") }
                    IconButton(onClick = { forget = connection }, enabled = !state.connecting) {
                        Icon(Icons.Outlined.DeleteOutline, "Forget ${connection.label}", tint = Muted)
                    }
                }
            }
        }
        if (!state.showConnectionForm) item {
            Button(onClick = vm::addKey, modifier = Modifier.fillMaxWidth()) { Text("Add another key") }
        }
        if (state.showConnectionForm) item { Panel {
            Text(if (state.connections.isEmpty()) "Connect your dashboard" else "Add another key", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(key, { key = it }, label = { Text("Dashboard connection key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(), enabled = !state.connecting)
            Text("Enter the dashboard key provided for your instance.", color = Muted, fontSize = 12.sp)
            Button(onClick = { vm.connect(key) }, enabled = !state.connecting && key.isNotBlank(),
                modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                if (state.connecting) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (state.connecting) "Connecting…" else "Connect securely")
            }
            if (state.connections.isNotEmpty()) TextButton(onClick = vm::cancelAddKey, enabled = !state.connecting) { Text("Cancel") }
        } }
        item { Panel {
            Text("About", fontWeight = FontWeight.Bold)
            KeyValue("Version", BuildConfig.VERSION_NAME)
            KeyValue("Access", "Read only")
            ExpandableHelp("About saved keys") { Text("Keys are stored encrypted on this phone. Forgetting an instance removes its saved key from this phone; it does not revoke the key on the server.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp) }
        } }
    }
    forget?.let { connection -> AlertDialog(onDismissRequest = { forget = null }, title = { Text("Forget ${connection.label}?") },
        text = { Text("The saved connection and access key will be removed from this phone.") },
        confirmButton = { TextButton(onClick = { vm.forget(connection.id); forget = null }) { Text("Forget", color = Red) } },
        dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } }) }
}

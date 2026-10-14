package com.shadowpheonix.tradingdashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ExpandableHelp(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(title, fontSize = 12.sp)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp))
        }
        if (expanded) content()
    }
}

@Composable
fun AlertsCard(enabled: Boolean, onChange: (Boolean) -> Unit, status: List<Pair<String, String>>, onHistory: () -> Unit) {
    val context = LocalContext.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bot alerts", style = MaterialTheme.typography.titleMedium)
                Text("Trades, risk and bot reports", fontSize = 12.sp, color = muted)
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
        status.forEach { (instance, message) -> Text("$instance · $message", fontSize = 12.sp, color = muted) }
        FilledTonalButton(onClick = onHistory, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Notifications, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open alerts")
        }
        ExpandableHelp("Notification settings & delivery") {
            Text("Allow notifications, floating alerts and autostart in your phone settings. Offline retries pause automatically. Android Force stop pauses delivery until you open the app again.", fontSize = 12.sp, color = muted)
            TextButton(onClick = { openAlertNotificationSettings(context) }) { Text("Floating notifications") }
            TextButton(onClick = { openAlertNotificationSettings(context, connectionIndicator = true) }) { Text("Hide connection indicator") }
            Text("To hide the ongoing indicator, turn off only Background alert connection in the next screen. Keep trade, risk and report channels enabled.", fontSize = 12.sp, color = muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertInboxPage(connections: List<Connection>, history: Map<String, List<DashboardAlert>>, target: AlertTarget, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var selected by rememberSaveable(target) { mutableStateOf(target.connectionId?.takeIf { id -> connections.any { it.id == id } }) }
    val events = remember(connections, history, selected) { inboxAlerts(connections, history, selected) }
    val list = rememberLazyListState()
    var focused by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target, events) {
        if (!focused && target.eventId != null) {
            val index = events.indexOfFirst { it.event.id == target.eventId && it.connection.id == target.connectionId }
            if (index >= 0) { list.scrollToItem(index + 1); focused = true }
        }
    }
    val colors = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    Scaffold(containerColor = colors.background, topBar = {
        TopAppBar(title = { Column { Text("Alerts"); Text("Actual bot events", fontSize = 12.sp, color = colors.onSurfaceVariant) } },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background))
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = list, contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "filters") {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected == null, { selected = null; focused = true }, label = { Text("All instances") }) }
                    connections.forEach { connection -> item(key = connection.id) { FilterChip(selected == connection.id,
                        { selected = connection.id; focused = true }, label = { Text(connection.label) }) } }
                }
            }
            if (events.isEmpty()) item { Text("No recorded alerts yet.", color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 32.dp)) }
            events.forEachIndexed { index, row -> item(key = row.key) {
                val day = Instant.ofEpochMilli(row.event.timestamp).atZone(zone).toLocalDate()
                val previous = events.getOrNull(index - 1)?.event?.timestamp?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (day != previous) Text(day.format(DateTimeFormatter.ofPattern("EEEE, d MMM")), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    AlertEventCard(row)
                }
            } }
        }
    }
}

@Composable
private fun AlertEventCard(row: InboxAlert) {
    val colors = MaterialTheme.colorScheme
    val event = row.event
    val presentation = remember(row) { presentAlert(row.connection.label, event) }
    val accent = if (presentation.urgent) colors.error else if (presentation.category == AlertCategory.Trade) colors.secondary else colors.primary
    var full by rememberSaveable(row.key) { mutableStateOf(false) }
    val title = if (event.message.startsWith("TEST NOTIFICATION")) "Test notification" else presentation.title.removePrefix("${row.connection.label.take(40)} · ")
    Surface(shape = RoundedCornerShape(22.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (presentation.urgent) Icons.Outlined.WarningAmber else if (presentation.category == AlertCategory.Trade) Icons.Outlined.SwapVert else Icons.Outlined.Notifications,
                    null, tint = accent, modifier = Modifier.size(22.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${row.connection.label} · ${presentation.category.name}", color = accent, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(event.timestamp)), color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
            alertDetails(event).forEach { detail ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    detail.label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                    Text(detail.value, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
            TextButton(onClick = { full = !full }, contentPadding = PaddingValues(0.dp)) { Text(if (full) "Hide full message" else "Full message", fontSize = 12.sp) }
            if (full) SelectionContainer { Text(event.message, fontSize = 12.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant) }
        }
    }
}

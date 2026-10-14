package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AlertTarget(val connectionId: String? = null, val eventId: String? = null)
class AlertInboxNavigation {
    private val mutable = MutableStateFlow<AlertTarget?>(null)
    val route = mutable.asStateFlow()
    fun open(target: AlertTarget = AlertTarget()) { mutable.value = target }
    fun back() { mutable.value = null }
}
data class InboxAlert(val connection: Connection, val event: DashboardAlert) {
    val key get() = "${connection.id}:${event.id}"
}
fun inboxAlerts(connections: List<Connection>, history: Map<String, List<DashboardAlert>>, selected: String? = null): List<InboxAlert> =
    connections.filter { selected == null || it.id == selected }.flatMap { connection ->
        history[connection.id].orEmpty().filterNot(::isRoutineEvaluation).map { InboxAlert(connection, it) }
    }.sortedWith(compareByDescending<InboxAlert> { it.event.timestamp }.thenBy { it.key })

data class AlertDetail(val label: String?, val value: String)
fun alertDetails(event: DashboardAlert): List<AlertDetail> {
    val presentation = presentAlert("", event)
    val lines = presentation.details.lines().filter { it.isNotBlank() }
    val test = lines.firstOrNull()?.startsWith("TEST NOTIFICATION") == true
    return lines.filterIndexed { index, line ->
        !(test && (index == 0 || line.startsWith("Sent at:") || line.startsWith("Test ID:"))) &&
            !(index == 0 && presentation.category == AlertCategory.Status &&
                line.replace(Regex("^\\[[^]]+]\\s*"), "") == presentation.title.removePrefix(" · "))
    }.map { line ->
        val text = line.removePrefix("• ")
        val label = text.substringBefore(':', "")
        if (!text.contains("://") && label.matches(Regex("[A-Za-z][A-Za-z0-9 /()_%+.-]{0,47}")) && text.substringAfter(':').isNotBlank())
            AlertDetail(label, text.substringAfter(':').trim()) else AlertDetail(null, text)
    }
}

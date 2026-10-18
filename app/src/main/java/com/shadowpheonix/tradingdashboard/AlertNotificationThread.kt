package com.shadowpheonix.tradingdashboard

// IDs come from the card still present in Android's shade, not all saved history.
// A dismissed card starts fresh. Bound metadata and rendered text independently.
const val MAX_PENDING_NOTIFICATION_EVENTS = 100
const val MAX_DISPLAYED_NOTIFICATION_EVENTS = 6

data class AlertNotificationThread(val events: List<DashboardAlert>, val isNew: Boolean) {
    val displayed get() = events.takeLast(MAX_DISPLAYED_NOTIFICATION_EVENTS)
}

// Android posts asynchronously. Retain a submitted card until a shade snapshot
// acknowledges its event IDs; an older/empty snapshot must not lose a burst.
// Once acknowledged, the OS owns dismissal state. Access is under the poster lock.
class PendingNotificationCards<T>(private val ids: (T) -> List<String>) {
    private val pending = mutableMapOf<String, T>()
    fun posted(tag: String, card: T) { pending[tag] = card }
    fun remove(tag: String) { pending.remove(tag) }
    fun snapshot(observed: Map<String, T>): Map<String, T> {
        pending.entries.removeAll { (tag, card) -> observed[tag]?.let { ids(it) == ids(card) } == true }
        return observed + pending
    }
}

fun notificationThread(history: List<DashboardAlert>, pendingIds: List<String>, incoming: DashboardAlert): AlertNotificationThread {
    incoming.validate()
    val available = (history + incoming).associateBy { it.id }
    val pending = pendingIds.distinct().mapNotNull { available[it] }.filterNot(::isRoutineEvaluation)
    val isNew = pending.none { it.id == incoming.id }
    val events = if (isNew && !isRoutineEvaluation(incoming)) pending + incoming else pending
    return AlertNotificationThread(events.takeLast(MAX_PENDING_NOTIFICATION_EVENTS), isNew)
}

fun notificationTopic(value: AlertPresentation): String = when {
    value.urgent -> "Urgent risk"
    value.category == AlertCategory.Trade -> "Trades"
    value.category == AlertCategory.Risk -> "Risk updates"
    else -> "Bot reports"
}

fun notificationCount(count: Int): String = if (count == 1) "1 new alert"
    else if (count >= MAX_PENDING_NOTIFICATION_EVENTS) "$count+ recent alerts" else "$count new alerts"

// Only style plain text. Bot messages cannot supply HTML, links or actions.
fun notificationBoldRanges(text: String): List<IntRange> {
    val header = text.indexOf('\n').takeIf { it >= 0 } ?: text.length
    val labels = Regex("(?m)^(?:•\\s*)?[A-Za-z][A-Za-z0-9 /()_%+.-]{0,47}:(?!//)").findAll(text)
        .map { it.range }.toList()
    return (if (header > 0) listOf(0 until header) else emptyList()) + labels
}

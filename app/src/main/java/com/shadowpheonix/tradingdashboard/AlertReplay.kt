package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable

@Serializable
data class AlertSource(val id: String, val label: String)
@Serializable
data class AlertEvent(val sequence: Long, val id: String, val timestamp: Long, val message: String) {
    fun alert() = DashboardAlert(id, timestamp, message).validate()
}
@Serializable
data class AlertBatch(val schemaVersion: Int, val instance: AlertSource, val status: String,
                      val events: List<AlertEvent>, val cursor: Long, val oldest: Long,
                      val newest: Long, val gap: Boolean, val hasMore: Boolean, val openPnl: AlertPnlSample? = null) {
    fun validate(): AlertBatch {
        require(schemaVersion == 1 && status in setOf("ready", "unavailable"))
        require(instance.id.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
        require(instance.label.length in 1..240 && instance.label.none { it.code < 32 })
        require(cursor >= 0 && oldest >= 0 && newest >= oldest && events.size <= 100)
        require(events.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        require(events.map { it.id }.distinct().size == events.size)
        events.forEach { require(it.sequence in oldest..newest); it.alert() }
        if (status == "ready") {
            require(cursor <= newest && (events.isEmpty() || cursor == events.last().sequence))
            require(hasMore == (cursor < newest))
        } else require(events.isEmpty() && !hasMore && !gap)
        openPnl?.validate()
        return this
    }
}

@Serializable
data class AlertLedger(val sourceId: String = "", val cursor: Long = 0,
                       val history: List<DashboardAlert> = emptyList(), val gap: Boolean = false,
                       val pnl: PnlMilestoneState? = null,
                       val pendingNotifications: List<DashboardAlert> = emptyList())
data class AlertDelivery(val ledger: AlertLedger, val notifications: List<DashboardAlert>)

/** Same-band quotes need no rewrite of the entire private alert history. */
internal fun alertCheckpointChanged(before: AlertLedger, after: AlertLedger): Boolean =
    before.sourceId != after.sourceId || before.cursor != after.cursor || before.history != after.history ||
        before.gap != after.gap || before.pendingNotifications != after.pendingNotifications ||
        before.pnl?.positionsKey != after.pnl?.positionsKey || before.pnl?.sequence != after.pnl?.sequence ||
        before.pnl?.recentLevels != after.pnl?.recentLevels ||
        before.pnl?.value?.div(1000)?.toLong() != after.pnl?.value?.div(1000)?.toLong()


fun replayAlerts(saved: AlertLedger, batch: AlertBatch, now: Long = System.currentTimeMillis()): AlertDelivery {
    val delivery = replayCurrentAlerts(saved.copy(pendingNotifications = emptyList()), batch, now)
    return delivery.copy(notifications = (saved.pendingNotifications + delivery.notifications).distinctBy { it.id })
}

private fun replayCurrentAlerts(saved: AlertLedger, batch: AlertBatch, now: Long): AlertDelivery {
    batch.validate()
    require(saved.sourceId.isEmpty() || saved.sourceId == batch.instance.id)
    if (batch.status != "ready") return withPnlMilestones(AlertDelivery(saved, emptyList()), batch.openPnl, now)
    val bootstrap = saved.sourceId.isEmpty()
    val seen = AlertDeduplicator().apply { restore(saved.history.map { it.id }) }
    val fresh = batch.events.filter { (it.sequence > saved.cursor || batch.gap || bootstrap) && seen.accept(it.id) }
        .map { it.alert() }.filterNot(::isRoutineEvaluation)
    // Advance past suppressed diagnostics too, so replay never gets stuck on a quiet batch.
    val ledger = AlertLedger(batch.instance.id, batch.cursor,
        (saved.history.filterNot(::isRoutineEvaluation) + fresh).takeLast(1000), saved.gap || batch.gap, saved.pnl)
    // Initial history is visible in the inbox but does not produce a notification storm.
    return withPnlMilestones(AlertDelivery(ledger, if (bootstrap) emptyList() else fresh), batch.openPnl, now)
}

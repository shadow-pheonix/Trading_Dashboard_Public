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
                      val newest: Long, val gap: Boolean, val hasMore: Boolean) {
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
        return this
    }
}

@Serializable
data class AlertLedger(val sourceId: String = "", val cursor: Long = 0,
                       val history: List<DashboardAlert> = emptyList(), val gap: Boolean = false)
data class AlertDelivery(val ledger: AlertLedger, val notifications: List<DashboardAlert>)

fun replayAlerts(saved: AlertLedger, batch: AlertBatch): AlertDelivery {
    batch.validate()
    if (batch.status != "ready") return AlertDelivery(saved, emptyList())
    require(saved.sourceId.isEmpty() || saved.sourceId == batch.instance.id)
    val bootstrap = saved.sourceId.isEmpty()
    val seen = AlertDeduplicator().apply { restore(saved.history.map { it.id }) }
    val fresh = batch.events.filter { (it.sequence > saved.cursor || batch.gap || bootstrap) && seen.accept(it.id) }
        .map { it.alert() }.filterNot(::isRoutineEvaluation)
    // Advance past suppressed diagnostics too, so replay never gets stuck on a quiet batch.
    val ledger = AlertLedger(batch.instance.id, batch.cursor,
        (saved.history.filterNot(::isRoutineEvaluation) + fresh).takeLast(1000), saved.gap || batch.gap)
    // Initial history is visible in the inbox but does not produce a notification storm.
    return AlertDelivery(ledger, if (bootstrap) emptyList() else fresh)
}

package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

@Serializable
data class AlertPnlSample(val positionsKey: String, val value: Double, val activeCount: Int,
                          val sampledAt: Long, val priceUpdatedAt: Long? = null) {
    fun validate() {
        require(positionsKey.matches(Regex("[a-f0-9]{64}")) && value.isFinite() && abs(value) <= 1e20)
        require(activeCount in 0..6 && sampledAt > 0)
        if (activeCount == 0) require(value == 0.0 && priceUpdatedAt == null)
        else require(priceUpdatedAt != null && priceUpdatedAt > 0 && priceUpdatedAt <= sampledAt && sampledAt-priceUpdatedAt <= 15_000)
    }
}

internal const val PNL_MILESTONE_COOLDOWN_MS = 15 * 60_000L

@Serializable
data class PnlCooldownRange(val first: Long, val last: Long, val notifiedAt: Long)

@Serializable
data class PnlMilestoneState(val positionsKey: String, val value: Double, val sampledAt: Long,
                             val sequence: Long = 0, val recentLevels: List<PnlCooldownRange> = emptyList())

internal data class PnlCrossing(val ranges: List<LongRange>, val rising: Boolean) {
    val count get() = ranges.sumOf { it.last-it.first+1 }
    val last get() = (if (rising) ranges.last().last else ranges.first().first).toDouble() * 1000
}

/** Signed $1,000 bands exclude zero; recovery/re-crossings count as well. */
internal fun pnlCrossing(previous: Double, current: Double): PnlCrossing? {
    val before = (previous / 1000).toLong()
    val after = (current / 1000).toLong()
    if (before == after) return null
    // Keep ranges of signed level numbers, excluding zero. A huge jump needs
    // at most two ranges rather than allocating one object per $1,000 level.
    val negative = minOf(before,after,0L)..(maxOf(minOf(before,0L),minOf(after,0L))-1)
    val positive = (minOf(maxOf(before,0L),maxOf(after,0L))+1)..maxOf(before,after,0L)
    return PnlCrossing(listOf(negative,positive).filterNot { it.isEmpty() }, after > before)
}

/** Subtract levels still cooling down; other new levels remain eligible. */
private fun PnlCrossing.withoutCoolingLevels(recent: List<PnlCooldownRange>): PnlCrossing? {
    val occupied = recent.sortedBy { it.first }
    val eligible = ranges.flatMap { range ->
        val pieces = mutableListOf<LongRange>()
        var first = range.first
        for (previous in occupied) {
            if (previous.last < first) continue
            if (previous.first > range.last) break
            if (previous.first > first) pieces.add(first..minOf(range.last,previous.first-1))
            first = maxOf(first,previous.last+1)
            if (first > range.last) break
        }
        if (first <= range.last) pieces.add(first..range.last)
        pieces
    }
    return if (eligible.isEmpty()) null else copy(ranges = eligible)
}

private fun pnlMoney(value: Double) = String.format(Locale.US, "%s$%,.2f", if (value < 0) "−" else "+", abs(value))

/** Persist baseline and local event with the bot cursor in one ledger commit. */
internal fun withPnlMilestones(delivery: AlertDelivery, sample: AlertPnlSample?, now: Long): AlertDelivery {
    if (sample == null || now < sample.sampledAt || now-sample.sampledAt > 30_000) return delivery
    val saved = delivery.ledger.pnl
    if (saved != null && sample.sampledAt <= saved.sampledAt) return delivery
    val continuing = saved != null && sample.activeCount > 0 && saved.positionsKey == sample.positionsKey
    val recent = if (continuing) saved!!.recentLevels.filter {
        it.notifiedAt <= sample.sampledAt && sample.sampledAt-it.notifiedAt < PNL_MILESTONE_COOLDOWN_MS
    } else emptyList()
    val crossing = if (continuing) pnlCrossing(saved!!.value, sample.value)?.withoutCoolingLevels(recent) else null
    val sequence = (saved?.sequence ?: 0) + if (crossing != null) 1 else 0
    val state = PnlMilestoneState(sample.positionsKey, sample.value, sample.sampledAt, sequence,
        recent + crossing?.ranges.orEmpty().map { PnlCooldownRange(it.first,it.last,sample.sampledAt) })
    val event = crossing?.let {
        val level = String.format(Locale.US, "%s$%,.0f", if (it.last < 0) "−" else "+", abs(it.last))
        DashboardAlert("pnl_${sample.positionsKey.take(16)}_$sequence", sample.sampledAt,
            "${if (sample.value >= 0) "📈" else "📉"} Combined open P&L\n" +
                "Current P&L: ${pnlMoney(sample.value)}\n" +
                "Crossed: $level ${if (it.rising) "↑" else "↓"}" +
                (if (it.count > 1) " (${it.count} levels)" else "") +
                "\nScope: All models · All wallets").validate()
    }
    return AlertDelivery(delivery.ledger.copy(pnl = state,
        history = (delivery.ledger.history + listOfNotNull(event)).takeLast(1000)),
        delivery.notifications + listOfNotNull(event))
}

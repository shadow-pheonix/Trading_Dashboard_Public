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

@Serializable
data class PnlMilestoneState(val positionsKey: String, val value: Double, val sampledAt: Long,
                             val sequence: Long = 0)

internal data class PnlCrossing(val last: Double, val count: Long, val rising: Boolean)

/** Signed $1,000 bands exclude zero; recovery/re-crossings count as well. */
internal fun pnlCrossing(previous: Double, current: Double): PnlCrossing? {
    val before = (previous / 1000).toLong()
    val after = (current / 1000).toLong()
    if (before == after) return null
    val rising = after > before
    val last = if (rising) { if (after > 0) after else after-1 }
               else { if (after < 0) after else after+1 }
    return PnlCrossing(last.toDouble() * 1000, abs(after-before), rising)
}

private fun pnlMoney(value: Double) = String.format(Locale.US, "%s$%,.2f", if (value < 0) "−" else "+", abs(value))

/** Persist baseline and local event with the bot cursor in one ledger commit. */
internal fun withPnlMilestones(delivery: AlertDelivery, sample: AlertPnlSample?, now: Long): AlertDelivery {
    if (sample == null || now < sample.sampledAt || now-sample.sampledAt > 30_000) return delivery
    val saved = delivery.ledger.pnl
    if (saved != null && sample.sampledAt <= saved.sampledAt) return delivery
    val crossing = if (saved != null && sample.activeCount > 0 && saved.positionsKey == sample.positionsKey)
        pnlCrossing(saved.value, sample.value) else null
    val sequence = (saved?.sequence ?: 0) + if (crossing != null) 1 else 0
    val state = PnlMilestoneState(sample.positionsKey, sample.value, sample.sampledAt, sequence)
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

package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable
import androidx.compose.ui.geometry.Rect

@Serializable
data class ChartTradeAnnotation(val id: String, val modelId: String, val wallet: String, val side: String,
    val enteredAt: Long, val entryPrice: Double, val stopPrice: Double? = null, val stopState: String = "unavailable")

fun validateTradeAnnotations(value: MacdSnapshot) {
    require(value.annotationStatus == null || value.annotationStatus in setOf("ok", "partial", "unavailable"))
    if (value.annotationStatus == null || value.annotationStatus == "unavailable") {
        require(value.tradeAnnotations.isEmpty() && value.annotationSampledAt == null)
        return
    }
    require(value.annotationSampledAt != null && value.annotationSampledAt in 1L..value.generatedAt &&
        value.tradeAnnotations.size <= 6 && value.tradeAnnotations.map { it.id }.distinct().size == value.tradeAnnotations.size)
    value.tradeAnnotations.forEach { trade ->
        require(trade.id.matches(Regex("[a-f0-9]{24}")) && trade.modelId in setOf("M1", "M2", "M3") &&
            trade.wallet in setOf("", "A", "B") && trade.side in setOf("LONG", "SHORT") &&
            trade.enteredAt in 1L..value.annotationSampledAt && trade.entryPrice.isFinite() && trade.entryPrice > 0 && trade.entryPrice <= 1e20)
        require(if (trade.stopPrice == null) trade.stopState == "unavailable" else
            trade.stopPrice.isFinite() && trade.stopPrice > 0 && trade.stopPrice <= 1e20 &&
                trade.stopState in setOf("confirmed", "saved", "pending"))
    }
}

internal fun MacdSnapshot.withoutTradeAnnotations() = copy(tradeAnnotations = emptyList(),
    annotationStatus = null, annotationSampledAt = null)

internal fun visibleTradeAnnotations(value: MacdSnapshot?, modelId: String, now: Long,
    cached: Boolean, refreshFailed: Boolean): List<ChartTradeAnnotation> {
    if (value == null || cached || refreshFailed || value.annotationStatus !in setOf("ok", "partial")) return emptyList()
    val sampled = value.annotationSampledAt ?: return emptyList()
    if (now < sampled || now - sampled > 90_000) return emptyList()
    return value.tradeAnnotations.filter { modelId == BTC_CHART_ID || it.modelId == modelId }
}

/** The opening time, not the signal or state-file modification time, selects the bucket. */
internal fun entryCandleIndex(trade: ChartTradeAnnotation, times: List<Long>): Int? {
    if (times.isEmpty()) return null
    val stamp = trade.enteredAt / MACD_INTERVAL * MACD_INTERVAL
    if (stamp > times.last()) return null // A forming entry candle is not in a closed-candle chart yet.
    return ((stamp - times.first()) / MACD_INTERVAL).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

internal fun tradePrice(value: Double) = chartNumber(value, if (value % 1.0 == 0.0) 0 else 2)

internal fun entryAnnotationLabel(trade: ChartTradeAnnotation) =
    listOf(trade.side, trade.wallet, tradePrice(trade.entryPrice)).filter { it.isNotEmpty() }.joinToString(" ")

internal data class EntryChartMarker(val index: Int, val side: String, val trades: List<ChartTradeAnnotation>)

internal fun entryChartMarkers(trades: List<ChartTradeAnnotation>, times: List<Long>): List<EntryChartMarker> =
    trades.mapNotNull { trade -> entryCandleIndex(trade, times)?.takeIf { it in times.indices }?.let { Triple(it, trade.side, trade.modelId) to trade } }
        .groupBy({ it.first }, { it.second }).map { (key, group) -> EntryChartMarker(key.first, key.second, group) }
        .sortedWith(compareBy({ it.index }, { it.side }, { it.trades.first().modelId }))

internal fun entryAnnotationLabel(marker: EntryChartMarker, includeModel: Boolean = false): String {
    if (includeModel) return marker.trades.first().modelId + " " + entryAnnotationLabel(marker)
    if (marker.trades.size == 1) return entryAnnotationLabel(marker.trades.first())
    val wallets = marker.trades.map { it.wallet }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("/")
    val price = marker.trades.map { it.entryPrice }.distinct().singleOrNull()?.let(::tradePrice) ?: "${marker.trades.size} entries"
    return listOf(marker.side, wallets, price).filter { it.isNotEmpty() }.joinToString(" ")
}

internal fun stopLineStart(index: Int, viewport: ChartViewport, width: Float): Float? {
    val x = ((index + 0.5 - viewport.first) / viewport.count * width).toFloat()
    return if (x >= width) null else x.coerceAtLeast(0f)
}

internal fun entryLabelRect(x: Float, y: Float, width: Float, height: Float, plotWidth: Float,
    low: Float, high: Float, occupied: List<Rect>): Rect {
    val candidates = listOf(0, 1, -1, 2, -2).flatMap { row ->
        listOf(x + 8f, x - width - 8f).map { left ->
            val xx = left.coerceIn(0f, (plotWidth - width).coerceAtLeast(0f))
            val yy = (y + row * (height + 4f)).coerceIn(low, (high - height).coerceAtLeast(low))
            Rect(xx, yy, xx + width, yy + height)
        }
    }
    return candidates.firstOrNull { candidate -> occupied.none { it.overlaps(candidate) } } ?: candidates.first()
}

internal fun stopAnnotationLabel(trades: List<ChartTradeAnnotation>, includeModel: Boolean = false): String {
    if (includeModel) {
        val owners = trades.groupBy { it.modelId }.toSortedMap().map { (model, group) ->
            val wallets = group.map { it.wallet }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("/")
            listOf(model, wallets).filter { it.isNotEmpty() }.joinToString(" ")
        }.joinToString(" · ")
        return "$owners ${if (trades.any { it.stopState == "pending" }) "SL?" else "SL"} ${tradePrice(trades.first().stopPrice!!)}"
    }
    val wallets = trades.map { it.wallet }.filter { it.isNotEmpty() }.distinct().sorted().joinToString("/")
    val pending = trades.any { it.stopState == "pending" }
    return listOf(if (pending) "SL?" else "SL", wallets, tradePrice(trades.first().stopPrice!!))
        .filter { it.isNotEmpty() }.joinToString(" ")
}

/** Keep price levels exact while spacing nearby axis badges, with short leader lines. */
internal fun priceLabelCenters(desired: List<Float>, height: Float, low: Float, high: Float): List<Float> {
    if (desired.isEmpty()) return emptyList()
    val half = height / 2
    val minimum = low + half
    val maximum = (high - half).coerceAtLeast(minimum)
    val spacing = if (desired.size == 1) 0f else minOf(height + 2f, (maximum - minimum) / (desired.size - 1))
    val order = desired.indices.sortedBy { desired[it] }
    val positions = FloatArray(desired.size)
    order.forEachIndexed { i, index -> positions[index] = maxOf(desired[index].coerceIn(minimum, maximum),
        if (i == 0) minimum else positions[order[i-1]] + spacing) }
    positions[order.last()] = minOf(positions[order.last()], maximum)
    for (i in order.lastIndex-1 downTo 0) positions[order[i]] = minOf(positions[order[i]], positions[order[i+1]] - spacing)
    return positions.toList()
}

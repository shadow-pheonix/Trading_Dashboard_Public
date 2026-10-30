package com.shadowpheonix.tradingdashboard

import kotlin.math.abs

internal fun validMacdPoint(p: MacdPoint) = listOf(p.macd, p.signal, p.histogram).all { it.isFinite() && abs(it) <= 1e20 } &&
    abs(p.histogram - (p.macd - p.signal)) < 0.000001

internal fun validateLiveChart(value: MacdSnapshot) {
    require(value.chartWindow in setOf(96, 256))
    if (value.chartWindow == 96) {
        require(value.formingCandle == null && value.chartModels.isEmpty() && value.formingModels.isEmpty() && value.chartMacdStatus == null)
        return
    }
    require(value.chartMacdStatus in setOf("ok", "stale", "loading", "failed"))
    value.formingCandle?.let { c ->
        require(value.candles.isNotEmpty() && value.candleStatus == "ok" &&
            c.timestamp == value.generatedAt / MACD_INTERVAL * MACD_INTERVAL &&
            c.timestamp == value.candles.last().timestamp + MACD_INTERVAL &&
            listOf(c.open,c.high,c.low,c.close).all { it.isFinite() && it > 0 && it <= 1e20 } &&
            c.low <= minOf(c.open,c.close) && c.high >= maxOf(c.open,c.close) &&
            value.candleSampledAt?.let { value.generatedAt-it in 0L..15_000L } == true)
    }
    if (value.candleStatus == "ok") require(value.formingCandle != null)
    if (value.chartModels.isEmpty()) {
        require(value.chartMacdStatus in setOf("loading", "failed") && value.formingModels.isEmpty())
        return
    }
    require(value.chartMacdStatus in setOf("ok", "stale") && value.chartModels.map { it.id } == listOf("M1","M2","M3"))
    value.chartModels.forEachIndexed { i,m ->
        val fast = (i+2)*150
        require(m.fastPeriod == fast && m.slowPeriod == fast*3 && m.signalPeriod == fast &&
            m.points.size == 255 && m.points.all(::validMacdPoint))
        m.points.forEachIndexed { index,p -> require(p.timestamp > 0 && p.timestamp % MACD_INTERVAL == 0L &&
            p.timestamp+MACD_INTERVAL <= value.generatedAt &&
            (index == 0 || p.timestamp-m.points[index-1].timestamp == MACD_INTERVAL)) }
        require(m.points.map { it.timestamp } == value.chartModels.first().points.map { it.timestamp })
        if (value.chartMacdStatus == "ok") require(m.points.map { it.timestamp } == value.candles.map { it.timestamp })
    }
    if (value.formingModels.isNotEmpty()) {
        require(value.formingCandle != null && value.chartMacdStatus == "ok" && value.formingModels.size == 3)
        value.formingModels.forEachIndexed { i,m ->
            val closed = value.chartModels[i]
            require(m.copy(points=closed.points) == closed && m.points.size == 1 &&
                m.points.single().timestamp == value.formingCandle.timestamp && validMacdPoint(m.points.single()))
        }
    }
}

internal fun formingIsFresh(value: MacdSnapshot?, now: Long, cached: Boolean = false, failed: Boolean = false): Boolean =
    value != null && !cached && !failed && value.candleStatus == "ok" && value.formingCandle != null &&
        value.formingCandle.timestamp == now / MACD_INTERVAL * MACD_INTERVAL &&
        value.candleSampledAt?.let { now-it in 0L..15_000L } == true

internal fun chartCandles(value: MacdSnapshot?, now: Long, cached: Boolean = false, failed: Boolean = false): List<PriceCandle> =
    value?.candles.orEmpty() + if (formingIsFresh(value,now,cached,failed)) listOf(value!!.formingCandle!!) else emptyList()

internal fun chartModel(value: MacdSnapshot?, id: String, now: Long, cached: Boolean = false, failed: Boolean = false): MacdModel? {
    val closed = value?.chartModels?.find { it.id == id } ?: value?.models?.find { it.id == id } ?: return null
    val live = if (formingIsFresh(value,now,cached,failed) && value?.chartMacdStatus == "ok")
        value.formingModels.find { it.id == id }?.points.orEmpty() else emptyList()
    return closed.copy(points=closed.points+live)
}

internal fun MacdSnapshot.withoutLivePreview() = copy(formingCandle=null, formingModels=emptyList(),
    candleStatus=if (chartWindow == 256 && candleStatus == "ok") "stale" else candleStatus,
    chartMacdStatus=if (chartMacdStatus == "ok") "stale" else chartMacdStatus)

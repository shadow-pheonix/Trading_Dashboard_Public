package com.shadowpheonix.tradingdashboard

import kotlin.math.floor
import kotlin.math.ceil

/** Index-space window shared by candles and MACD; transforms anchor at the pinch centroid. */
internal data class ChartViewport(val first: Double, val count: Double) {
    fun bounded(total: Int): ChartViewport {
        val available = total.coerceAtLeast(1).toDouble()
        val shown = count.takeIf { it.isFinite() }?.coerceIn(minOf(12.0, available), available) ?: minOf(60.0, available)
        return ChartViewport(first.takeIf { it.isFinite() }?.coerceIn(0.0, available - shown) ?: 0.0, shown)
    }

    fun transform(total: Int, zoom: Double, panFraction: Double, anchorFraction: Double): ChartViewport {
        val current = bounded(total)
        if (!zoom.isFinite() || zoom <= 0 || !panFraction.isFinite() || !anchorFraction.isFinite()) return current
        val anchor = anchorFraction.coerceIn(0.0, 1.0)
        val shown = (current.count / zoom).coerceIn(minOf(12.0, total.coerceAtLeast(1).toDouble()), total.coerceAtLeast(1).toDouble())
        return ChartViewport(current.first + current.count * anchor - shown * anchor - panFraction * shown, shown).bounded(total)
    }

    fun indexAt(fraction: Double, total: Int): Int =
        floor(first + fraction.coerceIn(0.0, 1.0) * count).toInt()
            .coerceAtMost(ceil(first + count).toInt() - 1).coerceIn(0, (total - 1).coerceAtLeast(0))

    fun zoomControl(total: Int, factor: Double): ChartViewport =
        transform(total, factor, 0.0, if (first + count >= total - 0.001) 1.0 else 0.5)

    fun rebased(oldFirstTime: Long, newFirstTime: Long, oldTotal: Int, newTotal: Int): ChartViewport =
        if (first + count >= oldTotal - 0.001) latest(newTotal, count)
        else copy(first = first - (newFirstTime - oldFirstTime).toDouble() / MACD_INTERVAL).bounded(newTotal)

    companion object {
        fun latest(total: Int, count: Double = 60.0): ChartViewport {
            val shown = count.coerceAtMost(total.coerceAtLeast(1).toDouble())
            return ChartViewport(total - shown, shown).bounded(total)
        }
    }
}

internal data class ChartPaneLayout(val plotWidth: Float, val priceHeight: Float, val macdHeight: Float) {
    val plotHeight get() = priceHeight + macdHeight
}

internal fun chartPaneLayout(width: Float, height: Float, axisWidth: Float, timeHeight: Float): ChartPaneLayout {
    val plotHeight = (height - timeHeight).coerceAtLeast(1f)
    return ChartPaneLayout((width - axisWidth).coerceAtLeast(1f), plotHeight * 0.7f, plotHeight * 0.3f)
}

/** Match real timestamps, never assume two retained series end on the same candle. */
internal fun alignedMacd(times: List<Long>, model: MacdModel?): List<MacdPoint?> {
    val byTime = model?.points?.associateBy { it.timestamp }.orEmpty()
    return times.map { byTime[it] }
}

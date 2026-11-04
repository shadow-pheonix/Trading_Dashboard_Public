package com.shadowpheonix.tradingdashboard

import kotlin.math.floor
import kotlin.math.ceil

internal const val MAX_VISIBLE_CHART_CANDLES = 256
private const val MAX_FUTURE_CHART_SLOTS = 1_000_000.0 // Finite guard for gestures/restored state, not data retention.

/** Index-space window shared by candles and MACD; transforms anchor at the pinch centroid. */
internal data class ChartViewport(val first: Double, val count: Double) {
    fun bounded(total: Int): ChartViewport {
        val available = total.coerceAtLeast(1).toDouble()
        val shown = count.takeIf { it.isFinite() }?.coerceIn(minOf(12.0, available), minOf(MAX_VISIBLE_CHART_CANDLES.toDouble(),available)) ?: minOf(60.0, available)
        return ChartViewport(first.takeIf { it.isFinite() }?.coerceIn(0.0, available + MAX_FUTURE_CHART_SLOTS) ?: 0.0, shown)
    }

    fun transform(total: Int, zoom: Double, panFraction: Double, anchorFraction: Double): ChartViewport {
        val current = bounded(total)
        if (!zoom.isFinite() || zoom <= 0 || !panFraction.isFinite() || !anchorFraction.isFinite()) return current
        val anchor = anchorFraction.coerceAtLeast(0.0) // The right gap lies beyond the nominal data width.
        val shown = (current.count / zoom).coerceIn(minOf(12.0, total.coerceAtLeast(1).toDouble()), minOf(MAX_VISIBLE_CHART_CANDLES.toDouble(),total.coerceAtLeast(1).toDouble()))
        return ChartViewport(current.first + current.count * anchor - shown * anchor - panFraction * shown, shown).bounded(total)
    }

    fun candleX(index: Int, width: Float): Float =
        ((index + 0.5 - first) / count * width).toFloat()

    /** Resolve all data visible across the full plot, including the default right gap. */
    fun forPlot(plotWidth: Float, contentWidth: Float): ChartViewport =
        copy(count = count * plotWidth.coerceAtLeast(1f) / contentWidth.coerceAtLeast(1f))

    fun visibleIndices(total: Int): IntRange {
        val from = floor(first).toInt().coerceIn(0, total.coerceAtLeast(0))
        val until = ceil(first + count).toInt().coerceIn(from, total.coerceAtLeast(0))
        return from until until
    }

    fun indexAt(fraction: Double, total: Int): Int? {
        if (!fraction.isFinite() || total <= 0) return null
        val index = floor(first + fraction.coerceIn(0.0, 1.0) * count).toInt()
            .coerceAtMost(ceil(first + count).toInt() - 1)
        return index.takeIf { it in visibleIndices(total) }
    }

    fun zoomControl(total: Int, factor: Double): ChartViewport =
        transform(total, factor, 0.0, if (kotlin.math.abs(first + count - total) < 0.001) 1.0 else 0.5)

    companion object {
        fun latest(total: Int, count: Double = 60.0): ChartViewport {
            val available = total.coerceAtLeast(1).toDouble()
            val shown = count.coerceIn(minOf(12.0,available),minOf(MAX_VISIBLE_CHART_CANDLES.toDouble(),available))
            return ChartViewport(total - shown, shown).bounded(total)
        }
    }
}

internal data class ChartPaneLayout(val plotWidth: Float, val priceHeight: Float, val macdHeight: Float) {
    val plotHeight get() = priceHeight + macdHeight
}

internal const val BTC_CHART_ID = "BTC"

internal fun chartPaneLayout(width: Float, height: Float, axisWidth: Float, timeHeight: Float, showMacd: Boolean = true): ChartPaneLayout {
    val plotHeight = (height - timeHeight).coerceAtLeast(1f)
    return ChartPaneLayout((width - axisWidth).coerceAtLeast(1f), plotHeight * if (showMacd) 0.7f else 1f, if (showMacd) plotHeight * 0.3f else 0f)
}

/** Keep a stable right-side breathing room without adding synthetic candle slots. */
internal fun chartContentWidth(plotWidth: Float, trailingSpace: Float): Float {
    val available = plotWidth.coerceAtLeast(1f)
    return (available - minOf(trailingSpace.coerceAtLeast(0f), available * 0.2f)).coerceAtLeast(1f)
}

/** Match real timestamps, never assume two retained series end on the same candle. */
internal fun alignedMacd(times: List<Long>, model: MacdModel?): List<MacdPoint?> {
    val byTime = model?.points?.associateBy { it.timestamp }.orEmpty()
    return times.map { byTime[it] }
}

/** Resolve directly against the current timestamps; no post-draw index rebasing. */
internal data class ChartWindow(val firstTime: Double = Double.NaN, val count: Double = 60.0, val following: Boolean = true) {
    fun viewport(times: List<Long>): ChartViewport =
        if (following || !firstTime.isFinite() || times.isEmpty()) ChartViewport.latest(times.size, count)
        else ChartViewport((firstTime-times.first())/MACD_INTERVAL, count).bounded(times.size)

    fun moved(next: ChartViewport, times: List<Long>, follow: Boolean = false): ChartWindow {
        val bounded = next.bounded(times.size)
        return ChartWindow((times.firstOrNull() ?: 0L) + bounded.first*MACD_INTERVAL, bounded.count, follow)
    }
}

/** Project the 15-minute time axis past the last sample; this never adds a data point. */
internal fun chartTimestamp(times: List<Long>, index: Int): Long? = times.getOrNull(index) ?: times.firstOrNull()?.let { start ->
    try { Math.addExact(start, Math.multiplyExact(index.toLong(), MACD_INTERVAL)) }
    catch (_: ArithmeticException) { null }
}

/** Auto uses visible data, or recent data while the window is entirely in empty future space. */
internal data class ChartScale(val low: Double = Double.NaN, val high: Double = Double.NaN) {
    val automatic get() = !low.isFinite() || !high.isFinite() || high <= low
    fun range(auto: ChartRange) = if (automatic) auto else ChartRange(low, high)

    /** Plot pinches control horizontal spacing; manual vertical spans only pan here. */
    fun fromPlotGesture(auto: ChartRange, horizontalZoom: Double, panFraction: Double): ChartScale {
        if (automatic || !horizontalZoom.isFinite() || horizontalZoom <= 0) return this
        return transformed(auto, 1.0, panFraction)
    }

    fun transformed(auto: ChartRange, zoom: Double, panFraction: Double, anchorFraction: Double = 0.5): ChartScale {
        if (!zoom.isFinite() || zoom <= 0 || !panFraction.isFinite() || !anchorFraction.isFinite()) return this
        val current = range(auto)
        val span = current.high-current.low
        if (!span.isFinite() || span <= 0) return this
        val anchor = anchorFraction.coerceIn(0.0, 1.0)
        val shown = (span/zoom).coerceIn(0.01, 1e16)
        val top = (current.high-span*anchor+shown*anchor+panFraction*shown).coerceIn(-1e19, 1e19)
        // Keep distinct representable bounds even after extreme pan/zoom sequences.
        return ChartScale(top-maxOf(shown,Math.ulp(top)*8), top)
    }
}

internal fun <T> chartVisible(values: List<T>, viewport: ChartViewport): List<T> {
    val indices = viewport.visibleIndices(values.size)
    return values.subList(indices.first, indices.last + 1)
}

internal fun chartPriceRange(candles: List<PriceCandle>, viewport: ChartViewport): ChartRange =
    chartVisible(candles, viewport).ifEmpty { candles.takeLast(ceil(viewport.count).toInt()) }
        .let { if (it.isEmpty()) ChartRange(0.0, 1.0) else priceRange(it) }

internal fun chartMacdRange(points: List<MacdPoint?>, viewport: ChartViewport): ChartRange {
    val values = chartVisible(points, viewport).ifEmpty { points.takeLast(ceil(viewport.count).toInt()) }
        .filterNotNull().flatMap { listOf(it.macd, it.signal, it.histogram) } + 0.0
    val low = values.min(); val high = values.max()
    val pad = maxOf((high-low)*0.12, 1.0)
    return ChartRange(low-pad, high+pad)
}

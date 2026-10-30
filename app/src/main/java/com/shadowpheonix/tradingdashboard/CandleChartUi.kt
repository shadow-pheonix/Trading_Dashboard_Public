package com.shadowpheonix.tradingdashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

internal fun chartNumber(value: Double, decimals: Int = 0): String =
    NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = decimals; maximumFractionDigits = decimals
    }.format(value)

internal fun chartTime(stamp: Long, pattern: String = "HH:mm"): String =
    DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(stamp))

internal fun previewCandleCount(plotWidthDp: Float): Int = (plotWidthDp / 5f).toInt().coerceIn(1, 96)

internal data class ChartRange(val low: Double, val high: Double) {
    fun fraction(value: Double): Float = ((high - value) / (high - low)).toFloat()
}

internal fun priceRange(candles: List<PriceCandle>, levels: List<Double> = emptyList()): ChartRange {
    val low = minOf(candles.minOf { it.low }, levels.minOrNull() ?: Double.POSITIVE_INFINITY)
    val high = maxOf(candles.maxOf { it.high }, levels.maxOrNull() ?: Double.NEGATIVE_INFINITY)
    val pad = maxOf((high - low) * 0.10, high * 0.0001, 0.01)
    return ChartRange(low - pad, high + pad)
}

internal fun DrawScope.drawPriceCandles(candles: List<PriceCandle>, x: (Int) -> Float,
    step: Float, y: (Double) -> Float, up: Color, down: Color) {
    candles.forEachIndexed { index, candle ->
        val color = if (candle.close >= candle.open) up else down
        val center = x(index)
        drawLine(color, Offset(center, y(candle.high)), Offset(center, y(candle.low)), 0.8.dp.toPx())
        val top = minOf(y(candle.open), y(candle.close))
        val width = (step * 0.68f).coerceAtLeast(1f)
        drawRect(color, Offset(center - width / 2, top),
            Size(width, abs(y(candle.open) - y(candle.close)).coerceAtLeast(1.dp.toPx())))
    }
}

@Composable
internal fun CandlePreview(value: MacdSnapshot?, now: Long, cached: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp), color = colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BTC / USDT", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("15m · Closed", color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
            when {
                value?.candles.isNullOrEmpty() -> Text(
                    if (value == null || value.candleStatus == "loading") "Loading 15-minute candles…"
                    else "Candles unavailable. Refresh to retry.", color = colors.onSurfaceVariant, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                else -> {
                    PricePreviewCanvas(value!!.candles)
                    if (cached || candlesAreStale(value, now)) Text(
                        if (candlesAreStale(value, now)) "Saved candles · Stale" else "Saved candles · Refreshing",
                        color = colors.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PricePreviewCanvas(all: List<PriceCandle>) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)
    Canvas(Modifier.fillMaxWidth().height(174.dp).semantics {
        contentDescription = "BTC USDT, 15-minute closed candles. Last close ${chartNumber(all.last().close, 2)} at ${chartTime(all.last().timestamp, "dd MMM HH:mm")}."
    }) {
        val right = (size.width - 64.dp.toPx()).coerceAtLeast(1f)
        val bottom = size.height - 24.dp.toPx(); val top = 10.dp.toPx()
        val candles = all.takeLast(previewCandleCount(right / density))
        val range = priceRange(candles)
        val step = right / candles.size
        fun x(i: Int) = (i + 0.5f) * step
        fun y(v: Double) = top + (bottom - top) * range.fraction(v)
        val last = candles.last()
        val line = colors.outlineVariant.copy(alpha = 0.40f)
        val currentY = y(last.close)
        for (i in 0..3) {
            val yy = top + (bottom - top) * i / 3
            drawLine(line, Offset(0f, yy), Offset(right, yy))
            if (abs(yy - currentY) > 15.dp.toPx()) drawText(measurer,
                chartNumber(range.high - (range.high - range.low) * i / 3),
                topLeft = Offset(right + 6.dp.toPx(), yy - 6.dp.toPx()), style = axisStyle)
        }
        listOf(0, candles.lastIndex / 2, candles.lastIndex).distinct().forEach { index ->
            val xx = x(index)
            drawLine(line, Offset(xx, top), Offset(xx, bottom))
            val label = measurer.measure(chartTime(candles[index].timestamp,
                if (index == 0) "dd MMM HH:mm" else "HH:mm"), axisStyle)
            drawText(label, topLeft = Offset((xx - label.size.width / 2).coerceIn(0f,
                (right - label.size.width).coerceAtLeast(0f)), bottom + 7.dp.toPx()))
        }
        drawPriceCandles(candles, ::x, step, ::y, colors.secondary, colors.error)
        val up = last.close >= last.open
        val accent = if (up) colors.secondary else colors.error
        drawLine(accent.copy(alpha = 0.65f), Offset(0f, currentY), Offset(right, currentY),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        val label = measurer.measure(chartNumber(last.close), TextStyle(fontSize = 10.sp,
            color = if (up) colors.onSecondary else colors.onError, fontWeight = FontWeight.SemiBold))
        drawRoundRect(accent, Offset(right + 3.dp.toPx(), currentY - 9.dp.toPx()),
            Size(label.size.width + 8.dp.toPx(), 18.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        drawText(label, topLeft = Offset(right + 7.dp.toPx(), currentY - label.size.height / 2))
    }
}

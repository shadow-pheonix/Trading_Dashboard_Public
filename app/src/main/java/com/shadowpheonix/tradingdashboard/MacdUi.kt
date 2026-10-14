package com.shadowpheonix.tradingdashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun macdNumber(value: Double): String = NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(value)
private fun candleLabel(stamp: Long): String = DateTimeFormatter.ofPattern("dd MMM HH:mm")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(stamp))

@Composable
fun MacdChart(model: MacdModel?, stale: Boolean, loading: Boolean) {
    val colors = MaterialTheme.colorScheme
    if (model == null) {
        Text(if (loading) "Loading closed-candle indicators…" else "MACD data unavailable. Refresh to retry.",
            color = colors.onSurfaceVariant, fontSize = 12.sp)
        return
    }
    val points = model.points
    var selectedTime by remember(model.id) { mutableStateOf<Long?>(null) }
    val selected = points.indexOfFirst { it.timestamp == selectedTime }.takeIf { it >= 0 } ?: points.lastIndex
    val point = points[selected]
    val macdColor = colors.primary
    val signalColor = colors.secondary
    val positive = colors.secondary
    val negative = colors.error
    val grid = colors.outlineVariant.copy(alpha = 0.6f)
    val allValues = points.flatMap { listOf(it.macd, it.signal, it.histogram, 0.0) }
    val low = allValues.min(); val high = allValues.max()
    val padding = maxOf((high - low) * 0.12, 1.0)
    val minimum = low - padding; val maximum = high + padding
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(Triple("MACD", point.macd, macdColor), Triple("Signal EMA", point.signal, signalColor),
            Triple("Histogram", point.histogram, if (point.histogram >= 0) positive else negative)).forEach { (label, value, color) ->
            Column {
                Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant)
                Text(macdNumber(value), fontSize = 16.sp, color = color, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Text("EMA ${model.fastPeriod} − ${model.slowPeriod} · Signal EMA ${model.signalPeriod}" +
        if (stale) " · Stale" else "", fontSize = 11.sp,
        color = if (stale) negative else colors.onSurfaceVariant)
    Canvas(Modifier.fillMaxWidth().height(168.dp)
        .semantics { contentDescription = "${model.id} MACD ${macdNumber(point.macd)}, signal EMA ${macdNumber(point.signal)}, histogram ${macdNumber(point.histogram)}. Tap or drag to inspect closed candles." }
        .pointerInput(model.id, points.first().timestamp) {
            detectTapGestures { selectedTime = points[(it.x / size.width * points.lastIndex).roundToInt().coerceIn(points.indices)].timestamp }
        }.pointerInput(model.id, points.first().timestamp) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                selectedTime = points[(change.position.x / size.width * points.lastIndex).roundToInt().coerceIn(points.indices)].timestamp
            }
        }) {
        fun x(i: Int) = size.width * i / points.lastIndex
        fun y(value: Double) = (size.height * (maximum - value) / (maximum - minimum)).toFloat()
        for (i in 0..4) {
            val lineY = size.height * i / 4
            drawLine(grid.copy(alpha = 0.5f), Offset(0f, lineY), Offset(size.width, lineY), 1.dp.toPx())
        }
        val zero = y(0.0)
        drawLine(grid, Offset(0f, zero), Offset(size.width, zero), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
        val width = (size.width / points.size * 0.72f).coerceAtLeast(1f)
        points.forEachIndexed { i, p ->
            val improving = i > 0 && abs(p.histogram) < abs(points[i - 1].histogram)
            drawLine((if (p.histogram >= 0) positive else negative).copy(alpha = if (improving) 0.35f else 0.65f),
                Offset(x(i), zero), Offset(x(i), y(p.histogram)), width)
        }
        fun curve(value: (MacdPoint) -> Double, color: androidx.compose.ui.graphics.Color) {
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(value(p))) else path.lineTo(x(i), y(value(p))) }
            drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        curve({ it.macd }, macdColor); curve({ it.signal }, signalColor)
        drawLine(grid, Offset(x(selected), 0f), Offset(x(selected), size.height), 1.dp.toPx())
        drawCircle(macdColor, 4.dp.toPx(), Offset(x(selected), y(point.macd)))
        drawCircle(signalColor, 3.dp.toPx(), Offset(x(selected), y(point.signal)))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(candleLabel(points.first().timestamp), color = colors.onSurfaceVariant, fontSize = 10.sp)
        Text(candleLabel(points.last().timestamp), color = colors.onSurfaceVariant, fontSize = 10.sp)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Tap or drag to inspect", color = colors.onSurfaceVariant, fontSize = 11.sp)
        Text(if (selectedTime == null) "Latest closed candle" else candleLabel(point.timestamp), color = colors.onSurfaceVariant, fontSize = 11.sp)
    }
}

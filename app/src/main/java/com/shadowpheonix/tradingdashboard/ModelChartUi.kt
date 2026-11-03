package com.shadowpheonix.tradingdashboard

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LastPage
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.exp

private val chartWindowSaver = listSaver<ChartWindow, Any>(
    save = { listOf(it.firstTime, it.count, it.following) },
    restore = { ChartWindow(it[0] as Double, it[1] as Double, it[2] as Boolean) })
private val chartScaleSaver = listSaver<ChartScale, Double>(
    save = { listOf(it.low, it.high) }, restore = { ChartScale(it[0], it[1]) })

@Composable
internal fun ModelChartScreen(state: DashboardState, modelId: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    BackHandler(onBack = onBack)
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val showMacd = modelId != BTC_CHART_ID
    val priceFraction = if (showMacd) 0.7f else 1f
    val value = state.macd
    val candles = chartCandles(value, state.now, state.macdCached, state.macdError != null)
    val model = chartModel(value, modelId, state.now, state.macdCached, state.macdError != null)
    val live = formingIsFresh(value, state.now, state.macdCached, state.macdError != null)
    val times = chartTimes(value, candles, model)
    val aligned = remember(times, model) { alignedMacd(times, model) }
    var showOverlays by rememberSaveable(state.active?.id, modelId) { mutableStateOf(true) }
    val annotations = if (showOverlays) visibleTradeAnnotations(value, modelId, state.now, state.macdCached, state.macdError != null) else emptyList()
    val colors = MaterialTheme.colorScheme
    val scope = "${state.active?.id}:$modelId"
    var window by rememberSaveable(scope, stateSaver = chartWindowSaver) { mutableStateOf(ChartWindow()) }
    var priceScale by rememberSaveable(scope, stateSaver = chartScaleSaver) { mutableStateOf(ChartScale()) }
    var macdScale by rememberSaveable(scope, stateSaver = chartScaleSaver) { mutableStateOf(ChartScale()) }
    var selectedTime by rememberSaveable(scope) { mutableStateOf<Long?>(null) }
    var inspecting by rememberSaveable(scope) { mutableStateOf(false) }
    val viewport = window.viewport(times)
    val priceRange = priceScale.range(chartPriceRange(candles, viewport))
    val indicatorRange = macdScale.range(chartMacdRange(aligned, viewport))
    fun setWindow(next: ChartViewport, follow: Boolean = window.following) { window = window.moved(next, times, follow) }
    fun latest() { window = ChartWindow(); selectedTime = null; inspecting = false }
    fun autoScales() { priceScale = ChartScale(); macdScale = ChartScale() }
    fun reset() { latest(); autoScales() }
    val selectedIndex = times.indexOf(selectedTime).takeIf { inspecting && it >= 0 }
    val selectedTrades = annotations.filter { inspecting && it.enteredAt / MACD_INTERVAL * MACD_INTERVAL == selectedTime }
    val candle = if (inspecting) selectedTime?.let { stamp -> candles.find { it.timestamp == stamp } } else candles.lastOrNull()
    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.displayCutout)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Strategies") }
            Column(Modifier.weight(1f)) {
                Text(if (showMacd) strategyLabel(modelId) else "BTC / USDT", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("${state.active?.label.orEmpty()} · BTC / USDT · 15m", fontSize = 11.sp,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!showMacd) IconToggleButton(checked = showOverlays, onCheckedChange = { showOverlays = it }) {
                Icon(if (showOverlays) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    if (showOverlays) "Hide all model entries and stop losses" else "Show all model entries and stop losses",
                    tint = if (showOverlays) colors.primary else colors.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh chart") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)) {
            listOf("O" to candle?.open, "H" to candle?.high, "L" to candle?.low, "C" to candle?.close).forEach { (label, number) ->
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 9.sp, color = colors.onSurfaceVariant)
                    Text(number?.let { chartNumber(it, 2) } ?: "—", fontSize = 11.sp,
                        color = if (label == "C" && candle != null) {
                            if (candle.close >= candle.open) colors.secondary else colors.error
                        } else colors.onSurface, maxLines = 1)
                }
            }
        }
        val priceStatus = if (live) "Live candle · Forming" else if (candlesAreStale(value, state.now)) "Candles stale / unavailable" else "Closed spot candles"
        val macdStatus = if (value?.chartWindow == 256) {
            if (value.chartMacdStatus == "loading") "MACD loading…"
            else if (live && value.formingModels.isNotEmpty()) "MACD preview · Provisional"
            else if (value.chartMacdStatus == "failed") "Live MACD unavailable" else "MACD history · Live unavailable"
        } else (if (value?.source == "bot_spot_closed") "Bot MACD" else "Public MACD") +
            if (macdIsStale(value, state.now)) " · Stale" else ""
        Text((if (state.macdError != null) "Refresh failed · Saved chart · " else if (state.macdCached) "Saved chart · " else "") + if (showMacd) "$priceStatus · $macdStatus" else priceStatus,
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), color = colors.onSurfaceVariant,
            fontSize = 10.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val annotationNote = when {
            !showOverlays -> "All model entries and stop losses hidden"
            annotations.any { it.stopState == "pending" } -> "Saved entries · SL? means protection pending"
            annotations.any { it.stopPrice == null } -> "Saved entries · SL unavailable"
            annotations.any { it.enteredAt / MACD_INTERVAL * MACD_INTERVAL > (candles.lastOrNull()?.timestamp ?: 0L) } -> "Entry candle unavailable · Waiting for price data"
            value?.annotationStatus == "partial" -> "Some trade markers unavailable"
            value?.annotationStatus != "ok" || state.macdCached || state.macdError != null ||
                value.annotationSampledAt?.let { state.now < it || state.now - it > 90_000 } != false -> "Trade markers unavailable"
            annotations.isNotEmpty() -> "Saved entries · Drag price axis to view distant SL"
            else -> if (showMacd) "No recorded open entries for this model" else "No recorded open entries across M1 / M2 / M3"
        }
        Text(annotationNote, Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (inspecting) Text(selectedTrades.joinToString("\n") { trade -> (if (showMacd) "" else "${trade.modelId} ") + entryAnnotationLabel(trade) + " · " +
            (trade.stopPrice?.let { stopAnnotationLabel(listOf(trade)) } ?: "SL unavailable") }
                .ifEmpty { "No recorded entry on selected candle" },
            Modifier.padding(horizontal = 14.dp), color = colors.onSurfaceVariant, fontSize = 10.sp,
            minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (times.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (value == null || value.status == "loading" || value.candleStatus == "loading") "Loading chart…"
                    else "Chart unavailable. Refresh to retry.", color = colors.onSurfaceVariant)
            }
        } else {
            InteractiveModelCanvas(candles, times, model, aligned, annotations, viewport, selectedIndex, inspecting,
                priceRange, indicatorRange, priceScale, macdScale, window.following, showMacd, priceFraction,
                onWindow = ::setWindow, onInspect = { index -> selectedTime = times[index]; inspecting = true },
                onPriceScale = { priceScale = it }, onMacdScale = { macdScale = it },
                onLatest = ::latest, onReset = ::reset, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = inspecting, onCheckedChange = {
                inspecting = it; selectedTime = if (it) times.getOrNull(viewport.indexAt(0.5, times.size)) else null
            }, enabled = times.isNotEmpty()) {
                Icon(Icons.Outlined.MyLocation, "Crosshair inspection", tint = if (inspecting) colors.primary else colors.onSurfaceVariant)
            }
            IconButton(onClick = { setWindow(viewport.zoomControl(times.size, 1 / 1.35)) },
                enabled = times.isNotEmpty() && viewport.count < minOf(96,times.size)) { Icon(Icons.Outlined.Remove, "Zoom out") }
            IconButton(onClick = { setWindow(viewport.zoomControl(times.size, 1.35)) },
                enabled = times.isNotEmpty() && viewport.count > minOf(12, times.size)) { Icon(Icons.Outlined.Add, "Zoom in") }
            IconButton(onClick = ::latest, enabled = times.isNotEmpty()) { Icon(Icons.AutoMirrored.Outlined.LastPage, "Follow latest candles",
                tint = if (window.following) colors.primary else colors.onSurfaceVariant) }
            TextButton(onClick = ::autoScales, modifier = Modifier.semantics { contentDescription = "Reset vertical scales to automatic" }) {
                Text("Auto", color = if (priceScale.automatic && (!showMacd || macdScale.automatic)) colors.primary else colors.onSurfaceVariant)
            }
        }
        Text(if (inspecting) "Drag to inspect · Tap crosshair to pan"
            else if (!priceScale.automatic) "Drag chart freely · Drag price axis to scale\nDouble-tap price axis or tap Auto to reset"
            else "Drag to pan · Pinch to zoom · Hold to inspect\nDrag price axis to unlock · Drag time axis to zoom", color = colors.onSurfaceVariant,
            minLines = 2, maxLines = 2, fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
    }
}

@Composable
private fun InteractiveModelCanvas(candles: List<PriceCandle>, times: List<Long>, model: MacdModel?,
    aligned: List<MacdPoint?>, annotations: List<ChartTradeAnnotation>, viewport: ChartViewport, selectedIndex: Int?, inspecting: Boolean,
    priceRange: ChartRange, indicatorRange: ChartRange, priceScale: ChartScale, macdScale: ChartScale, following: Boolean, showMacd: Boolean, priceFraction: Float,
    onWindow: (ChartViewport, Boolean) -> Unit, onInspect: (Int) -> Unit,
    onPriceScale: (ChartScale) -> Unit, onMacdScale: (ChartScale) -> Unit,
    onLatest: () -> Unit, onReset: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant)
    // Stable gutter: changing quotes, wallet labels and marker availability cannot resize the plot.
    val priceWidth = measurer.measure("888,888.88", labelStyle).size.width.toFloat()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val axisWidth = with(density) { maxOf(64.dp.toPx(), priceWidth + 14.dp.toPx()) }
    val axisWidthDp = with(density) { axisWidth.toDp() }
    val currentViewport by rememberUpdatedState(viewport)
    val currentInspecting by rememberUpdatedState(inspecting)
    val currentOnWindow by rememberUpdatedState(onWindow)
    val currentOnInspect by rememberUpdatedState(onInspect)
    val currentOnLatest by rememberUpdatedState(onLatest)
    val currentOnReset by rememberUpdatedState(onReset)
    val currentPriceRange by rememberUpdatedState(priceRange)
    val currentIndicatorRange by rememberUpdatedState(indicatorRange)
    val currentPriceScale by rememberUpdatedState(priceScale)
    val currentMacdScale by rememberUpdatedState(macdScale)
    val currentOnPriceScale by rememberUpdatedState(onPriceScale)
    val currentOnMacdScale by rememberUpdatedState(onMacdScale)
    val currentFollowing by rememberUpdatedState(following)
    val currentTotal by rememberUpdatedState(times.size)
    var plotPrice by remember(model?.id) { mutableStateOf(true) }
    fun inspect(x: Float, width: Float) {
        currentOnInspect(currentViewport.indexAt((x / width.coerceAtLeast(1f)).toDouble(), currentTotal))
    }
    val gestures = Modifier
        .pointerInput(model?.id) {
            detectTapGestures(onPress = { plotPrice = it.y < size.height*priceFraction },
                onTap = { inspect(it.x, size.width.toFloat()) }, onDoubleTap = { currentOnReset() })
        }
        .pointerInput(model?.id) {
            detectDragGesturesAfterLongPress(onDragStart = { inspect(it.x, size.width.toFloat()) }) { change, _ ->
                change.consume(); inspect(change.position.x, size.width.toFloat())
            }
        }
        .pointerInput(model?.id) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val width = size.width.toFloat().coerceAtLeast(1f)
                if (currentInspecting && abs(zoom - 1f) < 0.001f) inspect(centroid.x + pan.x, size.width.toFloat())
                else {
                    val next = currentViewport.transform(currentTotal, zoom.toDouble(),
                        (pan.x / width).toDouble(), (centroid.x / width).toDouble())
                    currentOnWindow(next, currentFollowing && abs(pan.x) < 0.01f && next.first+next.count >= currentTotal-0.001)
                    val height = size.height.toFloat()
                    if (plotPrice && !currentPriceScale.automatic) {
                        val top = 12.dp.toPx(); val span = (height*priceFraction-24.dp.toPx()).coerceAtLeast(1f)
                        currentOnPriceScale(currentPriceScale.transformed(currentPriceRange,zoom.toDouble(),
                            (pan.y/span).toDouble(),((centroid.y-top)/span).toDouble()))
                    } else if (!plotPrice && !currentMacdScale.automatic) {
                        val top = height*priceFraction+minOf(28.dp.toPx(),height*0.3f*0.3f)
                        val span = (height-8.dp.toPx()-top).coerceAtLeast(1f)
                        currentOnMacdScale(currentMacdScale.transformed(currentIndicatorRange,zoom.toDouble(),
                            (pan.y/span).toDouble(),((centroid.y-top)/span).toDouble()))
                    }
                }
            }
        }
    Box(modifier) {
    Canvas(Modifier.matchParentSize().semantics {
        contentDescription = (if (showMacd) "${model?.id ?: "Model"} chart. Candles above MACD in a 70 to 30 split." else "BTC chart. Price candles only, with all model overlays.") + " Pinch to zoom, drag to pan, hold or use crosshair to inspect. Drag the right axis for manual vertical scale. ${candles.size} candle samples across ${times.size} time slots; an unavailable forming slot stays empty."
    }) {
        val layout = chartPaneLayout(size.width, size.height, axisWidth, 28.dp.toPx(), showMacd)
        val right = layout.plotWidth
        val priceTop = 12.dp.toPx(); val priceBottom = (layout.priceHeight - 12.dp.toPx()).coerceAtLeast(priceTop + 1)
        val macdTop = layout.priceHeight + minOf(28.dp.toPx(), layout.macdHeight * 0.30f)
        val macdBottom = (layout.plotHeight - 8.dp.toPx()).coerceAtLeast(macdTop + 1)
        val from = floor(viewport.first).toInt().coerceIn(times.indices)
        val until = ceil(viewport.first + viewport.count).toInt().coerceIn(from + 1, times.size)
        val visibleIndices = from until until
        val step = right / viewport.count.toFloat()
        fun x(index: Int) = ((index + 0.5 - viewport.first) / viewport.count * right).toFloat()
        val shownCandles = chartVisible(candles, viewport)
        val stops = if (candles.isEmpty()) emptyList() else annotations.mapNotNull { trade ->
            val index = entryCandleIndex(trade, times)
            if (index != null && trade.stopPrice != null && stopLineStart(index, viewport, right) != null) index to trade else null
        }
        val entries = if (candles.isEmpty()) emptyList() else entryChartMarkers(annotations, times).filter { it.index in visibleIndices }
        fun priceY(value: Double) = priceTop + (priceBottom - priceTop) * priceRange.fraction(value)
        fun macdY(value: Double) = macdTop + (macdBottom - macdTop) * indicatorRange.fraction(value)
        val line = colors.outlineVariant.copy(alpha = 0.42f)
        val dashed = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        fun label(text: String, left: Float, top: Float, color: Color = colors.onSurfaceVariant) {
            drawText(measurer, text, topLeft = Offset(left, top), style = labelStyle.copy(color = color))
        }
        data class PriceBadge(val text: String, val y: Float, val background: Color, val foreground: Color, val inside: Boolean)
        val badges = mutableListOf<PriceBadge>()
        fun axisBadge(text: String, y: Float, background: Color, foreground: Color, inside: Boolean = false) {
            if (y in priceTop..priceBottom) badges.add(PriceBadge(text, y, background, foreground, inside))
        }
        for (i in 0..4) {
            val y = priceTop + (priceBottom - priceTop) * i / 4
            drawLine(line, Offset(0f, y), Offset(right, y))
            if (shownCandles.isNotEmpty()) label(chartNumber(priceRange.high - (priceRange.high - priceRange.low) * i / 4),
                right + 6.dp.toPx(), y - 6.dp.toPx())
        }
        listOf(from, (from + until - 1) / 2, until - 1).distinct().forEach { index ->
            val xx = x(index).coerceIn(0f, right)
            drawLine(line, Offset(xx, 0f), Offset(xx, layout.plotHeight))
            val text = chartTime(times[index], if (index == from) "dd MMM HH:mm" else "HH:mm")
            val measured = measurer.measure(text, labelStyle)
            drawText(measured, topLeft = Offset((xx - measured.size.width / 2).coerceIn(0f,
                (right - measured.size.width).coerceAtLeast(0f)), layout.plotHeight + 7.dp.toPx()))
        }
        drawLine(line, Offset(right, 0f), Offset(right, layout.plotHeight))
        drawLine(colors.outlineVariant, Offset(0f, layout.priceHeight), Offset(size.width, layout.priceHeight))
        clipRect(0f, priceTop, right, priceBottom) {
            drawPriceCandles(shownCandles, { x(it + from) }, step, ::priceY, colors.secondary, colors.error)
        }
        if (shownCandles.isEmpty()) label("15-minute candles unavailable", 12.dp.toPx(), layout.priceHeight / 2)
        candles.lastOrNull()?.let { latest ->
            val yy = priceY(latest.close)
            if (yy in priceTop..priceBottom) {
                val up = latest.close >= latest.open
                val accent = if (up) colors.secondary else colors.error
                drawLine(accent.copy(alpha = 0.6f), Offset(0f, yy), Offset(right, yy), pathEffect = dashed)
                axisBadge(chartNumber(latest.close), yy, accent, if (up) colors.onSecondary else colors.onError)
            }
        }
        clipRect(0f, priceTop, right, priceBottom) {
            stops.forEach { (index, trade) ->
                val start = stopLineStart(index, viewport, right)!!
                val yy = priceY(trade.stopPrice!!)
                drawLine(colors.error, Offset(start, yy), Offset(right, yy), 1.dp.toPx(), pathEffect = dashed)
                if (x(index) in 0f..right) drawCircle(colors.error, 2.5.dp.toPx(), Offset(start, yy))
            }
        }
        stops.groupBy { it.second.stopPrice!! }.values.forEach { group ->
            axisBadge(stopAnnotationLabel(group.map { it.second }, includeModel = !showMacd), priceY(group.first().second.stopPrice!!), colors.error, colors.onError, inside = true)
        }
        val occupied = mutableListOf<androidx.compose.ui.geometry.Rect>()
        clipRect(0f, priceTop, right, priceBottom) {
            entries.forEach { marker ->
                val candle = candles.getOrNull(marker.index) ?: return@forEach
                val long = marker.side == "LONG"
                val xx = x(marker.index)
                val direction = if (long) 1f else -1f
                val tip = if (long) priceY(candle.low) + 4.dp.toPx() else priceY(candle.high) - 4.dp.toPx()
                if (tip !in priceTop..priceBottom) return@forEach
                val stack = entries.filter { it.index == marker.index && it.side == marker.side }
                val rank = stack.indexOf(marker)
                val extent = (stack.size-1) * 16.dp.toPx() + 14.dp.toPx()
                val firstTip = if (long) minOf(tip, priceBottom-extent) else maxOf(tip, priceTop+extent)
                val arrowTip = firstTip + direction * rank * 16.dp.toPx()
                val base = arrowTip + direction * 9.dp.toPx()
                val color = if (long) colors.secondary else colors.error
                val arrow = Path().apply {
                    moveTo(xx, arrowTip); lineTo(xx - 5.dp.toPx(), base); lineTo(xx + 5.dp.toPx(), base); close()
                }
                drawPath(arrow, color)
                drawLine(color, Offset(xx, base), Offset(xx, base + direction * 5.dp.toPx()), 2.dp.toPx())
                val text = measurer.measure(entryAnnotationLabel(marker, includeModel = !showMacd), labelStyle.copy(color = color))
                val height = text.size.height + 6.dp.toPx()
                val width = (text.size.width + 8.dp.toPx()).coerceAtMost(right)
                val preferredY = if (long) base + 7.dp.toPx() else base - height - 7.dp.toPx()
                val rect = entryLabelRect(xx, preferredY, width, height, right, priceTop, priceBottom, occupied)
                occupied.add(rect)
                drawRect(colors.surface.copy(alpha = 0.96f), rect.topLeft, rect.size)
                drawRect(color.copy(alpha = 0.7f), rect.topLeft, rect.size, style = Stroke(0.7.dp.toPx()))
                drawText(text, topLeft = rect.topLeft + Offset(4.dp.toPx(), 3.dp.toPx()))
            }
        }
        if (showMacd) {
        val macdTicks = if (macdBottom - macdTop >= 64.dp.toPx()) 2 else 1
        for (i in 0..macdTicks) {
            val yy = macdTop + (macdBottom - macdTop) * i / macdTicks
            drawLine(line, Offset(0f, yy), Offset(right, yy))
            label(chartNumber(indicatorRange.high - (indicatorRange.high - indicatorRange.low) * i / macdTicks),
                right + 6.dp.toPx(), yy - 6.dp.toPx())
        }
        val zero = macdY(0.0)
        if (zero in macdTop..macdBottom) drawLine(line, Offset(0f, zero), Offset(right, zero), pathEffect = dashed)
        clipRect(0f, macdTop, right, macdBottom) {
            visibleIndices.forEach { index -> aligned[index]?.let { point ->
                drawLine((if (point.histogram >= 0) colors.secondary else colors.error).copy(alpha = 0.65f),
                    Offset(x(index), zero), Offset(x(index), macdY(point.histogram)), (step * 0.65f).coerceAtLeast(1f))
            } }
            fun curve(color: Color, value: (MacdPoint) -> Double) {
                val path = Path(); var started = false
                visibleIndices.forEach { index ->
                    val point = aligned[index]
                    if (point == null) started = false
                    else {
                        if (started) path.lineTo(x(index), macdY(value(point))) else path.moveTo(x(index), macdY(value(point)))
                        started = true
                    }
                }
                drawPath(path, color, style = Stroke(1.8.dp.toPx()))
            }
            curve(colors.primary) { it.macd }; curve(colors.secondary) { it.signal }
        }
        if (visibleIndices.none { aligned[it] != null }) label("No MACD for these candles", 12.dp.toPx(), (macdTop + macdBottom) / 2)
        val point = aligned.getOrNull(selectedIndex ?: times.lastIndex)
        var legendX = 8.dp.toPx()
        val legendY = layout.priceHeight + 4.dp.toPx()
        val legend = listOf("MACD ${model?.fastPeriod ?: "—"}/${model?.slowPeriod ?: "—"}/${model?.signalPeriod ?: "—"}" to colors.onSurfaceVariant,
            (point?.macd?.let { chartNumber(it, 1) } ?: "—") to colors.primary,
            (point?.signal?.let { chartNumber(it, 1) } ?: "—") to colors.secondary,
            (point?.histogram?.let { chartNumber(it, 1) } ?: "—") to if ((point?.histogram ?: 0.0) >= 0) colors.secondary else colors.error)
        clipRect(0f, layout.priceHeight, right, macdTop) {
            legend.forEach { (text, color) ->
                val measured = measurer.measure(text, labelStyle.copy(fontSize = 9.sp, color = color))
                drawText(measured, topLeft = Offset(legendX, legendY)); legendX += measured.size.width + 9.dp.toPx()
            }
        }
        }
        selectedIndex?.takeIf { it in visibleIndices }?.let { index ->
            val xx = x(index).coerceIn(0f, right)
            drawLine(colors.onSurfaceVariant, Offset(xx, 0f), Offset(xx, layout.plotHeight), pathEffect = dashed)
            candles.getOrNull(index)?.let { selected ->
                val yy = priceY(selected.close)
                if (yy in priceTop..priceBottom) {
                    drawLine(colors.onSurfaceVariant, Offset(0f, yy), Offset(right, yy), pathEffect = dashed)
                    drawCircle(colors.primary, 3.dp.toPx(), Offset(xx, yy))
                    axisBadge(chartNumber(selected.close), yy, colors.primary, colors.onPrimary)
                }
            }
            if (showMacd) aligned[index]?.let { selected ->
                clipRect(0f,macdTop,right,macdBottom) {
                    drawCircle(colors.primary, 3.dp.toPx(), Offset(xx, macdY(selected.macd)))
                    drawCircle(colors.secondary, 2.5.dp.toPx(), Offset(xx, macdY(selected.signal)))
                }
            }
            val text = measurer.measure(chartTime(times[index], "dd MMM HH:mm"), labelStyle.copy(color = colors.onPrimary))
            val width = text.size.width + 10.dp.toPx()
            val left = (xx - width / 2).coerceIn(0f, (right - width).coerceAtLeast(0f))
            drawRect(colors.primary, Offset(left, layout.plotHeight + 3.dp.toPx()), Size(width, 23.dp.toPx()))
            drawText(text, topLeft = Offset(left + 5.dp.toPx(), layout.plotHeight + 7.dp.toPx()))
        }
        val badgeHeight = measurer.measure("0", labelStyle).size.height + 6.dp.toPx()
        val centers = priceLabelCenters(badges.map { it.y }, badgeHeight, priceTop, priceBottom)
        badges.forEachIndexed { index, badge ->
            val center = centers[index]
            if (badge.inside) {
                val text = measurer.measure(badge.text, labelStyle.copy(color = badge.foreground))
                val width = (text.size.width+8.dp.toPx()).coerceAtMost(right)
                clipRect(0f, priceTop, right, priceBottom) {
                    if (abs(center-badge.y)>1.dp.toPx()) drawLine(badge.background,
                        Offset(right-width,badge.y),Offset(right-width+2.dp.toPx(),center),1.dp.toPx())
                    drawRect(badge.background, Offset(right-width,center-badgeHeight/2),Size(width,badgeHeight))
                    drawText(text,topLeft=Offset(right-width+4.dp.toPx(),center-badgeHeight/2+3.dp.toPx()))
                }
                return@forEachIndexed
            }
            if (abs(center - badge.y) > 1.dp.toPx()) drawLine(badge.background,
                Offset(right, badge.y), Offset(right + 3.dp.toPx(), center), 1.dp.toPx())
            drawRect(badge.background, Offset(right + 3.dp.toPx(), center - badgeHeight / 2),
                Size(size.width - right - 3.dp.toPx(), badgeHeight))
            drawText(measurer, badge.text, topLeft = Offset(right + 6.dp.toPx(), center - badgeHeight / 2 + 3.dp.toPx()),
                style = labelStyle.copy(color = badge.foreground))
        }
    }
    // Separate hit regions keep axis drags out of candle panning and crosshair detectors.
    Row(Modifier.matchParentSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.weight(1f).fillMaxWidth().then(gestures))
            Box(Modifier.height(28.dp).fillMaxWidth()
                .semantics { contentDescription = "Time axis. Drag horizontally to zoom; double tap to follow latest." }
                .pointerInput(model?.id) { detectTapGestures(onDoubleTap = { currentOnLatest() }) }
                .pointerInput(model?.id) {
                    detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        val zoom = exp(amount.toDouble()/size.width.coerceAtLeast(1)*3)
                        currentOnWindow(currentViewport.zoomControl(currentTotal,zoom),currentFollowing)
                    }
                })
        }
        Box(Modifier.width(axisWidthDp).fillMaxHeight().padding(bottom = 28.dp)
            .semantics { contentDescription = (if (showMacd) "Vertical price and MACD axes." else "Vertical price axis.") + " Drag to scale manually; double tap for automatic scale." }
            .pointerInput(model?.id) {
                detectTapGestures(onDoubleTap = { point ->
                    if (point.y < size.height*priceFraction) currentOnPriceScale(ChartScale()) else currentOnMacdScale(ChartScale())
                })
            }
            .pointerInput(model?.id) {
                var price = true
                detectVerticalDragGestures(onDragStart = { price = it.y < size.height*priceFraction }) { change, amount ->
                    change.consume()
                    val span = (size.height * if (price) priceFraction else 1f-priceFraction).coerceAtLeast(1f)
                    val zoom = exp(-amount.toDouble()/span*3)
                    if (price) currentOnPriceScale(currentPriceScale.transformed(currentPriceRange,zoom,0.0))
                    else currentOnMacdScale(currentMacdScale.transformed(currentIndicatorRange,zoom,0.0))
                }
            })
    }
    }
}

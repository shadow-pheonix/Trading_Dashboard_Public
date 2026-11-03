package com.shadowpheonix.tradingdashboard

import android.app.Application
import android.os.Build
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DashboardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(this)
    }
}

internal object AppDiagnostics {
    @Volatile private var store: DiagnosticStore? = null
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { runnable -> Thread(runnable, "dashboard-diagnostics").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val last = mutableMapOf<DiagnosticEvent, Long>()

    fun initialize(app: Application) {
        store = DiagnosticStore(File(app.filesDir, "diagnostics"))
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Dedicated crash record survives rolling event rotation. No exception text.
                store?.append(DiagnosticRecord(System.currentTimeMillis(), DiagnosticEvent.Crash,
                    failure = diagnosticFailure(error), frames = diagnosticFrames(error)))
            } catch (_: Throwable) { /* Diagnostics must not interfere with crash handling. */ }
            if (previous != null) previous.uncaughtException(thread, error) else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        record(DiagnosticEvent.ProcessStarted, mapOf(DiagnosticField.Version to BuildConfig.VERSION_CODE.toLong(),
            DiagnosticField.Sdk to Build.VERSION.SDK_INT.toLong()))
    }

    fun record(event: DiagnosticEvent, values: Map<DiagnosticField, Long> = emptyMap(), error: Throwable? = null, throttle: Boolean = false) {
        if (store == null) return
        val now = System.currentTimeMillis()
        if (throttle) synchronized(last) {
            if (now - (last[event] ?: 0) < 1000) return
            last[event] = now
        }
        val record = DiagnosticRecord(now, event, values.toMap(), error?.let(::diagnosticFailure),
            error?.let(::diagnosticFrames) ?: emptyList())
        runCatching { writer.execute { runCatching { store?.append(record) }.onFailure { dropped.incrementAndGet() } } }
            .onFailure { dropped.incrementAndGet() }
    }

    // Called on Dispatchers.IO. FIFO submission drains earlier events before export/clear.
    fun export(file: File, summary: Map<DiagnosticField, Long>) {
        writer.submit { checkNotNull(store).export(file, summary + (DiagnosticField.Dropped to dropped.get())) }.get(10, TimeUnit.SECONDS)
    }
    fun clear() { writer.submit { checkNotNull(store).clear(); dropped.set(0) }.get(10, TimeUnit.SECONDS) }
}

internal data class DiagnosticScope(val value: Long)
internal fun diagnosticScope(id: String): DiagnosticScope {
    val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
    return DiagnosticScope(java.nio.ByteBuffer.wrap(bytes).long and Long.MAX_VALUE)
}

internal fun diagnosticRequestValues(call: Call): Map<DiagnosticField, Long> {
    val request = call.request()
    val endpoint = when {
        request.url.encodedPath.endsWith("/alerts/live") -> 4L
        request.url.encodedPath.endsWith("/balances") -> 3L
        request.url.encodedPath.endsWith("/live") -> 2L
        request.url.queryParameter("view") in setOf("macd", "chart") -> 1L
        else -> 0L
    }
    return mapOf(DiagnosticField.Endpoint to endpoint, DiagnosticField.Scope to (request.tag(DiagnosticScope::class.java)?.value ?: 0L))
}

internal class DiagnosticHttpListener : EventListener() {
    private var started = 0L
    private var status = 0L
    override fun callStart(call: Call) { started = System.nanoTime() }
    override fun responseHeadersEnd(call: Call, response: Response) { status = response.code.toLong() }
    override fun callEnd(call: Call) { AppDiagnostics.record(DiagnosticEvent.HttpFinished,
        diagnosticRequestValues(call) + mapOf(DiagnosticField.Status to status,
            DiagnosticField.DurationMs to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))) }
    override fun callFailed(call: Call, ioe: IOException) { AppDiagnostics.record(DiagnosticEvent.HttpFailed,
        diagnosticRequestValues(call) + mapOf(DiagnosticField.Status to status,
            DiagnosticField.DurationMs to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)), ioe) }
}

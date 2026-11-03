package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// A closed vocabulary, with numeric values only. Never pass free text, URLs,
// request/response bodies, labels, credentials or Throwable messages here.
@Serializable
internal enum class DiagnosticEvent {
    ProcessStarted, ActivityStarted, ActivityStopped, Screen, HttpFinished, HttpFailed,
    StreamOpened, StreamClosed, StreamFailed, InvalidData, AlertBatch,
    ChartOpened, ChartData, ChartViewport, ChartLayout, AnalyticsView, Crash,
}
@Serializable
internal enum class DiagnosticField {
    Version, Sdk, Width, Height, Density, FontScale, Orientation, Page, Model, Scope,
    Endpoint, Status, DurationMs, Code, Count, Candles, Macd, Forming, Cached, Failed,
    Visible, FirstSlot, FirstTimestamp, LastTimestamp, Following, ManualPrice, ManualMacd, Inspecting, Landscape, View, Dropped,
}
@Serializable
internal enum class DiagnosticFailure { Network, InvalidData, Security, State, Memory, Other }
@Serializable
internal enum class DiagnosticSource { DashboardApi, AlertApi, ModelChartUi, LiveChart, ChartViewport, Macd,
    DashboardViewModel, ForegroundSession, MainActivity, AlertService, Diagnostics, AppDiagnostics, Other }
@Serializable
internal data class DiagnosticFrame(val source: DiagnosticSource, val line: Int)
@Serializable
internal data class DiagnosticRecord(val time: Long, val event: DiagnosticEvent,
    val values: Map<DiagnosticField, Long> = emptyMap(), val failure: DiagnosticFailure? = null,
    val frames: List<DiagnosticFrame> = emptyList())

internal fun diagnosticFailure(error: Throwable): DiagnosticFailure = when (error) {
    is SecurityException -> DiagnosticFailure.Security
    is kotlinx.serialization.SerializationException, is IllegalArgumentException -> DiagnosticFailure.InvalidData
    is IOException -> DiagnosticFailure.Network
    is IllegalStateException -> DiagnosticFailure.State
    is OutOfMemoryError -> DiagnosticFailure.Memory
    else -> DiagnosticFailure.Other
}

internal fun diagnosticFrames(error: Throwable): List<DiagnosticFrame> {
    val frames = mutableListOf<DiagnosticFrame>()
    var cause: Throwable? = error
    val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    repeat(8) {
        val current = cause ?: return@repeat
        if (!visited.add(current)) return@repeat
        current.stackTrace.filter { it.className.startsWith("com.shadowpheonix.tradingdashboard.") }.take(24).forEach { frame ->
            val source = DiagnosticSource.entries.find { "${it.name}.kt" == frame.fileName } ?: DiagnosticSource.Other
            frames += DiagnosticFrame(source, frame.lineNumber.coerceIn(-1, 1_000_000))
        }
        cause = current.cause
    }
    return frames.take(24)
}

internal fun diagnosticModel(model: String): Long = when (model) { "M1" -> 1; "M2" -> 2; "M3" -> 3; else -> 0 }

internal class DiagnosticStore(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    companion object { const val FILE_LIMIT = 128 * 1024; const val MAX_EVENTS = 2000; const val RETENTION_MS = 24 * 60 * 60 * 1000L }
    private val json = Json { encodeDefaults = true } // Unknown fields fail closed, including on export.
    private val current get() = File(directory, "events.jsonl")
    private val previous get() = File(directory, "previous.jsonl")
    private val crash get() = File(directory, "crash.json")

    private fun valid(record: DiagnosticRecord): Boolean = record.time > 0 && record.values.size <= DiagnosticField.entries.size &&
        record.frames.size <= 24 && record.frames.all { it.line in -1..1_000_000 }

    @Synchronized fun append(record: DiagnosticRecord) {
        require(valid(record))
        directory.mkdirs()
        val line = json.encodeToString(record) + "\n"
        require(line.toByteArray().size <= 8192)
        if (record.event == DiagnosticEvent.Crash) { crash.writeText(line); return }
        if (current.length() + line.toByteArray().size > FILE_LIMIT) {
            previous.delete()
            check(!current.exists() || current.renameTo(previous))
        }
        current.appendText(line)
    }

    @Synchronized fun records(): List<DiagnosticRecord> {
        val now = clock()
        val records = listOf(previous, current, crash).flatMap { file ->
            // Android may alias the parent storage directory (/data/user/0).
            // Reject a linked log file, while allowing the OS's directory alias.
            if (!file.isFile || file.length() > FILE_LIMIT || java.nio.file.Files.isSymbolicLink(file.toPath()) ||
                file.canonicalFile.parentFile != directory.canonicalFile) emptyList()
            else file.readLines().mapNotNull { line ->
                if (line.length > 8192) null else runCatching { json.decodeFromString<DiagnosticRecord>(line) }.getOrNull()
            }
        }.filter { valid(it) && now - it.time in 0..RETENTION_MS }
            .sortedBy { it.time }
        val lastCrash = records.lastOrNull { it.event == DiagnosticEvent.Crash }
        return (records.filter { it.event != DiagnosticEvent.Crash }.takeLast(MAX_EVENTS - if (lastCrash != null) 1 else 0) +
            listOfNotNull(lastCrash)).sortedBy { it.time }
    }

    @Synchronized fun clear() { listOf(previous, current, crash).forEach { check(!it.exists() || it.delete()) } }

    @Synchronized fun export(destination: File, summary: Map<DiagnosticField, Long>) {
        val records = records()
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream()).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("summary.json", json.encodeToString(summary))
            entry("events.jsonl", records.joinToString("\n", postfix = if (records.isEmpty()) "" else "\n") { json.encodeToString(it) })
            entry("README.txt", "Trading Dashboard diagnostics. Times are UTC epoch milliseconds.\n" +
                "Last 24 hours, at most 2000 events. Numeric fields and fixed event names only.\n" +
                "No keys, server addresses, connection labels, notification text, balances or trade history.\n" +
                "Crash frames contain allowlisted app source names and line numbers, never exception messages.\n")
        }
    }
}

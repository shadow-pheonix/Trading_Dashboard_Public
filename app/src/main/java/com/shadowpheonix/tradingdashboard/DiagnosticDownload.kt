package com.shadowpheonix.tradingdashboard

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun diagnosticDownloadName(at: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
    "Trading-Dashboard-diagnostics-${DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS", java.util.Locale.US).withZone(zone).format(at)}.zip"

internal interface DiagnosticDownloadDestination {
    fun open(): OutputStream
    fun publish()
    fun discard()
}

/** Publish only a complete, closed ZIP; remove a partial destination on failure. */
internal fun copyDiagnosticDownload(source: File, destination: DiagnosticDownloadDestination) {
    try {
        destination.open().use { output -> source.inputStream().use { it.copyTo(output) } }
        destination.publish()
    } catch (failure: Throwable) {
        runCatching { destination.discard() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

internal fun reserveDiagnosticDownload(directory: File, name: String): File {
    check(directory.isDirectory || directory.mkdirs())
    for (suffix in 0..999) {
        val file = File(directory, if (suffix == 0) name else "${name.removeSuffix(".zip")}-$suffix.zip")
        if (file.createNewFile()) return file
    }
    error("Could not reserve a diagnostics filename")
}

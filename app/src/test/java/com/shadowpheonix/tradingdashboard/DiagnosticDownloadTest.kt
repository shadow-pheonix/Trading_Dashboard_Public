package com.shadowpheonix.tradingdashboard

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class DiagnosticDownloadTest {
    @Test fun filenameIncludesLocalTimestampAndMilliseconds() {
        val at = Instant.parse("2026-11-04T11:49:23.456Z")
        assertEquals("Trading-Dashboard-diagnostics-2026-11-04_17-19-23-456.zip",
            diagnosticDownloadName(at, ZoneId.of("Asia/Kolkata")))
    }

    @Test fun repeatedExportsNeverOverwriteExistingDownloads() {
        val directory = Files.createTempDirectory("diagnostic-download").toFile()
        try {
            val name = diagnosticDownloadName(Instant.EPOCH, ZoneId.of("UTC"))
            val first = reserveDiagnosticDownload(directory, name).apply { writeText("existing") }
            val second = reserveDiagnosticDownload(directory, name)
            assertNotEquals(first, second)
            assertEquals("existing", first.readText())
            assertTrue(second.name.endsWith("-1.zip"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun completeSanitizedZipIsClosedBeforePublication() {
        val directory = Files.createTempDirectory("diagnostic-download").toFile()
        try {
            val zip = java.io.File(directory, "private.zip")
            DiagnosticStore(java.io.File(directory, "logs")).export(zip, mapOf(DiagnosticField.Version to 66))
            val destination = RecordingDestination()
            copyDiagnosticDownload(zip, destination)
            assertEquals(listOf("open", "close", "publish"), destination.calls)
            val saved = java.io.File(directory, "download.zip").apply { writeBytes(destination.bytes.toByteArray()) }
            ZipFile(saved).use { assertEquals(setOf("summary.json", "events.jsonl", "README.txt"), it.entries().asSequence().map { e -> e.name }.toSet()) }
            assertArrayEquals(zip.readBytes(), saved.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun openingWritingClosingAndPublishingFailuresDiscardPartialDownload() {
        val source = Files.createTempFile("diagnostic-copy", ".zip").toFile().apply { writeText("zip bytes") }
        try {
            for (stage in listOf("open", "write", "close", "publish")) {
                val target = RecordingDestination(stage)
                assertThrows(IOException::class.java) { copyDiagnosticDownload(source, target) }
                assertEquals("discard", target.calls.last())
                if (stage != "publish") assertFalse(target.calls.contains("publish"))
            }
        } finally { source.delete() }
    }

    @Test fun missingPrivateSourceAlsoRemovesReservedDestination() {
        val target = RecordingDestination()
        assertThrows(IOException::class.java) { copyDiagnosticDownload(java.io.File("/nonexistent/diagnostics.zip"), target) }
        assertEquals(listOf("open", "close", "discard"), target.calls)
    }

    private class RecordingDestination(private val failAt: String? = null) : DiagnosticDownloadDestination {
        val calls = mutableListOf<String>()
        val bytes = ByteArrayOutputStream()
        private fun step(name: String) { calls.add(name); if (failAt == name) throw IOException("Synthetic failure") }
        override fun open(): OutputStream {
            step("open")
            return object : OutputStream() {
                override fun write(b: Int) { if (failAt == "write") throw IOException("Synthetic write failure"); bytes.write(b) }
                override fun close() { step("close") }
            }
        }
        override fun publish() { step("publish") }
        override fun discard() { step("discard") }
    }
}

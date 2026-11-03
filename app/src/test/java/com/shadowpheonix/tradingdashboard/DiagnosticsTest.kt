package com.shadowpheonix.tradingdashboard

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class DiagnosticsTest {
    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("diagnostics-test").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun networkMetadataContainsOnlyEndpointCodeAndPseudonym() {
        val connection = Connection("synthetic-instance-id", "private-label", "https://private.invalid", "a".repeat(64))
        val client = okhttp3.OkHttpClient()
        try {
            listOf(dashboardRequest(connection, "All", "all") to 0L,
                liveChartRequest(connection) to 1L, dashboardStreamRequest(connection, "All", "all") to 2L,
                balanceRequest(connection) to 3L, alertRequest(connection, 0) to 4L).forEach { (request, endpoint) ->
                val metadata = diagnosticRequestValues(client.newCall(request))
                assertEquals(setOf(DiagnosticField.Endpoint, DiagnosticField.Scope), metadata.keys)
                assertEquals(endpoint, metadata[DiagnosticField.Endpoint])
                assertEquals(diagnosticScope(connection.id).value, metadata[DiagnosticField.Scope])
                assertFalse(metadata.toString().contains("private"))
                assertFalse(metadata.toString().contains(connection.accessKey))
            }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun exportContainsOnlyTypedEventsAndNoExceptionOrForeignText() = inDirectory { dir ->
        val store = DiagnosticStore(File(dir, "private")) { 100000 }
        val secret = "synthetic-secret-key-https://private.invalid/Authorization-Bearer"
        val error = IllegalStateException(secret, java.io.IOException(secret))
        error.stackTrace = arrayOf(
            StackTraceElement("com.shadowpheonix.tradingdashboard.DashboardApi", secret, "DashboardApi.kt", 71),
            StackTraceElement("com.shadowpheonix.tradingdashboard.$secret", secret, secret, 3),
            StackTraceElement(secret, secret, secret, 5))
        store.append(DiagnosticRecord(99000, DiagnosticEvent.Crash, failure = diagnosticFailure(error), frames = diagnosticFrames(error)))
        File(dir, "private/events.jsonl").writeText("{\"time\":99000,\"event\":\"ChartData\",\"url\":\"$secret\"}\n" + secret)
        val zip = File(dir, "export.zip")
        store.export(zip, mapOf(DiagnosticField.Version to 64))
        ZipFile(zip).use { archive ->
            assertEquals(setOf("summary.json", "events.jsonl", "README.txt"), archive.entries().asSequence().map { it.name }.toSet())
            val contents = archive.entries().asSequence().joinToString { archive.getInputStream(it).bufferedReader().readText() }
            assertFalse(contents.contains(secret))
            assertFalse(contents.contains("private.invalid"))
            assertTrue(contents.contains("DashboardApi"))
            assertTrue(contents.contains("71"))
            assertTrue(contents.contains("State"))
        }
    }

    @Test fun rollingFilesRemainBoundedAndSurviveProcessRecreation() = inDirectory { dir ->
        val store = DiagnosticStore(dir) { 20000 }
        repeat(6000) { i -> store.append(DiagnosticRecord(10000 + i.toLong(), DiagnosticEvent.ChartViewport,
            mapOf(DiagnosticField.Visible to (i % 96).toLong()))) }
        assertTrue(File(dir, "events.jsonl").length() <= DiagnosticStore.FILE_LIMIT)
        assertTrue(File(dir, "previous.jsonl").length() <= DiagnosticStore.FILE_LIMIT)
        val records = DiagnosticStore(dir) { 20000 }.records()
        assertTrue(records.size <= DiagnosticStore.MAX_EVENTS)
        assertEquals(15999L, records.last().time)
        assertTrue(records.zipWithNext().all { it.first.time <= it.second.time })
    }

    @Test fun retentionExcludesExpiredAndFutureEventsAndClearRemovesCrashToo() = inDirectory { dir ->
        val now = DiagnosticStore.RETENTION_MS + 10000
        val store = DiagnosticStore(dir) { now }
        store.append(DiagnosticRecord(1, DiagnosticEvent.ProcessStarted))
        store.append(DiagnosticRecord(now + 1, DiagnosticEvent.ProcessStarted))
        store.append(DiagnosticRecord(now, DiagnosticEvent.Crash))
        assertEquals(listOf(DiagnosticEvent.Crash), store.records().map { it.event })
        store.clear()
        assertTrue(DiagnosticStore(dir) { now }.records().isEmpty())
    }

    @Test fun malformedTruncatedAndOversizedFilesCannotEnterExport() = inDirectory { dir ->
        val store = DiagnosticStore(dir) { 1000 }
        store.append(DiagnosticRecord(900, DiagnosticEvent.ProcessStarted))
        File(dir, "events.jsonl").appendText("{truncated\n{\"time\":990,\"event\":\"evil\"}\n")
        assertEquals(1, store.records().size)
        File(dir, "previous.jsonl").writeText("x".repeat(DiagnosticStore.FILE_LIMIT + 1))
        assertEquals(1, store.records().size)
    }

    @Test fun symbolicLinksCannotExportAnUnrelatedFile() = inDirectory { dir ->
        val other = File(dir, "unrelated").apply { writeText("synthetic-sensitive-content") }
        val logdir = File(dir, "logs").apply { mkdirs() }
        Files.createSymbolicLink(File(logdir, "events.jsonl").toPath(), other.toPath())
        assertTrue(DiagnosticStore(logdir) { 1000 }.records().isEmpty())
    }

    @Test fun androidStyleParentDirectoryAliasKeepsValidLogsReadable() = inDirectory { dir ->
        val real = File(dir, "real").apply { mkdirs() }
        val alias = File(dir, "user-storage")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        DiagnosticStore(alias) { 1000 }.append(DiagnosticRecord(900, DiagnosticEvent.ChartData))
        assertEquals(listOf(DiagnosticEvent.ChartData), DiagnosticStore(alias) { 1000 }.records().map { it.event })
    }

    @Test fun crashesAreRetainedAcrossRotationAndFramesAreBounded() = inDirectory { dir ->
        val store = DiagnosticStore(dir) { 20000 }
        store.append(DiagnosticRecord(19000, DiagnosticEvent.Crash))
        repeat(4000) { store.append(DiagnosticRecord(19001 + it / 100L, DiagnosticEvent.ChartData)) }
        assertTrue(store.records().any { it.event == DiagnosticEvent.Crash })
        val error = RuntimeException("synthetic-secret")
        error.stackTrace = Array(200) { StackTraceElement("com.shadowpheonix.tradingdashboard.ModelChartUi", "private", "ModelChartUi.kt", 12) }
        assertEquals(24, diagnosticFrames(error).size)
        assertEquals(DiagnosticFailure.Network, diagnosticFailure(java.io.IOException("secret")))
    }

    @Test fun concurrentWritersKeepCompleteRecords() = inDirectory { dir ->
        val store = DiagnosticStore(dir) { 10000 }
        val threads = (1..4).map { worker -> Thread { repeat(50) {
            store.append(DiagnosticRecord(9000, DiagnosticEvent.ChartData, mapOf(DiagnosticField.Count to worker.toLong())))
        } }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(200, store.records().size)
    }
}

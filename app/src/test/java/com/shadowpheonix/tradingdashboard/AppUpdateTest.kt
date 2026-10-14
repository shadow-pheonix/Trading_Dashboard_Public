package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class AppUpdateTest {
    private fun release(code: Long = 40) = AppRelease(code, "0.1.$code", "com.shadowpheonix.tradingdashboard",
        "Trading-Dashboard-0.1.$code.apk", "f".repeat(64), 1200)

    private class Platform : UpdatePlatform {
        var saved: AppRelease? = null
        var latest = AppRelease(40, "0.1.40", "com.shadowpheonix.tradingdashboard", "Trading-Dashboard-0.1.40.apk", "f".repeat(64), 1200)
        var checks = 0
        var downloads = 0
        var inspections = 0
        var clears = 0
        var result: UpdateDownload = UpdateDownload.Pending(30)
        var fetchFailure = false
        var verifyFailure = false
        override fun savedRelease() = saved
        override suspend fun fetchRelease(): AppRelease { checks++; yield(); if (fetchFailure) error("offline"); return latest }
        override fun enqueue(release: AppRelease) { saved = release; downloads++ }
        override suspend fun inspect(release: AppRelease): UpdateDownload { inspections++; if (verifyFailure) error("invalid APK"); return result }
        override fun clear() { clears++; saved = null }
    }
    private suspend fun settled(controller: AppUpdateController) {
        withTimeout(2000) { while (controller.state.value.checking) yield() }
    }

    private class Clock {
        var now = 0L
        private val ticks = Channel<Unit>(Channel.UNLIMITED)
        suspend fun pause(milliseconds: Long) { assertEquals(1500L, milliseconds); ticks.receive() }
        suspend fun advance(milliseconds: Long) { now += milliseconds; ticks.send(Unit); yield() }
    }

    @Test fun minuteBoundaryDiscoversNewReleaseAndLaterStaysQuietDuringAutomaticChecks() = runBlocking {
        val platform = Platform(); platform.latest = release(36)
        val clock = Clock()
        val controller = AppUpdateController(36, platform, this, { clock.now }, clock::pause)
        controller.opened(1)
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) { controller.monitor() }
        try {
            settled(controller)
            assertEquals(1, platform.checks) // Opening/foreground/manual requests share a check.
            platform.latest = release(41)
            clock.advance(59_999); settled(controller)
            assertEquals(1, platform.checks)
            clock.advance(1); settled(controller)
            assertEquals(2, platform.checks); assertEquals(1, platform.downloads)
            platform.result = UpdateDownload.Verified
            clock.advance(0); settled(controller)
            assertTrue(controller.state.value.prompt)
            controller.later()
            val inspections = platform.inspections
            repeat(3) {
                clock.advance(60_000); settled(controller)
                assertTrue(controller.state.value.ready)
                assertFalse(controller.state.value.prompt)
            }
            assertEquals(5, platform.checks); assertEquals(1, platform.downloads)
            assertEquals(inspections, platform.inspections)
        } finally { monitor.cancelAndJoin() }
    }

    @Test fun offlineOpeningRetriesAutomaticallyAndDownloadsAfterNetworkReturns() = runBlocking {
        val platform = Platform(); platform.fetchFailure = true
        val clock = Clock()
        val controller = AppUpdateController(36, platform, this, { clock.now }, clock::pause)
        controller.opened(1)
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) { controller.monitor() }
        try {
            settled(controller)
            clock.advance(29_999); settled(controller)
            assertEquals(1, platform.checks)
            clock.advance(1); settled(controller)
            assertEquals(2, platform.checks); assertEquals(0, platform.downloads)
            platform.fetchFailure = false
            clock.advance(30_000); settled(controller)
            assertEquals(3, platform.checks); assertEquals(1, platform.downloads)
        } finally { monitor.cancelAndJoin() }
    }

    @Test fun downloadVerificationFailureRetriesWithoutUserPressingCheck() = runBlocking {
        val platform = Platform()
        val clock = Clock()
        val controller = AppUpdateController(36, platform, this, { clock.now }, clock::pause)
        controller.opened(1)
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) { controller.monitor() }
        try {
            settled(controller)
            platform.verifyFailure = true
            clock.advance(0); settled(controller)
            assertEquals(1, platform.clears)
            assertFalse(controller.state.value.ready)
            platform.verifyFailure = false
            clock.advance(29_999); settled(controller)
            assertEquals(1, platform.downloads)
            clock.advance(1); settled(controller)
            assertEquals(2, platform.downloads)
            assertTrue(controller.state.value.downloading)
        } finally { monitor.cancelAndJoin() }
    }

    @Test fun stoppingForegroundMonitorStopsRecurringRequestsAndResumeKeepsLaterDeferred() = runBlocking {
        val platform = Platform()
        val clock = Clock()
        val controller = AppUpdateController(36, platform, this, { clock.now }, clock::pause)
        controller.opened(1)
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) { controller.monitor() }
        settled(controller)
        platform.result = UpdateDownload.Verified
        clock.advance(0); settled(controller)
        controller.later()
        monitor.cancelAndJoin()
        val checks = platform.checks
        clock.advance(600_000)
        assertEquals(checks, platform.checks)
        val resumed = launch(start = CoroutineStart.UNDISPATCHED) { controller.monitor() }
        try {
            settled(controller)
            assertEquals(checks + 1, platform.checks)
            assertTrue(controller.state.value.ready); assertFalse(controller.state.value.prompt)
            assertEquals(1, platform.downloads)
        } finally { resumed.cancelAndJoin() }
    }

    @Test fun foregroundReturnDetectsReleasePublishedDuringSameTaskWithoutManualCheck() = runBlocking {
        val platform = Platform(); platform.latest = release(36)
        val controller = AppUpdateController(36, platform, this)
        controller.opened(1); settled(controller)
        val firstVisit = launch(start = CoroutineStart.UNDISPATCHED) { controller.monitor() }
        settled(controller)
        firstVisit.cancelAndJoin()
        val before = platform.checks
        platform.latest = release(41)
        // Android keeps the same Activity/task when returning from the background.
        controller.opened(1)
        val returned = launch(start = CoroutineStart.UNDISPATCHED) { controller.monitor() }
        try {
            settled(controller)
            assertEquals(before + 1, platform.checks)
            assertTrue(controller.state.value.downloading)
            assertEquals(1, platform.downloads)
        } finally { returned.cancelAndJoin() }
    }

    @Test fun publicManifestRequestNeverCarriesDashboardCredentialsAndDisallowsTraversal() {
        val request = updateRequest(DASHBOARD_URL)
        assertEquals("$DASHBOARD_URL/app-updates/latest.json", request.url.toString())
        assertEquals("no-cache", request.header("Cache-Control"))
        assertNull(request.header("Authorization"))
        assertNull(updateRequest(DASHBOARD_URL, release().apk).header("Authorization"))
        for (file in listOf("../config.json", "https://evil.test/x.apk", "Trading-Dashboard-0.1.0.apk", "latest.json?key=x"))
            assertThrows(IllegalArgumentException::class.java) { updateRequest(DASHBOARD_URL, file) }
    }

    @Test fun invalidManifestsCannotStartDownloads() {
        for (value in listOf(release().copy(apk = "../app.apk"), release().copy(versionName = "0.1.39"),
            release().copy(packageName = "other.app"), release().copy(size = MAX_APK_BYTES + 1),
            release().copy(sha256 = "invalid"), release(0)))
            assertThrows(IllegalArgumentException::class.java) { value.validate() }
        assertThrows(IllegalArgumentException::class.java) { readUpdateManifest(ByteArrayInputStream(ByteArray(16385))) }
        val text = dashboardJson.encodeToString(AppRelease.serializer(), release())
        assertEquals(release(), readUpdateManifest(ByteArrayInputStream(text.toByteArray())))
    }

    @Test fun installerRequiresExactPackageNewerVersionAndPermanentSigner() {
        val current = ApkIdentity(release().packageName, 36, setOf("permanent-cert"))
        val archive = current.copy(versionCode = 40)
        validateUpdateIdentity(release(), current, archive)
        for (bad in listOf(archive.copy(packageName = "other.app"), archive.copy(versionCode = 35),
            archive.copy(versionCode = 39), archive.copy(signers = setOf("attacker-cert")),
            archive.copy(signers = emptySet()), archive.copy(signers = setOf("permanent-cert", "other-cert"))))
            assertThrows(IllegalArgumentException::class.java) { validateUpdateIdentity(release(), current, bad) }
        assertThrows(IllegalArgumentException::class.java) { validateUpdateIdentity(release(), current.copy(versionCode = 40), archive) }
    }

    @Test fun automaticDownloadCheckSharedAcrossRotationTabsAndIdle() = runBlocking {
        val platform = Platform()
        val controller = AppUpdateController(36, platform, this)
        controller.opened(1); controller.check(); controller.opened(1)
        settled(controller)
        assertEquals(1, platform.checks); assertEquals(1, platform.downloads)
        assertTrue(controller.state.value.downloading)
        controller.poll()
        assertEquals(30, controller.state.value.progress)
        platform.result = UpdateDownload.Verified
        controller.poll()
        assertTrue(controller.state.value.ready); assertTrue(controller.state.value.prompt)
        repeat(10) { controller.poll(); controller.opened(1) }
        assertEquals(1, platform.checks); assertEquals(2, platform.inspections)
    }

    @Test fun laterSuppressesUntilNextRestartAndNeverDownloadsVerifiedApkAgain() = runBlocking {
        val platform = Platform()
        val controller = AppUpdateController(36, platform, this)
        controller.opened(1); settled(controller)
        platform.result = UpdateDownload.Verified; controller.poll()
        controller.later(); controller.opened(1); controller.check(); settled(controller)
        assertTrue(controller.state.value.ready); assertFalse(controller.state.value.prompt)
        assertEquals(1, platform.downloads)
        controller.closed(); controller.opened(1); settled(controller)
        assertTrue(controller.state.value.prompt)
        controller.later()
        val restarted = AppUpdateController(36, platform, this)
        restarted.opened(2); settled(restarted)
        assertTrue(restarted.state.value.prompt); assertEquals(1, platform.downloads)
    }

    @Test fun downloadFinishedWhileClosedIsRestoredBeforeOfflineCheck() = runBlocking {
        val platform = Platform()
        platform.saved = release(); platform.result = UpdateDownload.Verified; platform.fetchFailure = true
        val controller = AppUpdateController(36, platform, this)
        controller.opened(2); settled(controller)
        assertTrue(controller.state.value.ready); assertTrue(controller.state.value.prompt)
        assertEquals(0, platform.downloads)
    }

    @Test fun interruptedDownloadContinuesWithoutDuplicatesAndVerificationFailureCanRetry() = runBlocking {
        val platform = Platform(); platform.saved = release()
        val controller = AppUpdateController(36, platform, this)
        controller.opened(2); settled(controller)
        assertEquals(0, platform.downloads); assertTrue(controller.state.value.downloading)
        platform.verifyFailure = true; controller.poll()
        assertFalse(controller.state.value.ready); assertFalse(controller.state.value.prompt)
        assertEquals(1, platform.clears)
        platform.verifyFailure = false; controller.check(); settled(controller)
        assertEquals(1, platform.downloads); assertTrue(controller.state.value.downloading)
    }

    @Test fun installedUpdateClearsOldDownloadAndProducesZeroIdleRequests() = runBlocking {
        val platform = Platform(); platform.saved = release()
        val controller = AppUpdateController(40, platform, this)
        controller.opened(2); settled(controller)
        repeat(10) { controller.poll() }
        assertEquals(1, platform.clears); assertEquals(1, platform.checks)
        assertEquals(0, platform.downloads); assertEquals(0, platform.inspections)
        assertFalse(controller.state.value.ready)
    }

    @Test fun rollbackManifestCannotReplaceNewerPendingRelease() = runBlocking {
        val platform = Platform(); platform.saved = release(41); platform.latest = release(40)
        val controller = AppUpdateController(36, platform, this)
        controller.opened(2); settled(controller)
        assertEquals(41L, controller.state.value.release!!.versionCode)
        assertEquals(0, platform.downloads)
    }
}

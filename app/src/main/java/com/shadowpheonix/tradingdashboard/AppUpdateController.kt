package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface UpdateDownload {
    data class Pending(val progress: Int?) : UpdateDownload
    data object Verified : UpdateDownload
}

interface UpdatePlatform {
    fun savedRelease(): AppRelease?
    suspend fun fetchRelease(): AppRelease
    fun enqueue(release: AppRelease)
    suspend fun inspect(release: AppRelease): UpdateDownload
    fun clear()
}

// Tested independently of Android. The platform owns persistent downloads and APK verification.
class AppUpdateController(private val installedVersion: Long, private val platform: UpdatePlatform,
                          private val scope: CoroutineScope,
                          private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
                          private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private val mutex = Mutex()
    private val reminder = UpdateReminder()
    private val mutable = MutableStateFlow(AppUpdateState())
    val state = mutable.asStateFlow()
    private var task: Int? = null
    private var nextCheckAt = Long.MIN_VALUE

    fun opened(taskId: Int) {
        reminder.opened(taskId)
        if (task != taskId) {
            task = taskId
            mutable.value = mutable.value.copy(prompt = reminder.shouldPrompt(mutable.value.ready))
            check()
        }
    }
    fun closed() { task = null; reminder.closed() }
    fun later() { reminder.later(); mutable.value = mutable.value.copy(prompt = false) }
    fun requestInstall() { mutable.value = mutable.value.copy(prompt = false, message = null) }
    fun installError() { mutable.value = mutable.value.copy(message = "Installation could not open. Try Install update again.") }
    fun permissionDenied() { mutable.value = mutable.value.copy(message = "Allow app updates in Android's Install unknown apps setting, then tap Install update.") }

    fun check() {
        if (mutable.value.checking) return
        mutable.value = mutable.value.copy(checking = true, message = null)
        scope.launch {
            mutex.withLock {
                var retry = false
                try {
                    val saved = platform.savedRelease()?.validate()
                    if (saved != null && saved.versionCode <= installedVersion) {
                        platform.clear(); mutable.value = AppUpdateState(checking = true)
                    } else if (saved != null) {
                        // Keep a verified/deferred prompt stable during routine discovery.
                        // Reverification still happens immediately before installation.
                        if (!mutable.value.ready || mutable.value.release != saved) {
                            mutable.value = mutable.value.copy(downloading = true, ready = false,
                                prompt = false, progress = null, release = saved)
                            inspect()
                        }
                    }
                    val release = platform.fetchRelease().validate()
                    if (release.versionCode <= installedVersion) {
                        if (!mutable.value.downloading && !mutable.value.ready)
                            mutable.value = mutable.value.copy(message = "You're up to date.")
                    } else if (release.versionCode > (mutable.value.release?.versionCode ?: 0) ||
                        (!mutable.value.ready && !mutable.value.downloading)) {
                        platform.enqueue(release)
                        mutable.value = AppUpdateState(checking = true, downloading = true, release = release)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    retry = true
                    mutable.value = mutable.value.copy(message = "Update check unavailable. We'll retry automatically while the app is open.")
                } finally {
                    nextCheckAt = clockMillis() + if (retry) RETRY_INTERVAL_MS else CHECK_INTERVAL_MS
                    mutable.value = mutable.value.copy(checking = false)
                }
            }
        }
    }

    suspend fun poll() {
        if (mutable.value.downloading && !mutable.value.checking) mutex.withLock { inspect() }
    }

    // Activity lifecycle owns this loop: returning to the foreground checks again,
    // tabs do not restart it, and backgrounding cancels all recurring discovery.
    // DownloadManager can continue an existing download while the app is hidden.
    suspend fun monitor() {
        check()
        while (true) {
            if (clockMillis() >= nextCheckAt) check()
            poll()
            pause(1500)
        }
    }

    private suspend fun inspect() {
        val release = mutable.value.release ?: return
        try {
            when (val download = platform.inspect(release)) {
                UpdateDownload.Verified -> mutable.value = mutable.value.copy(downloading = false, ready = true,
                    progress = null, prompt = reminder.shouldPrompt(true))
                is UpdateDownload.Pending -> mutable.value = mutable.value.copy(progress = download.progress)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            try { platform.clear() } catch (_: Exception) { /* A later check retries cleanup. */ }
            nextCheckAt = clockMillis() + RETRY_INTERVAL_MS
            mutable.value = AppUpdateState(checking = mutable.value.checking,
                message = "Update download or verification failed. We'll retry automatically while the app is open.")
        }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L
        const val RETRY_INTERVAL_MS = 30_000L
    }
}

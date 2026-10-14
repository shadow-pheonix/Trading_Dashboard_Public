package com.shadowpheonix.tradingdashboard

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val platform = AndroidUpdatePlatform(application)
    private val controller = AppUpdateController(BuildConfig.VERSION_CODE.toLong(), platform, viewModelScope)
    val state = controller.state
    fun opened(taskId: Int) = controller.opened(taskId)
    fun closed() = controller.closed()
    fun check() = controller.check()
    fun later() = controller.later()
    fun requestInstall() = controller.requestInstall()
    fun installError() = controller.installError()
    fun permissionDenied() = controller.permissionDenied()
    suspend fun monitor() = controller.monitor()
    suspend fun installFile(): File {
        val release = state.value.release ?: error("No pending update")
        check(state.value.ready)
        return platform.installFile(release)
    }
}

private class AndroidUpdatePlatform(private val context: Application) : UpdatePlatform {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val directory get() = File(context.filesDir, "app-updates")
    private val verified get() = File(directory, "verified.apk")
    private val connections = ConnectionStore(context)
    private fun serverUrl(): String = connections.load().firstOrNull()?.baseUrl
        ?: throw IllegalStateException("Add a dashboard connection before checking for updates.")
    private fun downloadFile(release: AppRelease) = File(requireNotNull(context.getExternalFilesDir(null)), "app-updates/${release.apk}")

    override suspend fun fetchRelease(): AppRelease = withContext(Dispatchers.IO) {
        client.newCall(updateRequest(serverUrl())).execute().use { response ->
            check(response.isSuccessful)
            val body = response.body ?: error("Missing manifest")
            check(body.contentLength() <= 16 * 1024)
            readUpdateManifest(body.byteStream())
        }
    }

    override fun savedRelease(): AppRelease? {
        val text = preferences.getString("release", null) ?: return null
        return try { dashboardJson.decodeFromString<AppRelease>(text).validate() }
            catch (_: Exception) { clear(); null }
    }

    override fun enqueue(release: AppRelease) {
        val previous = preferences.getLong("download", -1)
        if (previous >= 0) manager.remove(previous)
        verified.delete()
        val file = downloadFile(release)
        file.parentFile?.mkdirs()
        file.parentFile?.listFiles()?.forEach { it.delete() }
        val request = DownloadManager.Request(Uri.parse(updateRequest(serverUrl(), release.apk).url.toString()))
            .setTitle("Trading Dashboard ${release.versionName}")
            .setDescription("Downloading app update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(file))
        val id = manager.enqueue(request)
        try {
            check(preferences.edit().putString("release", dashboardJson.encodeToString(AppRelease.serializer(), release))
                .putLong("download", id).commit())
        } catch (failure: Exception) { manager.remove(id); throw failure }
    }

    override suspend fun inspect(release: AppRelease): UpdateDownload {
        if (verified.isFile) {
            try { withContext(Dispatchers.IO) { verify(release, verified) }; return UpdateDownload.Verified }
            catch (_: Exception) { verified.delete() }
        }

        val id = preferences.getLong("download", -1)
        return manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            check(cursor.moveToFirst())
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            if (bytes > release.size) { manager.remove(id); error("Oversized download") }
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    withContext(Dispatchers.IO) {
                        val source = downloadFile(release)
                        check(source.length() == release.size)
                        directory.mkdirs()
                        source.copyTo(verified, overwrite = true)
                        verify(release, verified)
                    }
                    // Retain only the private verified copy. Later survives app/process restarts.
                    manager.remove(id)
                    check(preferences.edit().remove("download").commit())
                    UpdateDownload.Verified
                }
                DownloadManager.STATUS_FAILED -> error("Download failed")
                else -> UpdateDownload.Pending(((bytes * 100 / release.size).toInt()).coerceIn(0, 100))
            }
        }
    }

    override fun clear() {
        val id = preferences.getLong("download", -1)
        if (id >= 0) manager.remove(id)
        verified.delete()
        context.getExternalFilesDir(null)?.let { File(it, "app-updates").listFiles()?.forEach { file -> file.delete() } }
        check(preferences.edit().clear().commit())
    }

    // Recheck the private APK immediately before exposing it to Android's installer.
    suspend fun installFile(release: AppRelease): File = withContext(Dispatchers.IO) {
        verify(release, verified)
        verified
    }

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return ApkIdentity(info.packageName, if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signatures?.map { sha256(it.toByteArray()) }?.toSet() ?: emptySet())
    }

    @Suppress("DEPRECATION")
    private fun verify(release: AppRelease, file: File) {
        check(file.isFile && file.length() == release.size)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val size = input.read(bytes); if (size < 0) break; digest.update(bytes, 0, size) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("Invalid APK")
        validateUpdateIdentity(release, identity(installed), identity(archive))
    }
}

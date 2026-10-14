package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable
import okhttp3.Request
import java.security.MessageDigest
import java.io.InputStream
import java.io.ByteArrayOutputStream

const val UPDATE_PATH = "/app-updates/"
const val MAX_APK_BYTES = 100L * 1024 * 1024

@Serializable
data class AppRelease(val versionCode: Long, val versionName: String, val packageName: String,
                      val apk: String, val sha256: String, val size: Long) {
    fun validate(): AppRelease {
        require(versionCode in 1..Int.MAX_VALUE.toLong())
        require(versionName == "0.1.$versionCode")
        require(packageName == "com.shadowpheonix.tradingdashboard")
        require(apk == "Trading-Dashboard-$versionName.apk")
        require(sha256.matches(Regex("[a-f0-9]{64}")))
        require(size in 1..MAX_APK_BYTES)
        return this
    }
}

// Separate public client: dashboard authorization is never attached to update requests.
fun updateRequest(baseUrl: String, file: String = "latest.json"): Request {
    require(file == "latest.json" || file.matches(Regex("Trading-Dashboard-0\\.1\\.[1-9][0-9]*\\.apk")))
    return Request.Builder().url(validatedBaseUrl(baseUrl).newBuilder()
        .encodedPath(UPDATE_PATH + file).build()).apply {
            if (file == "latest.json") header("Cache-Control", "no-cache")
        }.get().build()
}

data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

fun validateUpdateIdentity(release: AppRelease, installed: ApkIdentity, archive: ApkIdentity) {
    release.validate()
    require(archive.packageName == installed.packageName && archive.packageName == release.packageName)
    require(archive.versionCode == release.versionCode && archive.versionCode > installed.versionCode)
    require(installed.signers.isNotEmpty() && archive.signers == installed.signers)
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

fun readUpdateManifest(input: InputStream): AppRelease {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= 16 * 1024)
        output.write(buffer, 0, count)
    }
    return dashboardJson.decodeFromString<AppRelease>(output.toString("UTF-8")).validate()
}

// Later suppresses automatic prompts for this opening, including rotations and tab changes.
// The verified APK remains on disk; a fresh app opening gets a fresh reminder session.
class UpdateReminder {
    private var task: Int? = null
    private var deferred = false
    fun opened(taskId: Int) { if (task != taskId) { task = taskId; deferred = false } }
    fun later() { deferred = true }
    fun shouldPrompt(ready: Boolean): Boolean = ready && !deferred
    fun closed() { task = null; deferred = false }
}

data class AppUpdateState(val checking: Boolean = false, val downloading: Boolean = false,
                          val progress: Int? = null, val release: AppRelease? = null,
                          val ready: Boolean = false, val prompt: Boolean = false,
                          val message: String? = null)

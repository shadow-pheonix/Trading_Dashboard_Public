package com.shadowpheonix.tradingdashboard

import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import java.security.MessageDigest
import java.io.InputStream
import java.io.ByteArrayOutputStream

const val UPDATE_REPOSITORY = "shadow-pheonix/Trading_Dashboard_Public"
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
fun updateRequest(file: String = "latest.json"): Request {
    require(file == "latest.json" || file.matches(Regex("Trading-Dashboard-0\\.1\\.[1-9][0-9]*\\.apk")))
    val path = if (file == "latest.json") "latest/download/latest.json" else {
        val version = file.removePrefix("Trading-Dashboard-").removeSuffix(".apk")
        "download/v$version/$file"
    }
    return Request.Builder().url("https://github.com/$UPDATE_REPOSITORY/releases/$path").apply {
        if (file == "latest.json") header("Cache-Control", "no-cache")
    }.get().build()
}

private fun githubDownloadUrl(url: HttpUrl): Boolean = url.isHttps &&
    url.username.isEmpty() && url.password.isEmpty() &&
    url.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")

internal fun updateRedirect(request: Request, location: String): Request {
    require(request.header("Authorization") == null && request.header("Cookie") == null)
    val url = request.url.resolve(location) ?: throw IllegalArgumentException("Invalid update redirect.")
    require(githubDownloadUrl(request.url) && githubDownloadUrl(url)) { "Invalid update redirect." }
    return request.newBuilder().url(url).get().build()
}

// GitHub release assets redirect to GitHub's HTTPS CDN. Validate each hop before
// issuing it; dashboard credentials and saved server addresses are never used.
fun fetchUpdateManifest(client: OkHttpClient): AppRelease {
    require(!client.followRedirects && !client.followSslRedirects)
    var request = updateRequest()
    repeat(6) {
        client.newCall(request).execute().use { response ->
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                request = updateRedirect(request, response.header("Location")
                    ?: throw IllegalArgumentException("Missing update redirect."))
            } else {
                check(response.isSuccessful)
                val body = response.body ?: error("Missing manifest")
                check(body.contentLength() <= 16 * 1024)
                return readUpdateManifest(body.byteStream())
            }
        }
    }
    throw IllegalStateException("Too many update redirects.")
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

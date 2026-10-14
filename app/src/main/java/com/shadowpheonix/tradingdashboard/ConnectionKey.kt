package com.shadowpheonix.tradingdashboard

import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ConnectionKeyPayload(val url: String, val key: String) {
    override fun toString() = "ConnectionKeyPayload(redacted)"
}
private val connectionKeyJson = Json { ignoreUnknownKeys = false }

internal fun validAccessKey(value: String): Boolean = value.length in 32..256 &&
    value.all { it.code in 33..126 }

// The address comes only from the owner's private connection key, never from the APK.
fun connectionFromKey(id: String, raw: String): Connection {
    try {
        val text = raw.trim()
        require(text.length in 5..2048 && text.startsWith("TD1."))
        val encoded = text.removePrefix("TD1.")
        require(encoded.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' })
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded)
        val payload = connectionKeyJson.decodeFromString<ConnectionKeyPayload>(
            bytes.toString(Charsets.UTF_8))
        val url = validatedBaseUrl(payload.url)
        require(url.encodedPath == "/" && validAccessKey(payload.key))
        return Connection(id, "", url.toString(), payload.key)
    } catch (_: Exception) {
        // Decoder/URL exceptions can contain the private address or token.
        throw IllegalArgumentException("Enter the full connection key beginning TD1. Ask the dashboard owner for a connection key.")
    }
}

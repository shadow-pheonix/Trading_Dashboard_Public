package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface MacdCache {
    suspend fun load(connection: Connection): MacdSnapshot?
    suspend fun save(connection: Connection, value: MacdSnapshot)
    suspend fun forget(id: String)
}
@Serializable
private data class SavedMacd(val scope: String, val value: MacdSnapshot)

/** Private app storage: no credentials or account addresses are serialized. */
class MacdCacheStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) : MacdCache {
    private val json = Json { ignoreUnknownKeys = false }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun file(id: String) = File(directory, digest(id) + ".json")
    private fun scope(connection: Connection) = digest("${connection.id}\u0000${connection.baseUrl}\u0000${connection.accessKey}")
    override suspend fun load(connection: Connection): MacdSnapshot? = withContext(Dispatchers.IO) {
        try {
            val path = file(connection.id)
            if (!path.isFile || path.length() !in 1L..262144L) return@withContext null
            val saved = json.decodeFromString<SavedMacd>(path.readText())
            if (saved.scope != scope(connection)) return@withContext null
            validateMacd(saved.value)
            saved.value.takeIf { it.models.isNotEmpty() && it.generatedAt <= now() + 60_000 }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    override suspend fun save(connection: Connection, value: MacdSnapshot) = withContext(Dispatchers.IO) {
        validateMacd(value)
        if (value.models.isEmpty() || value.generatedAt > now() + 60_000) return@withContext
        directory.mkdirs()
        val contents = json.encodeToString(SavedMacd(scope(connection), value))
        require(contents.toByteArray().size <= 262144)
        val temporary = File.createTempFile("macd-", ".tmp", directory)
        try {
            temporary.writeText(contents)
            Files.move(temporary.toPath(), file(connection.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        Unit
    }
    override suspend fun forget(id: String) { withContext(Dispatchers.IO) { file(id).delete(); Unit } }
}

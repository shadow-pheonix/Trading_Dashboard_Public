package com.shadowpheonix.tradingdashboard

import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ConnectionKeyTest {
    private val token = "x".repeat(43)
    private fun encode(url: String = DASHBOARD_URL, key: String = token, extra: Boolean = false): String {
        val json = buildJsonObject {
            put("url", url); put("key", key)
            if (extra) put("unexpected", "field")
        }.toString()
        return "TD1." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    @Test fun privateKeyRoutesEveryDashboardRequestToItsOwnServer() {
        for (url in listOf(DASHBOARD_URL, "https://second.example.test:8443")) {
            val code = encode(url)
            val connection = connectionFromKey("test", "  $code\n")
            assertEquals(url + "/", connection.baseUrl)
            assertEquals(token, connection.accessKey)
            assertEquals("test", connection.id)
            assertFalse(connection.toString().contains(token))
            assertFalse(connection.toString().contains("example.test"))
            for (request in listOf(dashboardRequest(connection, "All", "all"),
                dashboardStreamRequest(connection, "All", "M1"), balanceRequest(connection), macdRequest(connection))) {
                assertEquals(validatedBaseUrl(url).host, request.url.host)
                assertEquals(validatedBaseUrl(url).port, request.url.port)
                assertEquals("Bearer $token", request.header("Authorization"))
                assertFalse(request.url.toString().contains(code))
                assertFalse(request.url.toString().contains(token))
            }
            assertEquals(url + "/app-updates/latest.json", updateRequest(connection.baseUrl).url.toString())
            assertNull(updateRequest(connection.baseUrl).header("Authorization"))
        }
    }

    @Test fun oldSavedConnectionsKeepTheirPrivateOriginAndRawAccessToken() {
        val saved = listOf(Connection("old", "Main", DASHBOARD_URL, token),
            Connection("other", "Other", "https://second.example.test", "y".repeat(43)))
        assertEquals(saved, migrateConnections(saved))
        assertEquals(saved, migrateConnections(migrateConnections(saved)))
        assertEquals("second.example.test", balanceRequest(saved.last()).url.host)
    }

    @Test fun invalidCodesNeverRepeatPrivateContentsInErrors() {
        val invalid = listOf(token, "TD2." + encode().removePrefix("TD1."), "TD1.", "TD1.!", encode() + "=",
            "TD1." + "a".repeat(2048), encode(extra = true), encode(key = "short"), encode(key = "x".repeat(257)),
            encode(key = "x".repeat(40) + "\n"), encode(key = "é".repeat(43))) +
            listOf("http://dashboard.example.test", "https://user:password@dashboard.example.test",
                "$DASHBOARD_URL/path", "$DASHBOARD_URL?", "$DASHBOARD_URL#", "$DASHBOARD_URL:65536").map { encode(it) }
        for (code in invalid) {
            val error = assertThrows(IllegalArgumentException::class.java) { connectionFromKey("test", code) }
            assertFalse(error.message.orEmpty().contains("example.test"))
            assertFalse(error.message.orEmpty().contains(token))
            assertEquals("Enter the full connection key beginning TD1. Ask the dashboard owner for a connection key.", error.message)
        }
    }
}

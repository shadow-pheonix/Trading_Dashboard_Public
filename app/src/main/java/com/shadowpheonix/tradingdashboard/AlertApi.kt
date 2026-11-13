package com.shadowpheonix.tradingdashboard

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

fun alertRequest(connection: Connection, after: Long): Request {
    require(after >= 0)
    val request = dashboardRequest(connection, "All", "all")
    return request.newBuilder().removeHeader("X-Dashboard-Margin").header("X-Dashboard-Pnl", "1").url(request.url.newBuilder().encodedPath("/v2/alerts/live")
        .query(null).addQueryParameter("after", after.toString()).build()).build()
}

class AlertApi {
    private val client = OkHttpClient.Builder().eventListenerFactory { DiagnosticHttpListener() }.followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        // The server owns the keepalive. OkHttp automatically replies to pings.
        .pingInterval(0, TimeUnit.SECONDS).build()
    fun stream(connection: Connection, cursor: Long) = callbackFlow {
        val socket = client.newWebSocket(alertRequest(connection, cursor), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AppDiagnostics.record(DiagnosticEvent.StreamOpened, mapOf(DiagnosticField.Endpoint to 4L, DiagnosticField.Scope to diagnosticScope(connection.id).value))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    require(text.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
                    val batch = dashboardJson.decodeFromString<AlertBatch>(text).validate()
                    AppDiagnostics.record(DiagnosticEvent.AlertBatch, mapOf(DiagnosticField.Count to batch.events.size.toLong(), DiagnosticField.Scope to diagnosticScope(connection.id).value), throttle = true)
                    if (!trySend(batch).isSuccess) throw IllegalStateException()
                } catch (error: Exception) { AppDiagnostics.record(DiagnosticEvent.InvalidData, mapOf(DiagnosticField.Endpoint to 4L), error); webSocket.cancel(); close(DashboardError("Invalid alert feed")) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                webSocket.cancel(); close(DashboardError("Invalid alert feed"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                AppDiagnostics.record(DiagnosticEvent.StreamFailed, mapOf(DiagnosticField.Endpoint to 4L, DiagnosticField.Scope to diagnosticScope(connection.id).value), t)
                response?.close(); close(DashboardError("Alert connection lost"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                AppDiagnostics.record(DiagnosticEvent.StreamClosed, mapOf(DiagnosticField.Endpoint to 4L, DiagnosticField.Code to code.toLong()))
                close(DashboardError("Alert connection closed")) }
        })
        awaitClose { socket.cancel() }
    }
    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

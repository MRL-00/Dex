package com.remodex.android.core.network

import android.util.Log
import com.remodex.android.core.model.RelayConnectionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

sealed interface RelaySocketTerminalEvent {
    data class Closed(val code: Int, val reason: String) : RelaySocketTerminalEvent
    data class Failure(val message: String, val responseCode: Int?) : RelaySocketTerminalEvent
}

class RelayWebSocketClient(private val okHttpClient: OkHttpClient) {
    private var socket: WebSocket? = null
    @Volatile
    var lastTerminalEvent: RelaySocketTerminalEvent? = null
        private set

    /**
     * Generation counter to discard stale callbacks from a previously closed socket.
     * Incremented each time [connect] is called so that onClosed/onFailure from the
     * old socket are ignored instead of polluting the new connection's state.
     */
    private val connectionGeneration = AtomicLong(0)

    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val incoming = _incoming.asSharedFlow()

    private val _terminalEvents = MutableSharedFlow<RelaySocketTerminalEvent>(extraBufferCapacity = 16)
    val terminalEvents = _terminalEvents.asSharedFlow()

    private val _connectionState = MutableStateFlow(RelayConnectionState.OFFLINE)
    val connectionState = _connectionState.asStateFlow()

    suspend fun connect(url: String, role: String) {
        disconnect()
        lastTerminalEvent = null
        _connectionState.value = RelayConnectionState.CONNECTING

        val gen = connectionGeneration.incrementAndGet()
        val opened = CompletableDeferred<Unit>()
        val redactedUrl = redactRelayUrl(url)
        val request = Request.Builder()
            .url(url)
            .header("x-role", role)
            .build()
        Log.d("Remodex", "relay websocket opening role=$role url=$redactedUrl")
        socket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (connectionGeneration.get() != gen) return
                    _connectionState.value = RelayConnectionState.CONNECTING
                    Log.d("Remodex", "relay websocket open url=$redactedUrl")
                    opened.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (connectionGeneration.get() != gen) return
                    _incoming.tryEmit(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (connectionGeneration.get() != gen) return
                    _connectionState.value = RelayConnectionState.OFFLINE
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (connectionGeneration.get() != gen) {
                        Log.d("Remodex", "ignoring stale onClosed from previous connection gen")
                        return
                    }
                    _connectionState.value = RelayConnectionState.OFFLINE
                    Log.w("Remodex", "relay websocket closed code=$code reason=${reason.ifBlank { "none" }}")
                    val event = RelaySocketTerminalEvent.Closed(code, reason)
                    lastTerminalEvent = event
                    _terminalEvents.tryEmit(event)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (connectionGeneration.get() != gen) {
                        Log.d("Remodex", "ignoring stale onFailure from previous connection gen")
                        if (!opened.isCompleted) {
                            opened.completeExceptionally(t)
                        }
                        return
                    }
                    if (!opened.isCompleted) {
                        opened.completeExceptionally(t)
                    }
                    _connectionState.value = RelayConnectionState.OFFLINE
                    Log.e("Remodex", "relay websocket failed response=${response?.code ?: "none"} message=${t.message}", t)
                    val event = RelaySocketTerminalEvent.Failure(t.message ?: "WebSocket failed", response?.code)
                    lastTerminalEvent = event
                    _terminalEvents.tryEmit(event)
                }
            },
        )
        opened.await()
    }

    fun send(text: String): Boolean = socket?.send(text) == true

    fun disconnect() {
        val old = socket
        socket = null
        _connectionState.value = RelayConnectionState.OFFLINE
        // Cancel immediately rather than graceful close to avoid stale onClosed callbacks
        // racing with the next connect() call.
        old?.cancel()
    }

    private fun redactRelayUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return "relay=[redacted]"
        val encodedPath = parsed.encodedPath
        val redactedPath = if (encodedPath.isBlank() || encodedPath == "/") {
            encodedPath
        } else {
            encodedPath.substringBeforeLast("/", missingDelimiterValue = "").ifBlank { "" } + "/[session]"
        }
        val defaultPort = if (parsed.scheme == "https" || parsed.scheme == "wss") 443 else 80
        val port = if (parsed.port != defaultPort) ":${parsed.port}" else ""
        return "${parsed.scheme}://${parsed.host}$port$redactedPath"
    }
}

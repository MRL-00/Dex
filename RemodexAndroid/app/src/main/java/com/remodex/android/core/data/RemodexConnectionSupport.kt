package com.remodex.android.core.data

import com.remodex.android.core.model.PhoneIdentityState
import com.remodex.android.core.model.SavedRelaySession
import com.remodex.android.core.model.TrustedSessionResolveRequest
import com.remodex.android.core.model.TrustedSessionResolveResponse
import com.remodex.android.core.network.RelaySocketTerminalEvent
import com.remodex.android.core.security.AndroidSecureStorage
import com.remodex.android.core.security.SecureTransportCrypto
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal suspend fun tryResolvedTrustedSession(
    savedSession: SavedRelaySession,
    storage: AndroidSecureStorage,
    phoneIdentity: PhoneIdentityState,
    okHttpClient: OkHttpClient,
    json: Json,
): SavedRelaySession? = withContext(Dispatchers.IO) {
    val trustedRegistry = storage.readTrustedRegistry()
    val trustedMac = trustedRegistry.records[savedSession.macDeviceId] ?: return@withContext null
    val relayUrl = trustedMac.relayUrl ?: return@withContext null
    val nonce = UUID.randomUUID().toString()
    val timestamp = System.currentTimeMillis()
    val transcript = SecureTransportCrypto.buildTrustedSessionResolveTranscript(
        macDeviceId = trustedMac.macDeviceId,
        phoneDeviceId = phoneIdentity.phoneDeviceId,
        phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
        nonce = nonce,
        timestamp = timestamp,
    )
    val request = TrustedSessionResolveRequest(
        macDeviceId = trustedMac.macDeviceId,
        phoneDeviceId = phoneIdentity.phoneDeviceId,
        phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
        nonce = nonce,
        timestamp = timestamp,
        signature = SecureTransportCrypto.signEd25519(phoneIdentity.phoneIdentityPrivateKey, transcript),
    )
    val resolveUrl = trustedSessionResolveUrl(relayUrl) ?: return@withContext null
    val httpRequest = Request.Builder()
        .url(resolveUrl)
        .post(json.encodeToString(TrustedSessionResolveRequest.serializer(), request).toRequestBody("application/json".toMediaType()))
        .build()
    val response = okHttpClient.newCall(httpRequest).execute()
    val body = response.body?.string().orEmpty()
    if (!response.isSuccessful) {
        return@withContext null
    }
    val resolved = runCatching {
        json.decodeFromString(TrustedSessionResolveResponse.serializer(), body)
    }.getOrNull() ?: return@withContext null
    val updated = savedSession.copy(
        relay = relayUrl,
        sessionId = resolved.sessionId,
        macIdentityPublicKey = resolved.macIdentityPublicKey,
    )
    storage.writeRelaySession(updated)
    return@withContext updated
}

internal fun websocketSessionUrl(savedSession: SavedRelaySession): String {
    val relay = savedSession.relay.trim().trimEnd('/')
        .replaceFirst("http://", "ws://")
        .replaceFirst("https://", "wss://")
    return "$relay/${savedSession.sessionId}"
}

internal fun trustedSessionResolveUrl(relayUrl: String): String? {
    val parsed = relayUrl.trim()
        .replaceFirst("ws://", "http://")
        .replaceFirst("wss://", "https://")
        .toHttpUrlOrNull() ?: return null
    val pathSegments = parsed.pathSegments.filter { it.isNotBlank() }
    val builder = parsed.newBuilder()
    builder.encodedPath(
        if (pathSegments.lastOrNull() == "relay") {
            "/" + (pathSegments.dropLast(1) + listOf("v1", "trusted", "session", "resolve")).joinToString("/")
        } else {
            "/v1/trusted/session/resolve"
        },
    )
    return builder.build().toString()
}

internal fun RelaySocketTerminalEvent.toUserFacingError(): String = when (this) {
    is RelaySocketTerminalEvent.Closed -> when (code) {
        4001 -> "The relay rejected this phone because the Mac bridge is not connected for the scanned session. Restart the bridge and scan a fresh QR code."
        4002 -> "The Mac bridge is temporarily unavailable for this relay session. Try reconnecting."
        4003 -> "The relay says this pairing session is no longer valid. Scan a fresh QR code."
        4004 -> "The relay disconnected while the Mac was temporarily absent. Try reconnecting."
        else -> "Relay WebSocket closed${if (code != 1000) " with code $code" else ""}${reason.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}"
    }
    is RelaySocketTerminalEvent.Failure -> "Relay WebSocket failed${responseCode?.let { " with HTTP $it" } ?: ""}: $message"
}

internal fun userFacingConnectionError(error: Throwable): String {
    val message = error.message ?: return "Could not connect to the relay."
    return when {
        message.contains("CLEARTEXT", ignoreCase = true) ->
            "Android blocked the local ws:// relay. Reinstall this build and try again."
        message.contains("Failed to connect", ignoreCase = true) ->
            "Could not reach the relay host. Confirm the phone and Mac are on the same network and the bridge is running."
        else -> message
    }
}

internal fun redactRelayUrlForLog(url: String): String {
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

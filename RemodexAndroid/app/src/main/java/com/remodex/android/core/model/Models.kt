package com.remodex.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

const val SECURE_PROTOCOL_VERSION = 1
const val PAIRING_QR_VERSION = 2
const val SECURE_HANDSHAKE_TAG = "remodex-e2ee-v1"
const val CLIENT_AUTH_LABEL = "client-auth"
const val TRUSTED_SESSION_RESOLVE_TAG = "remodex-trusted-session-resolve-v1"

@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

@Serializable
data class RpcMessage(
    val jsonrpc: String? = null,
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonElement? = null,
    val result: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class PairingQrPayload(
    val v: Int,
    val relay: String,
    val sessionId: String,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val expiresAt: Long,
)

@Serializable
data class SavedRelaySession(
    val relay: String,
    val sessionId: String,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val protocolVersion: Int = SECURE_PROTOCOL_VERSION,
    val lastAppliedBridgeOutboundSeq: Int = 0,
)

@Serializable
data class PhoneIdentityState(
    val phoneDeviceId: String,
    val phoneIdentityPrivateKey: String,
    val phoneIdentityPublicKey: String,
)

@Serializable
data class TrustedMacRecord(
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val lastPairedAt: Long,
    val relayUrl: String? = null,
    val displayName: String? = null,
    val lastResolvedSessionId: String? = null,
    val lastResolvedAt: Long? = null,
    val lastUsedAt: Long? = null,
)

@Serializable
data class TrustedMacRegistry(
    val records: Map<String, TrustedMacRecord> = emptyMap(),
)

@Serializable
enum class SecureHandshakeMode {
    @SerialName("qr_bootstrap")
    QR_BOOTSTRAP,

    @SerialName("trusted_reconnect")
    TRUSTED_RECONNECT,
}

@Serializable
data class SecureClientHello(
    val kind: String = "clientHello",
    val protocolVersion: Int,
    val sessionId: String,
    val handshakeMode: SecureHandshakeMode,
    val phoneDeviceId: String,
    val phoneIdentityPublicKey: String,
    val phoneEphemeralPublicKey: String,
    val clientNonce: String,
)

@Serializable
data class SecureServerHello(
    val kind: String,
    val protocolVersion: Int,
    val sessionId: String,
    val handshakeMode: SecureHandshakeMode,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val macEphemeralPublicKey: String,
    val serverNonce: String,
    val keyEpoch: Int,
    val expiresAtForTranscript: Long,
    val macSignature: String,
    val clientNonce: String? = null,
)

@Serializable
data class SecureClientAuth(
    val kind: String = "clientAuth",
    val sessionId: String,
    val phoneDeviceId: String,
    val keyEpoch: Int,
    val phoneSignature: String,
)

@Serializable
data class SecureReadyMessage(
    val kind: String,
    val sessionId: String,
    val keyEpoch: Int,
    val macDeviceId: String,
)

@Serializable
data class SecureResumeState(
    val kind: String = "resumeState",
    val sessionId: String,
    val keyEpoch: Int,
    val lastAppliedBridgeOutboundSeq: Int,
)

@Serializable
data class SecureErrorMessage(
    val kind: String,
    val code: String,
    val message: String,
)

@Serializable
data class SecureEnvelope(
    val kind: String,
    val v: Int,
    val sessionId: String,
    val keyEpoch: Int,
    val sender: String,
    val counter: Int,
    val ciphertext: String,
    val tag: String,
)

@Serializable
data class SecureApplicationPayload(
    val bridgeOutboundSeq: Int? = null,
    val payloadText: String,
)

@Serializable
data class TrustedSessionResolveRequest(
    val macDeviceId: String,
    val phoneDeviceId: String,
    val phoneIdentityPublicKey: String,
    val nonce: String,
    val timestamp: Long,
    val signature: String,
)

@Serializable
data class TrustedSessionResolveResponse(
    val ok: Boolean,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val displayName: String? = null,
    val sessionId: String,
)

@Serializable
data class RelayErrorResponse(
    val ok: Boolean? = null,
    val error: String? = null,
    val code: String? = null,
)

data class SecureSession(
    val sessionId: String,
    val keyEpoch: Int,
    val macDeviceId: String,
    val macIdentityPublicKey: String,
    val phoneToMacKey: ByteArray,
    val macToPhoneKey: ByteArray,
    var lastInboundBridgeOutboundSeq: Int,
    var lastInboundCounter: Int,
    var nextOutboundCounter: Int,
)

data class ThreadSummary(
    val id: String,
    val title: String,
    val preview: String?,
    val cwd: String?,
    val updatedAtMillis: Long?,
    val isArchived: Boolean,
)

@Serializable
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

@Serializable
enum class MessageKind {
    CHAT,
    THINKING,
    TOOL_ACTIVITY,
    COMMAND_EXECUTION,
    APPROVAL,
}

@Serializable
data class ConversationMessage(
    val id: String,
    val threadId: String,
    val role: MessageRole,
    val kind: MessageKind = MessageKind.CHAT,
    val text: String,
    val attachments: List<ImageAttachment> = emptyList(),
    val turnId: String? = null,
    val itemId: String? = null,
    val isStreaming: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

fun namespacedConversationMessageId(
    rawId: String,
    role: MessageRole,
    kind: MessageKind,
): String {
    val prefix = when (kind) {
        MessageKind.THINKING -> "thinking"
        MessageKind.TOOL_ACTIVITY -> "tool"
        MessageKind.COMMAND_EXECUTION -> "command"
        MessageKind.APPROVAL -> "approval"
        MessageKind.CHAT -> when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
        }
    }
    return if (rawId.startsWith("$prefix-")) rawId else "$prefix-$rawId"
}

fun ConversationMessage.timelineLazyKey(index: Int): String {
    return buildString {
        append(namespacedConversationMessageId(id, role, kind))
        append('|')
        append(itemId ?: "")
        append('|')
        append(turnId ?: "")
        append('|')
        append(createdAtMillis)
        append('|')
        append(index)
    }
}

data class PendingApproval(
    val requestKey: String,
    val requestId: JsonElement,
    val method: String,
    val command: String?,
    val reason: String?,
    val threadId: String?,
    val turnId: String?,
)

enum class RelayConnectionState {
    OFFLINE,
    CONNECTING,
    HANDSHAKING,
    CONNECTED,
    RECONNECT_REQUIRED,
    UPDATE_REQUIRED,
}

data class RemodexUiState(
    val connectionState: RelayConnectionState = RelayConnectionState.OFFLINE,
    val secureStatusLabel: String = "Not paired",
    val isConnected: Boolean = false,
    val hasSavedPairing: Boolean = false,
    val relaySession: SavedRelaySession? = null,
    val trustedMacs: Map<String, TrustedMacRecord> = emptyMap(),
    val threads: List<ThreadSummary> = emptyList(),
    val selectedThreadId: String? = null,
    val messagesByThread: Map<String, List<ConversationMessage>> = emptyMap(),
    val loadingThreadIds: Set<String> = emptySet(),
    val activeTurnIdByThread: Map<String, String> = emptyMap(),
    val runningThreadIds: Set<String> = emptySet(),
    val pendingApproval: PendingApproval? = null,
    val errorMessage: String? = null,
    // Plan mode
    val planStateByThread: Map<String, PlanState> = emptyMap(),
    val structuredInputRequests: List<StructuredUserInputRequest> = emptyList(),
    // Git
    val gitStatus: GitRepoSyncResult? = null,
    val gitStatusByThread: Map<String, GitRepoSyncResult> = emptyMap(),
    val gitBranches: GitBranchesResult? = null,
    // Runtime config
    val availableModels: List<ModelOption> = emptyList(),
    val runtimeOverrideByThread: Map<String, ThreadRuntimeOverride> = emptyMap(),
    val selectedAccessMode: AccessMode = AccessMode.ON_REQUEST,
    val contextWindowUsageByThread: Map<String, ContextWindowUsage> = emptyMap(),
    val rateLimitBuckets: List<RateLimitBucket> = emptyList(),
    val isLoadingRateLimits: Boolean = false,
    val rateLimitsErrorMessage: String? = null,
    val hasResolvedRateLimitsSnapshot: Boolean = false,
    // Notifications
    val pendingNotificationThreadId: String? = null,
)

fun JsonObject.stringOrNull(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        if (value.isString) {
            return value.content.takeIf { it.isNotBlank() && it != "null" }
        }
        val content = value.content
        if (content.isNotBlank() && content != "null") {
            return content
        }
    }
    return null
}

fun JsonObject.longOrNull(vararg keys: String): Long? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.content.toLongOrNull()?.let { return it }
    }
    return null
}

fun JsonObject.boolOrNull(vararg keys: String): Boolean? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        when (value.content.lowercase()) {
            "true" -> return true
            "false" -> return false
        }
    }
    return null
}

fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement?.jsonArrayOrNull(): JsonArray? = this as? JsonArray

fun idKey(value: JsonElement?): String {
    return when (value) {
        null, JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}

fun threadSummaryFromJson(payload: JsonObject): ThreadSummary? {
    val id = payload.stringOrNull("id") ?: return null
    val title = payload.stringOrNull("name", "title")
        ?: payload.stringOrNull("preview")
        ?: "New Thread"
    val rawTimestamp = payload.longOrNull("updatedAt", "updated_at")
    // Normalize: if the value looks like seconds (< 10 billion), convert to millis.
    val updatedAtMillis = when {
        rawTimestamp == null -> null
        rawTimestamp < 10_000_000_000L -> rawTimestamp * 1000L
        else -> rawTimestamp
    }
    return ThreadSummary(
        id = id,
        title = title,
        preview = payload.stringOrNull("preview"),
        cwd = payload.stringOrNull("cwd", "current_working_directory", "working_directory"),
        updatedAtMillis = updatedAtMillis,
        isArchived = payload.boolOrNull("archived") ?: false,
    )
}

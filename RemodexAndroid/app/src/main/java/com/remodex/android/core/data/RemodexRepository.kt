package com.remodex.android.core.data

import android.util.Log
import com.remodex.android.core.model.*
import com.remodex.android.core.notification.RemodexNotificationService
import com.remodex.android.core.network.RelaySocketTerminalEvent
import com.remodex.android.core.network.RelayWebSocketClient
import com.remodex.android.core.security.AndroidSecureStorage
import com.remodex.android.core.security.SecureTransportCrypto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class RemodexRepository(
    private val storage: AndroidSecureStorage,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    var notificationService: RemodexNotificationService? = null,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketClient = RelayWebSocketClient(okHttpClient)
    private val pendingRequests = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<RpcMessage>>()
    private val secureControlFlow = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)

    private var secureSession: SecureSession? = null
    private var phoneIdentity: PhoneIdentityState = storage.getOrCreatePhoneIdentity()
    private var syncJob: Job? = null
    var isAppInForeground: AtomicBoolean = AtomicBoolean(true)

    private val _uiState = MutableStateFlow(
        RemodexUiState(
            hasSavedPairing = storage.readRelaySession() != null,
            relaySession = storage.readRelaySession(),
            trustedMacs = storage.readTrustedRegistry().records,
        ),
    )
    val uiState: StateFlow<RemodexUiState> = _uiState.asStateFlow()

    init {
        scope.launch {
            socketClient.incoming.collect(::handleIncomingWireText)
        }
    }

    suspend fun connectWithPairing(payload: PairingQrPayload) {
        val saved = SavedRelaySession(
            relay = payload.relay.trimEnd('/'),
            sessionId = payload.sessionId,
            macDeviceId = payload.macDeviceId,
            macIdentityPublicKey = payload.macIdentityPublicKey,
        )
        storage.writeRelaySession(saved)
        _uiState.update {
            it.copy(
                hasSavedPairing = true,
                relaySession = saved,
                errorMessage = null,
            )
        }
        connectFromSavedSession(forceQrBootstrap = true)
    }

    suspend fun connectFromSavedSession(forceQrBootstrap: Boolean = false) {
        val savedSession = storage.readRelaySession()
        if (savedSession == null) {
            _uiState.update { it.copy(errorMessage = "No saved pairing. Scan a QR code first.") }
            return
        }

        _uiState.update {
            it.copy(
                connectionState = RelayConnectionState.CONNECTING,
                secureStatusLabel = "Connecting to relay",
                isConnected = false,
                errorMessage = null,
            )
        }
        try {
            val connectedSession = tryResolvedTrustedSession(savedSession) ?: savedSession
            connectInternal(connectedSession, forceQrBootstrap)
        } catch (error: TimeoutCancellationException) {
            logError("connect timed out", error)
            _uiState.update {
                it.copy(
                    connectionState = RelayConnectionState.RECONNECT_REQUIRED,
                    isConnected = false,
                    secureStatusLabel = "Relay timed out",
                    errorMessage = "Connection timed out. Confirm the Mac bridge is running and the phone can reach the relay host on this network.",
                )
            }
        } catch (error: Exception) {
            logError("connect failed", error)
            _uiState.update {
                it.copy(
                    connectionState = RelayConnectionState.RECONNECT_REQUIRED,
                    isConnected = false,
                    secureStatusLabel = "Reconnect required",
                    errorMessage = userFacingConnectionError(error),
                )
            }
        }
    }

    suspend fun disconnect(clearPairing: Boolean = false) {
        stopSyncLoop()
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        secureSession = null
        socketClient.disconnect()
        if (clearPairing) {
            storage.clearRelayState()
        }
        _uiState.update {
            it.copy(
                connectionState = RelayConnectionState.OFFLINE,
                isConnected = false,
                hasSavedPairing = storage.readRelaySession() != null,
                relaySession = storage.readRelaySession(),
                activeTurnIdByThread = emptyMap(),
                runningThreadIds = emptySet(),
                pendingApproval = null,
            )
        }
    }

    suspend fun refreshThreads() {
        val response = sendRequest(
            method = "thread/list",
            params = JsonObject(
                mapOf(
                    "sourceKinds" to JsonArray(
                        listOf("cli", "vscode", "appServer", "exec", "unknown").map(::JsonPrimitive),
                    ),
                    "cursor" to JsonNull,
                ),
            ),
        )
        val root = response.result.jsonObjectOrNull() ?: return
        val page = root["data"].jsonArrayOrNull()
            ?: root["items"].jsonArrayOrNull()
            ?: root["threads"].jsonArrayOrNull()
            ?: JsonArray(emptyList())
        val threads = page.mapNotNull { it.jsonObjectOrNull()?.let(::threadSummaryFromJson) }
        _uiState.update {
            it.copy(
                threads = threads,
                selectedThreadId = it.selectedThreadId ?: threads.firstOrNull()?.id,
            )
        }
        _uiState.value.selectedThreadId?.let { selectedThreadId ->
            openThread(selectedThreadId)
        }
    }

    suspend fun startThread() {
        val response = sendRequest("thread/start", JsonObject(emptyMap()))
        val thread = response.result.jsonObjectOrNull()
            ?.get("thread")
            ?.jsonObjectOrNull()
            ?.let(::threadSummaryFromJson)
            ?: return refreshThreads()
        _uiState.update {
            it.copy(
                threads = listOf(thread) + it.threads.filterNot { existing -> existing.id == thread.id },
                selectedThreadId = thread.id,
            )
        }
    }

    suspend fun openThread(threadId: String) {
        _uiState.update { it.copy(selectedThreadId = threadId, errorMessage = null) }
        val response = sendRequest(
            method = "thread/read",
            params = JsonObject(
                mapOf(
                    "threadId" to JsonPrimitive(threadId),
                    "includeTurns" to JsonPrimitive(true),
                ),
            ),
        )
        val threadObject = response.result.jsonObjectOrNull()?.get("thread")?.jsonObjectOrNull() ?: return
        val history = parseThreadHistory(threadId, threadObject)
        _uiState.update { state ->
            state.copy(messagesByThread = state.messagesByThread + (threadId to history))
        }
    }

    suspend fun sendTurn(threadId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val localMessage = ConversationMessage(
            id = UUID.randomUUID().toString(),
            threadId = threadId,
            role = MessageRole.USER,
            text = trimmed,
        )
        appendMessage(threadId, localMessage)
        val input = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(trimmed),
                    ),
                ),
            ),
        )
        val turnParams = mutableMapOf<String, JsonElement>(
            "threadId" to JsonPrimitive(threadId),
            "input" to input,
        )
        // Apply runtime overrides
        val override = _uiState.value.runtimeOverrideByThread[threadId]
        override?.model?.let { turnParams["model"] = JsonPrimitive(it) }
        override?.reasoningEffort?.let { turnParams["effort"] = JsonPrimitive(it.value) }
        override?.serviceTier?.let { turnParams["serviceTier"] = JsonPrimitive(it.value) }

        val response = sendRequest(method = "turn/start", params = JsonObject(turnParams))
        val turnId = extractTurnId(response.result.jsonObjectOrNull())
        if (turnId != null) {
            _uiState.update {
                it.copy(
                    activeTurnIdByThread = it.activeTurnIdByThread + (threadId to turnId),
                    runningThreadIds = it.runningThreadIds + threadId,
                )
            }
        }
    }

    suspend fun interruptTurn(threadId: String) {
        val turnId = _uiState.value.activeTurnIdByThread[threadId] ?: return
        sendRequest(
            method = "turn/interrupt",
            params = JsonObject(
                mapOf(
                    "threadId" to JsonPrimitive(threadId),
                    "turnId" to JsonPrimitive(turnId),
                ),
            ),
        )
    }

    suspend fun respondToApproval(accept: Boolean) {
        val approval = _uiState.value.pendingApproval ?: return
        sendRpc(
            RpcMessage(
                id = approval.requestId,
                result = JsonObject(
                    mapOf(
                        "decision" to JsonPrimitive(if (accept) "accept" else "reject"),
                    ),
                ),
            ),
        )
        _uiState.update { it.copy(pendingApproval = null) }
    }

    // ── Sync loop ──────────────────────────────────────────────────────

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            launch { threadListSyncLoop() }
            launch { activeThreadSyncLoop() }
            launch { runningThreadWatchLoop() }
        }
    }

    private fun stopSyncLoop() {
        syncJob?.cancel()
        syncJob = null
    }

    private suspend fun threadListSyncLoop() {
        while (true) {
            val interval = if (isAppInForeground.get()) 10_000L else 75_000L
            delay(interval)
            if (_uiState.value.isConnected) {
                runCatching { refreshThreads() }
            }
        }
    }

    private suspend fun activeThreadSyncLoop() {
        while (true) {
            val threadId = _uiState.value.selectedThreadId
            val isRunning = threadId != null && threadId in _uiState.value.runningThreadIds
            val interval = when {
                !isAppInForeground.get() && isRunning -> 12_000L
                !isAppInForeground.get() -> 90_000L
                isRunning -> 3_000L
                else -> 10_000L
            }
            delay(interval)
            if (_uiState.value.isConnected && threadId != null) {
                runCatching { openThread(threadId) }
            }
        }
    }

    private suspend fun runningThreadWatchLoop() {
        while (true) {
            val interval = if (isAppInForeground.get()) 2_000L else 15_000L
            delay(interval)
            if (_uiState.value.isConnected && _uiState.value.runningThreadIds.isNotEmpty()) {
                for (threadId in _uiState.value.runningThreadIds) {
                    if (threadId != _uiState.value.selectedThreadId) {
                        runCatching { openThread(threadId) }
                    }
                }
            }
        }
    }

    // ── Git actions ──────────────────────────────────────────────────

    suspend fun gitStatus(cwd: String): GitRepoSyncResult? {
        val response = sendRequest("git/status", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        val result = parseGitRepoSyncResult(obj)
        _uiState.update { it.copy(gitStatus = result) }
        return result
    }

    suspend fun gitCommit(cwd: String, message: String? = null): GitCommitResult? {
        val params = mutableMapOf<String, JsonElement>("cwd" to JsonPrimitive(cwd))
        message?.let { params["message"] = JsonPrimitive(it) }
        val response = sendRequest("git/commit", JsonObject(params))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitCommitResult(
            hash = obj.stringOrNull("hash"),
            branch = obj.stringOrNull("branch"),
            summary = obj.stringOrNull("summary"),
        )
    }

    suspend fun gitPush(cwd: String): GitPushResult? {
        val response = sendRequest("git/push", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitPushResult(
            branch = obj.stringOrNull("branch"),
            remote = obj.stringOrNull("remote"),
            updated = obj.boolOrNull("updated") ?: false,
            status = obj.stringOrNull("status"),
        )
    }

    suspend fun gitPull(cwd: String): GitPullResult? {
        val response = sendRequest("git/pull", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitPullResult(status = obj.stringOrNull("status"))
    }

    suspend fun gitBranches(cwd: String): GitBranchesResult? {
        val response = sendRequest("git/branches", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        val result = parseGitBranchesResult(obj)
        _uiState.update { it.copy(gitBranches = result) }
        return result
    }

    suspend fun gitDiff(cwd: String): GitDiffResult? {
        val response = sendRequest("git/diff", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitDiffResult(patch = obj.stringOrNull("patch"))
    }

    suspend fun gitCheckout(cwd: String, branch: String): GitCheckoutResult? {
        val response = sendRequest(
            "git/checkout",
            JsonObject(mapOf("cwd" to JsonPrimitive(cwd), "branch" to JsonPrimitive(branch))),
        )
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitCheckoutResult(status = obj.stringOrNull("status"))
    }

    suspend fun gitCreateBranch(cwd: String, name: String): GitCreateBranchResult? {
        val response = sendRequest(
            "git/createBranch",
            JsonObject(mapOf("cwd" to JsonPrimitive(cwd), "name" to JsonPrimitive(name))),
        )
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitCreateBranchResult(status = obj.stringOrNull("status"), branch = obj.stringOrNull("branch"))
    }

    // ── Model / runtime config ──────────────────────────────────────

    suspend fun fetchModelList() {
        val response = sendRequest(
            "model/list",
            JsonObject(
                mapOf(
                    "cursor" to JsonNull,
                    "limit" to JsonPrimitive(50),
                    "includeHidden" to JsonPrimitive(false),
                ),
            ),
        )
        val root = response.result.jsonObjectOrNull() ?: return
        val items = root["items"]?.jsonArrayOrNull()
            ?: root["data"]?.jsonArrayOrNull()
            ?: root["models"]?.jsonArrayOrNull()
            ?: return
        val models = parseModelOptions(items)
        _uiState.update { it.copy(availableModels = models) }
    }

    fun setRuntimeOverride(threadId: String, override: ThreadRuntimeOverride) {
        _uiState.update {
            it.copy(runtimeOverrideByThread = it.runtimeOverrideByThread + (threadId to override))
        }
    }

    // ── Access mode ─────────────────────────────────────────────────

    suspend fun setAccessMode(mode: AccessMode) {
        _uiState.update { it.copy(selectedAccessMode = mode) }
        runCatching {
            sendRequest(
                "sandbox/setApprovalPolicy",
                JsonObject(mapOf("policy" to JsonPrimitive(mode.policyValue))),
            )
        }
    }

    // ── Structured input response ───────────────────────────────────

    suspend fun respondToStructuredInput(requestId: JsonElement, answers: Map<String, String>) {
        val answersJson = JsonObject(answers.mapValues { JsonPrimitive(it.value) })
        sendRpc(
            RpcMessage(
                id = requestId,
                result = JsonObject(mapOf("answers" to answersJson)),
            ),
        )
        _uiState.update {
            it.copy(
                structuredInputRequests = it.structuredInputRequests.filterNot { req -> req.requestId == requestId },
            )
        }
    }

    // ── Voice transcription ────────────────────────────────────────

    suspend fun resolveVoiceAuthToken(): String? {
        return try {
            val response = sendRequest("voice/resolveAuth", null)
            response.result.jsonObjectOrNull()?.stringOrNull("token")
        } catch (e: Exception) {
            Log.e("Remodex", "voice/resolveAuth failed", e)
            null
        }
    }

    // ── Background recovery ─────────────────────────────────────────

    suspend fun attemptAutoReconnect() {
        if (_uiState.value.isConnected) return
        if (!_uiState.value.hasSavedPairing) return
        Log.d("Remodex", "Attempting auto-reconnect on foreground resume")
        runCatching { connectFromSavedSession() }
    }

    private suspend fun connectInternal(savedSession: SavedRelaySession, forceQrBootstrap: Boolean) {
        val websocketUrl = websocketSessionUrl(savedSession)
        Log.d("Remodex", "connecting relay=${redactRelayUrlForLog(websocketUrl)}")
        withTimeout(15_000L) {
            socketClient.connect(websocketUrl, role = "iphone")
        }
        _uiState.update {
            it.copy(
                connectionState = RelayConnectionState.HANDSHAKING,
                secureStatusLabel = "Secure handshake in progress",
                relaySession = savedSession,
                hasSavedPairing = true,
            )
        }
        performSecureHandshake(savedSession, forceQrBootstrap)
        initializeSession()
        _uiState.update {
            it.copy(
                connectionState = RelayConnectionState.CONNECTED,
                secureStatusLabel = "End-to-end encrypted",
                isConnected = true,
                errorMessage = null,
            )
        }
        refreshThreads()
        startSyncLoop()
        runCatching { fetchModelList() }
    }

    private suspend fun initializeSession() {
        val clientInfo = JsonObject(
            mapOf(
                "name" to JsonPrimitive("remodex_android"),
                "title" to JsonPrimitive("Remodex Android"),
                "version" to JsonPrimitive("0.1.0"),
            ),
        )
        val modernParams = JsonObject(
            mapOf(
                "clientInfo" to clientInfo,
                "capabilities" to JsonObject(mapOf("experimentalApi" to JsonPrimitive(true))),
            ),
        )
        try {
            sendRequest("initialize", modernParams)
        } catch (_: Exception) {
            sendRequest("initialize", JsonObject(mapOf("clientInfo" to clientInfo)))
        }
        sendNotification("initialized", null)
    }

    private suspend fun performSecureHandshake(savedSession: SavedRelaySession, forceQrBootstrap: Boolean) {
        val trustedRegistry = storage.readTrustedRegistry()
        val trustedMac = trustedRegistry.records[savedSession.macDeviceId]
        val handshakeMode = if (!forceQrBootstrap && trustedMac != null) {
            SecureHandshakeMode.TRUSTED_RECONNECT
        } else {
            SecureHandshakeMode.QR_BOOTSTRAP
        }
        val expectedMacPublicKey = trustedMac?.macIdentityPublicKey ?: savedSession.macIdentityPublicKey
        val ephemeral = SecureTransportCrypto.generateEphemeralX25519()
        val clientNonce = SecureTransportCrypto.randomNonce()
        val clientHello = SecureClientHello(
            protocolVersion = savedSession.protocolVersion,
            sessionId = savedSession.sessionId,
            handshakeMode = handshakeMode,
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
            phoneEphemeralPublicKey = ephemeral.publicKeyBase64,
            clientNonce = java.util.Base64.getEncoder().encodeToString(clientNonce),
        )
        sendControl(clientHello)

        val serverHello = waitForControl("serverHello").let {
            json.decodeFromJsonElement(SecureServerHello.serializer(), it)
        }
        require(serverHello.protocolVersion == SECURE_PROTOCOL_VERSION) {
            "The bridge is using a different secure transport version."
        }
        require(serverHello.sessionId == savedSession.sessionId) {
            "The secure bridge session id did not match the saved pairing."
        }
        require(serverHello.macDeviceId == savedSession.macDeviceId) {
            "The bridge reported a different Mac identity for this session."
        }
        require(serverHello.macIdentityPublicKey == expectedMacPublicKey) {
            "The secure Mac identity did not match the saved pairing."
        }

        val transcript = SecureTransportCrypto.buildTranscriptBytes(
            sessionId = savedSession.sessionId,
            protocolVersion = serverHello.protocolVersion,
            handshakeMode = serverHello.handshakeMode,
            keyEpoch = serverHello.keyEpoch,
            macDeviceId = serverHello.macDeviceId,
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            macIdentityPublicKey = serverHello.macIdentityPublicKey,
            phoneIdentityPublicKey = phoneIdentity.phoneIdentityPublicKey,
            macEphemeralPublicKey = serverHello.macEphemeralPublicKey,
            phoneEphemeralPublicKey = clientHello.phoneEphemeralPublicKey,
            clientNonce = java.util.Base64.getDecoder().decode(clientHello.clientNonce),
            serverNonce = java.util.Base64.getDecoder().decode(serverHello.serverNonce),
            expiresAtForTranscript = serverHello.expiresAtForTranscript,
        )
        require(
            SecureTransportCrypto.verifyEd25519(
                publicKeyBase64 = serverHello.macIdentityPublicKey,
                transcript = transcript,
                signatureBase64 = serverHello.macSignature,
            ),
        ) { "The secure Mac signature could not be verified." }

        val clientAuthTranscript = SecureTransportCrypto.buildClientAuthTranscript(transcript)
        val clientAuth = SecureClientAuth(
            sessionId = savedSession.sessionId,
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            keyEpoch = serverHello.keyEpoch,
            phoneSignature = SecureTransportCrypto.signEd25519(
                privateKeyBase64 = phoneIdentity.phoneIdentityPrivateKey,
                transcript = clientAuthTranscript,
            ),
        )
        sendControl(clientAuth)
        val ready = waitForControl("secureReady").let {
            json.decodeFromJsonElement(SecureReadyMessage.serializer(), it)
        }
        require(ready.keyEpoch == serverHello.keyEpoch) {
            "The secure ready response had the wrong key epoch."
        }

        val sharedSecret = SecureTransportCrypto.sharedSecret(ephemeral.privateKey, serverHello.macEphemeralPublicKey)
        val keys = SecureTransportCrypto.deriveSessionKeys(
            sharedSecret = sharedSecret,
            transcriptBytes = transcript,
            sessionId = savedSession.sessionId,
            macDeviceId = savedSession.macDeviceId,
            phoneDeviceId = phoneIdentity.phoneDeviceId,
            keyEpoch = serverHello.keyEpoch,
        )
        secureSession = SecureSession(
            sessionId = savedSession.sessionId,
            keyEpoch = serverHello.keyEpoch,
            macDeviceId = savedSession.macDeviceId,
            macIdentityPublicKey = serverHello.macIdentityPublicKey,
            phoneToMacKey = keys.phoneToMacKey,
            macToPhoneKey = keys.macToPhoneKey,
            lastInboundBridgeOutboundSeq = savedSession.lastAppliedBridgeOutboundSeq,
            lastInboundCounter = -1,
            nextOutboundCounter = 0,
        )

        val updatedRegistry = trustedRegistry.records + (
            savedSession.macDeviceId to TrustedMacRecord(
                macDeviceId = savedSession.macDeviceId,
                macIdentityPublicKey = serverHello.macIdentityPublicKey,
                lastPairedAt = System.currentTimeMillis(),
                relayUrl = savedSession.relay,
                displayName = trustedMac?.displayName,
                lastResolvedSessionId = savedSession.sessionId,
                lastResolvedAt = System.currentTimeMillis(),
                lastUsedAt = System.currentTimeMillis(),
            )
        )
        storage.writeTrustedRegistry(TrustedMacRegistry(updatedRegistry))
        storage.writeLastTrustedMacDeviceId(savedSession.macDeviceId)
        _uiState.update {
            it.copy(
                trustedMacs = updatedRegistry,
                secureStatusLabel = "End-to-end encrypted",
            )
        }

        sendControl(
            SecureResumeState(
                sessionId = savedSession.sessionId,
                keyEpoch = serverHello.keyEpoch,
                lastAppliedBridgeOutboundSeq = savedSession.lastAppliedBridgeOutboundSeq,
            ),
        )
    }

    private suspend fun waitForControl(kind: String): JsonObject {
        return coroutineScope {
            socketClient.lastTerminalEvent?.let { error(it.toUserFacingError()) }
            val control = async { secureControlFlow.filter { it.stringOrNull("kind") == kind }.first() }
            val terminal = async { socketClient.terminalEvents.first() }
            try {
                withTimeout(15_000L) {
                    select {
                        control.onAwait { it }
                        terminal.onAwait { event -> error(event.toUserFacingError()) }
                    }
                }
            } finally {
                control.cancel()
                terminal.cancel()
            }
        }
    }

    private suspend fun handleIncomingWireText(text: String) {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()?.jsonObjectOrNull() ?: return
        when (root.stringOrNull("kind")) {
            "serverHello", "secureReady" -> secureControlFlow.emit(root)
            "secureError" -> {
                val error = json.decodeFromJsonElement(SecureErrorMessage.serializer(), root)
                _uiState.update {
                    it.copy(
                        connectionState = if (error.code == "update_required") {
                            RelayConnectionState.UPDATE_REQUIRED
                        } else {
                            RelayConnectionState.RECONNECT_REQUIRED
                        },
                        errorMessage = error.message,
                        secureStatusLabel = if (error.code == "update_required") "Update required" else "Re-pair required",
                    )
                }
                secureControlFlow.emit(root)
            }
            "encryptedEnvelope" -> handleEncryptedEnvelope(json.decodeFromJsonElement(SecureEnvelope.serializer(), root))
            else -> handleRpcMessage(json.decodeFromJsonElement(RpcMessage.serializer(), root))
        }
    }

    private suspend fun handleEncryptedEnvelope(envelope: SecureEnvelope) {
        val session = secureSession ?: return
        if (envelope.sessionId != session.sessionId || envelope.keyEpoch != session.keyEpoch) {
            return
        }
        if (envelope.sender != "mac" || envelope.counter <= session.lastInboundCounter) {
            _uiState.update {
                it.copy(
                    connectionState = RelayConnectionState.RECONNECT_REQUIRED,
                    errorMessage = "The secure Remodex payload could not be verified.",
                )
            }
            return
        }
        val payload = SecureTransportCrypto.decryptEnvelope(session, envelope) ?: return
        session.lastInboundCounter = envelope.counter
        val bridgeOutboundSeq = payload.bridgeOutboundSeq
        if (bridgeOutboundSeq != null) {
            secureSession = session.copy(lastInboundBridgeOutboundSeq = bridgeOutboundSeq)
            storage.readRelaySession()?.let { saved ->
                storage.writeRelaySession(saved.copy(lastAppliedBridgeOutboundSeq = bridgeOutboundSeq))
            }
        }
        handleRpcMessage(json.decodeFromString(RpcMessage.serializer(), payload.payloadText))
    }

    private suspend fun handleRpcMessage(message: RpcMessage) {
        if (message.method != null) {
            if (message.id != null) {
                handleServerRequest(message)
            } else {
                handleNotification(message.method, message.params.jsonObjectOrNull())
            }
            return
        }
        val requestKey = idKey(message.id)
        pendingRequests.remove(requestKey)?.complete(message)
    }

    private suspend fun handleServerRequest(message: RpcMessage) {
        val method = message.method ?: return
        val params = message.params.jsonObjectOrNull()

        // Approval requests
        if (method.endsWith("requestApproval")) {
            // Auto-approve in full-access mode
            if (_uiState.value.selectedAccessMode == AccessMode.FULL_ACCESS) {
                sendRpc(
                    RpcMessage(
                        id = message.id,
                        result = JsonObject(mapOf("decision" to JsonPrimitive("accept"))),
                    ),
                )
                return
            }
            _uiState.update {
                it.copy(
                    pendingApproval = PendingApproval(
                        requestKey = idKey(message.id),
                        requestId = message.id ?: JsonNull,
                        method = method,
                        command = params?.stringOrNull("command"),
                        reason = params?.stringOrNull("reason"),
                        threadId = extractThreadId(params),
                        turnId = extractTurnId(params),
                    ),
                )
            }
            return
        }

        // Structured user input requests (plan mode questions)
        if (method == "item/tool/requestUserInput" || method == "tool/requestUserInput") {
            if (params != null) {
                val questions = parseStructuredInputQuestions(params)
                if (questions.isNotEmpty()) {
                    val request = StructuredUserInputRequest(
                        requestId = message.id ?: JsonNull,
                        threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: "",
                        turnId = extractTurnId(params),
                        itemId = extractItemId(params),
                        questions = questions,
                    )
                    _uiState.update {
                        it.copy(structuredInputRequests = it.structuredInputRequests + request)
                    }
                    // Notify if backgrounded
                    if (!isAppInForeground.get()) {
                        val threadTitle = _uiState.value.threads
                            .find { it.id == request.threadId }?.title ?: "Remodex"
                        notificationService?.notifyStructuredInput(
                            request.threadId, threadTitle, questions.size,
                        )
                    }
                }
            }
            return
        }

        sendRpc(
            RpcMessage(
                id = message.id,
                error = RpcError(
                    code = -32601,
                    message = "Unsupported request method: $method",
                ),
            ),
        )
    }

    private suspend fun handleNotification(method: String, params: JsonObject?) {
        when (method) {
            "thread/started" -> {
                val thread = params?.get("thread")?.jsonObjectOrNull()?.let(::threadSummaryFromJson)
                    ?: params?.let(::threadSummaryFromJson)
                if (thread != null) {
                    _uiState.update {
                        it.copy(threads = listOf(thread) + it.threads.filterNot { existing -> existing.id == thread.id })
                    }
                }
            }

            "thread/listChanged" -> {
                val threadsArray = params?.get("threads")?.jsonArrayOrNull()
                if (threadsArray != null) {
                    val threads = threadsArray.mapNotNull { it.jsonObjectOrNull()?.let(::threadSummaryFromJson) }
                    _uiState.update { it.copy(threads = threads) }
                }
            }

            "turn/started" -> {
                val threadId = extractThreadId(params) ?: return
                val turnId = extractTurnId(params) ?: return
                _uiState.update {
                    it.copy(
                        activeTurnIdByThread = it.activeTurnIdByThread + (threadId to turnId),
                        runningThreadIds = it.runningThreadIds + threadId,
                    )
                }
            }

            "turn/completed", "turn/failed" -> {
                val threadId = extractThreadId(params) ?: return
                val isSuccess = method == "turn/completed"
                _uiState.update {
                    it.copy(
                        activeTurnIdByThread = it.activeTurnIdByThread - threadId,
                        runningThreadIds = it.runningThreadIds - threadId,
                        planStateByThread = it.planStateByThread - threadId,
                    )
                }
                // Post local notification if backgrounded
                if (!isAppInForeground.get()) {
                    val threadTitle = _uiState.value.threads.find { it.id == threadId }?.title ?: "Remodex"
                    notificationService?.notifyRunCompletion(threadId, threadTitle, isSuccess)
                }
            }

            // Plan mode
            "turn/plan/updated", "turn/planUpdated" -> {
                val turnId = extractTurnId(params) ?: return
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: return
                val explanation = params?.stringOrNull("explanation")
                val steps = params?.let { parsePlanSteps(it) } ?: emptyList()
                _uiState.update {
                    it.copy(
                        planStateByThread = it.planStateByThread + (threadId to PlanState(
                            turnId = turnId,
                            threadId = threadId,
                            explanation = explanation,
                            steps = steps,
                        )),
                    )
                }
            }

            "turn/plan/delta", "turn/planDelta", "item/plan/delta" -> {
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: return
                val delta = extractDelta(params) ?: return
                _uiState.update { state ->
                    val existing = state.planStateByThread[threadId]
                    if (existing != null) {
                        state.copy(
                            planStateByThread = state.planStateByThread + (threadId to existing.copy(
                                streamingText = (existing.streamingText ?: "") + delta,
                            )),
                        )
                    } else {
                        state
                    }
                }
            }

            // Thinking / reasoning deltas
            "item/reasoning/delta", "codex/event/reasoning_delta" -> {
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: return
                val turnId = extractTurnId(params)
                val itemId = extractItemId(params)
                val delta = extractDelta(params) ?: return
                appendThinkingDelta(threadId, turnId, itemId, delta)
            }

            // Tool activity
            "item/toolUse/started", "item/tool/started", "codex/event/tool_use_begin" -> {
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: return
                val turnId = extractTurnId(params)
                val itemId = extractItemId(params)
                val toolName = params?.stringOrNull("name", "tool", "toolName") ?: "tool"
                appendMessage(threadId, ConversationMessage(
                    id = itemId ?: UUID.randomUUID().toString(),
                    threadId = threadId,
                    role = MessageRole.ASSISTANT,
                    kind = MessageKind.TOOL_ACTIVITY,
                    text = toolName,
                    turnId = turnId,
                    itemId = itemId,
                    isStreaming = true,
                ))
            }

            "item/toolUse/completed", "item/tool/completed", "codex/event/tool_use_end" -> {
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: return
                val itemId = extractItemId(params)
                if (itemId != null) {
                    _uiState.update { state ->
                        val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
                        val idx = messages.indexOfLast { it.itemId == itemId && it.kind == MessageKind.TOOL_ACTIVITY }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(isStreaming = false)
                            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
                        } else {
                            state
                        }
                    }
                }
            }

            // Content deltas (assistant messages)
            "item/agentMessage/delta", "codex/event/agent_message_content_delta", "codex/event/agent_message_delta" -> {
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: return
                val turnId = extractTurnId(params)
                val itemId = extractItemId(params)
                val delta = extractDelta(params) ?: return
                appendAssistantDelta(threadId, turnId, itemId, delta)
            }

            "item/completed", "codex/event/item_completed", "codex/event/agent_message" -> {
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId ?: return
                val turnId = extractTurnId(params)
                val itemId = extractItemId(params)
                val text = extractCompletedText(params)
                if (!text.isNullOrBlank()) {
                    completeAssistantMessage(threadId, turnId, itemId, text)
                }
            }

            // Git repo changed
            "repo/changed", "repo/refreshSignal" -> {
                val threadId = extractThreadId(params) ?: _uiState.value.selectedThreadId
                val cwd = threadId?.let { tid -> _uiState.value.threads.find { it.id == tid }?.cwd }
                if (cwd != null) {
                    runCatching { gitStatus(cwd) }
                }
            }

            // Rate limit updates
            "account/rateLimitsUpdated" -> {
                Log.d("Remodex", "Rate limits updated (handling deferred)")
            }

            // Bridge update available
            "ui/bridgeUpdateAvailable" -> {
                val message = params?.stringOrNull("message") ?: "A bridge update is available."
                _uiState.update { it.copy(errorMessage = message) }
            }

            "error", "turn/error", "codex/event/error" -> {
                _uiState.update { it.copy(errorMessage = extractCompletedText(params) ?: "Runtime error.") }
            }
        }
    }

    private fun appendAssistantDelta(threadId: String, turnId: String?, itemId: String?, delta: String) {
        _uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
            val existingIndex = messages.indexOfLast {
                it.role == MessageRole.ASSISTANT &&
                    ((itemId != null && it.itemId == itemId) || (turnId != null && it.turnId == turnId)) &&
                    it.isStreaming
            }
            if (existingIndex >= 0) {
                val current = messages[existingIndex]
                messages[existingIndex] = current.copy(text = current.text + delta, isStreaming = true)
            } else {
                messages += ConversationMessage(
                    id = itemId ?: UUID.randomUUID().toString(),
                    threadId = threadId,
                    role = MessageRole.ASSISTANT,
                    text = delta,
                    turnId = turnId,
                    itemId = itemId,
                    isStreaming = true,
                )
            }
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
    }

    private fun completeAssistantMessage(threadId: String, turnId: String?, itemId: String?, text: String) {
        _uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
            val existingIndex = messages.indexOfLast {
                it.role == MessageRole.ASSISTANT &&
                    ((itemId != null && it.itemId == itemId) || (turnId != null && it.turnId == turnId))
            }
            if (existingIndex >= 0) {
                messages[existingIndex] = messages[existingIndex].copy(text = text, isStreaming = false)
            } else {
                messages += ConversationMessage(
                    id = itemId ?: UUID.randomUUID().toString(),
                    threadId = threadId,
                    role = MessageRole.ASSISTANT,
                    text = text,
                    turnId = turnId,
                    itemId = itemId,
                    isStreaming = false,
                )
            }
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
    }

    private fun appendThinkingDelta(threadId: String, turnId: String?, itemId: String?, delta: String) {
        _uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
            val existingIndex = messages.indexOfLast {
                it.kind == MessageKind.THINKING &&
                    ((itemId != null && it.itemId == itemId) || (turnId != null && it.turnId == turnId)) &&
                    it.isStreaming
            }
            if (existingIndex >= 0) {
                val current = messages[existingIndex]
                messages[existingIndex] = current.copy(text = current.text + delta, isStreaming = true)
            } else {
                messages += ConversationMessage(
                    id = itemId ?: "thinking-${UUID.randomUUID()}",
                    threadId = threadId,
                    role = MessageRole.ASSISTANT,
                    kind = MessageKind.THINKING,
                    text = delta,
                    turnId = turnId,
                    itemId = itemId,
                    isStreaming = true,
                )
            }
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
    }

    private fun appendMessage(threadId: String, message: ConversationMessage) {
        _uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty() + message
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
    }

    private suspend fun sendRequest(method: String, params: JsonObject?): RpcMessage {
        val requestId = JsonPrimitive(UUID.randomUUID().toString())
        val deferred = kotlinx.coroutines.CompletableDeferred<RpcMessage>()
        pendingRequests[idKey(requestId)] = deferred
        sendRpc(
            RpcMessage(
                id = requestId,
                method = method,
                params = params,
            ),
        )
        val response = deferred.await()
        response.error?.let { throw IllegalStateException(it.message) }
        return response
    }

    private suspend fun sendNotification(method: String, params: JsonObject?) {
        sendRpc(RpcMessage(method = method, params = params))
    }

    private suspend fun sendRpc(message: RpcMessage) {
        val session = secureSession ?: error("Secure session not ready")
        val plaintext = json.encodeToString(RpcMessage.serializer(), message)
        val envelope = SecureTransportCrypto.encryptEnvelope(
            session = session,
            sender = "iphone",
            counter = session.nextOutboundCounter,
            payloadText = plaintext,
        )
        secureSession = session.copy(nextOutboundCounter = session.nextOutboundCounter + 1)
        if (!socketClient.send(json.encodeToString(SecureEnvelope.serializer(), envelope))) {
            error("Relay WebSocket is not open.")
        }
    }

    private suspend fun sendControl(payload: Any) {
        val text = when (payload) {
            is SecureClientHello -> json.encodeToString(SecureClientHello.serializer(), payload)
            is SecureClientAuth -> json.encodeToString(SecureClientAuth.serializer(), payload)
            is SecureResumeState -> json.encodeToString(SecureResumeState.serializer(), payload)
            else -> error("Unsupported control payload")
        }
        if (!socketClient.send(text)) {
            error("Relay WebSocket is not open.")
        }
    }

    private suspend fun tryResolvedTrustedSession(savedSession: SavedRelaySession): SavedRelaySession? = withContext(Dispatchers.IO) {
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

    private fun parseThreadHistory(threadId: String, threadObject: JsonObject): List<ConversationMessage> {
        val results = mutableListOf<ConversationMessage>()
        val seen = LinkedHashSet<String>()
        parseHistoryArray(
            threadId = threadId,
            array = threadObject["messages"].jsonArrayOrNull(),
            turnId = null,
            out = results,
            seen = seen,
        )
        threadObject["turns"].jsonArrayOrNull()?.forEach { turnElement ->
            val turnObject = turnElement.jsonObjectOrNull() ?: return@forEach
            val turnId = extractTurnId(turnObject)
            parseTurnInput(threadId, turnId, turnObject, results, seen)
            parseHistoryArray(threadId, turnObject["messages"].jsonArrayOrNull(), turnId, results, seen)
            parseHistoryArray(threadId, turnObject["items"].jsonArrayOrNull(), turnId, results, seen)
            parseHistoryArray(threadId, turnObject["events"].jsonArrayOrNull(), turnId, results, seen)
        }
        return results.sortedBy { it.createdAtMillis }
    }

    private fun parseTurnInput(
        threadId: String,
        turnId: String?,
        turnObject: JsonObject,
        out: MutableList<ConversationMessage>,
        seen: MutableSet<String>,
    ) {
        val inputItems = turnObject["input"].jsonArrayOrNull() ?: return
        val text = inputItems.mapNotNull { item ->
            item.jsonObjectOrNull()?.stringOrNull("text")
        }.joinToString("\n").trim()
        if (text.isBlank()) {
            return
        }
        val key = "turn-input-${turnId ?: UUID.randomUUID()}"
        if (!seen.add(key)) {
            return
        }
        out += ConversationMessage(
            id = key,
            threadId = threadId,
            role = MessageRole.USER,
            text = text,
            turnId = turnId,
            createdAtMillis = turnObject.longOrNull("createdAt", "created_at") ?: System.currentTimeMillis(),
        )
    }

    private fun parseHistoryArray(
        threadId: String,
        array: JsonArray?,
        turnId: String?,
        out: MutableList<ConversationMessage>,
        seen: MutableSet<String>,
    ) {
        array?.forEach { element ->
            val item = element.jsonObjectOrNull() ?: return@forEach
            val message = historyMessageFromObject(threadId, turnId, item) ?: return@forEach
            if (seen.add(message.id)) {
                out += message
            }
        }
    }

    private fun historyMessageFromObject(threadId: String, turnId: String?, item: JsonObject): ConversationMessage? {
        val type = item.stringOrNull("type")?.lowercase().orEmpty()
        val role = when {
            item.stringOrNull("role") == "user" || type.contains("user") -> MessageRole.USER
            item.stringOrNull("role") == "assistant" || type.contains("agent_message") || type.contains("assistant") -> MessageRole.ASSISTANT
            else -> MessageRole.SYSTEM
        }
        val text = extractCompletedText(item) ?: extractDelta(item) ?: return null
        val id = item.stringOrNull("id", "itemId", "item_id", "messageId", "message_id")
            ?: "${role.name.lowercase()}-${UUID.randomUUID()}"
        return ConversationMessage(
            id = id,
            threadId = threadId,
            role = role,
            kind = if (type.contains("reasoning")) MessageKind.THINKING else MessageKind.CHAT,
            text = text,
            turnId = extractTurnId(item) ?: turnId,
            itemId = item.stringOrNull("itemId", "item_id", "callId", "call_id"),
            createdAtMillis = item.longOrNull("createdAt", "created_at") ?: System.currentTimeMillis(),
            isStreaming = false,
        )
    }

    private fun extractThreadId(payload: JsonObject?): String? {
        payload ?: return null
        return payload.stringOrNull("threadId", "thread_id", "conversationId", "conversation_id")
            ?: payload["thread"].jsonObjectOrNull()?.stringOrNull("id")
            ?: payload["turn"].jsonObjectOrNull()?.stringOrNull("threadId", "thread_id")
            ?: payload["event"].jsonObjectOrNull()?.stringOrNull("threadId", "thread_id")
    }

    private fun extractTurnId(payload: JsonObject?): String? {
        payload ?: return null
        return payload.stringOrNull("turnId", "turn_id")
            ?: payload["turn"].jsonObjectOrNull()?.stringOrNull("id")
            ?: payload["event"].jsonObjectOrNull()?.stringOrNull("turnId", "turn_id")
            ?: payload["item"].jsonObjectOrNull()?.stringOrNull("turnId", "turn_id")
    }

    private fun extractItemId(payload: JsonObject?): String? {
        payload ?: return null
        return payload.stringOrNull("itemId", "item_id", "callId", "call_id", "messageId", "message_id")
            ?: payload["item"].jsonObjectOrNull()?.stringOrNull("id", "itemId", "item_id")
    }

    private fun extractDelta(payload: JsonObject?): String? {
        payload ?: return null
        return payload.stringOrNull("delta", "text", "summary", "part")
            ?: payload["event"].jsonObjectOrNull()?.stringOrNull("delta", "text")
            ?: payload["item"].jsonObjectOrNull()?.stringOrNull("delta", "text")
    }

    private fun extractCompletedText(payload: JsonObject?): String? {
        payload ?: return null
        payload.stringOrNull("message", "text", "summary")?.let { return it }
        payload["content"].jsonArrayOrNull()?.mapNotNull { element ->
            element.jsonObjectOrNull()?.stringOrNull("text")
        }?.joinToString("")?.takeIf { it.isNotBlank() }?.let { return it }
        payload["item"].jsonObjectOrNull()?.stringOrNull("text", "message")?.let { return it }
        payload["event"].jsonObjectOrNull()?.stringOrNull("text", "message", "summary")?.let { return it }
        return payload["error"].jsonObjectOrNull()?.stringOrNull("message")
    }

    private fun logError(message: String, error: Throwable) {
        Log.e("Remodex", message, error)
    }

    private fun websocketSessionUrl(savedSession: SavedRelaySession): String {
        val relay = savedSession.relay.trim().trimEnd('/')
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
        return "$relay/${savedSession.sessionId}"
    }

    private fun trustedSessionResolveUrl(relayUrl: String): String? {
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

    private fun RelaySocketTerminalEvent.toUserFacingError(): String = when (this) {
        is RelaySocketTerminalEvent.Closed -> when (code) {
            4001 -> "The relay rejected this phone because the Mac bridge is not connected for the scanned session. Restart the bridge and scan a fresh QR code."
            4002 -> "The Mac bridge is temporarily unavailable for this relay session. Try reconnecting."
            4003 -> "The relay says this pairing session is no longer valid. Scan a fresh QR code."
            4004 -> "The relay disconnected while the Mac was temporarily absent. Try reconnecting."
            else -> "Relay WebSocket closed${if (code != 1000) " with code $code" else ""}${reason.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}"
        }
        is RelaySocketTerminalEvent.Failure -> "Relay WebSocket failed${responseCode?.let { " with HTTP $it" } ?: ""}: $message"
    }

    private fun userFacingConnectionError(error: Throwable): String {
        val message = error.message ?: return "Could not connect to the relay."
        return when {
            message.contains("CLEARTEXT", ignoreCase = true) ->
                "Android blocked the local ws:// relay. Reinstall this build and try again."
            message.contains("Failed to connect", ignoreCase = true) ->
                "Could not reach the relay host. Confirm the phone and Mac are on the same network and the bridge is running."
            else -> message
        }
    }

    private fun redactRelayUrlForLog(url: String): String {
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

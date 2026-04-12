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
    private val messageHistoryStore: AndroidMessageHistoryStore,
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
    private val threadHistoryRefreshJobs = ConcurrentHashMap<String, Job>()

    private var secureSession: SecureSession? = null
    private var phoneIdentity: PhoneIdentityState = storage.getOrCreatePhoneIdentity()
    private var syncJob: Job? = null
    private var messageHistorySaveJob: Job? = null
    var isAppInForeground: AtomicBoolean = AtomicBoolean(true)

    private val persistedMessagesByThread = messageHistoryStore.load()

    private val _uiState = MutableStateFlow(
        RemodexUiState(
            hasSavedPairing = storage.readRelaySession() != null,
            relaySession = storage.readRelaySession(),
            trustedMacs = storage.readTrustedRegistry().records,
            messagesByThread = persistedMessagesByThread,
        ),
    )
    val uiState: StateFlow<RemodexUiState> = _uiState.asStateFlow()
    private val gitCoordinator = RemodexGitCoordinator(
        sendRequest = ::sendRequest,
        uiState = _uiState,
        scope = scope,
    )
    private val realtimeCoordinator = RemodexRealtimeCoordinator(
        uiState = _uiState,
        isAppInForeground = isAppInForeground,
        notificationServiceProvider = { notificationService },
        sendRpc = ::sendRpc,
        refreshGitStatus = ::gitStatus,
        handleRateLimitsUpdated = ::handleRateLimitsUpdated,
        scheduleMessageHistorySave = ::scheduleMessageHistorySave,
    )

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
        val currentState = _uiState.value.connectionState
        if (!forceQrBootstrap &&
            (currentState == RelayConnectionState.CONNECTING || currentState == RelayConnectionState.HANDSHAKING)
        ) {
            Log.d("Remodex", "connectFromSavedSession ignored because a connection attempt is already in progress")
            return
        }
        val savedSession = storage.readRelaySession()
        if (savedSession == null) {
            _uiState.update { it.copy(errorMessage = "No saved pairing. Scan a QR code first.") }
            return
        }

        stopSyncLoop()
        secureSession = null
        socketClient.disconnect()
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()

        _uiState.update {
            it.copy(
                connectionState = RelayConnectionState.CONNECTING,
                secureStatusLabel = "Connecting to relay",
                isConnected = false,
                errorMessage = null,
            )
        }
        try {
            val connectedSession = tryResolvedTrustedSession(
                savedSession = savedSession,
                storage = storage,
                phoneIdentity = phoneIdentity,
                okHttpClient = okHttpClient,
                json = json,
            ) ?: savedSession
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
        messageHistorySaveJob?.cancel()
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
                    "limit" to JsonPrimitive(60),
                ),
            ),
        )
        val root = response.result.jsonObjectOrNull() ?: return
        val page = root["data"].jsonArrayOrNull()
            ?: root["items"].jsonArrayOrNull()
            ?: root["threads"].jsonArrayOrNull()
            ?: JsonArray(emptyList())
        val threads = page.mapNotNull { it.jsonObjectOrNull()?.let(::threadSummaryFromJson) }
        var selectedThreadIdToRefresh: String? = null
        _uiState.update { state ->
            val resolvedSelectedThreadId = when {
                state.selectedThreadId != null && threads.any { thread -> thread.id == state.selectedThreadId } -> {
                    state.selectedThreadId
                }
                else -> threads.firstOrNull()?.id
            }
            val hasCachedMessages = resolvedSelectedThreadId?.let { threadId ->
                state.messagesByThread[threadId].orEmpty().isNotEmpty()
            } == true
            val shouldRefreshSelectedThread = resolvedSelectedThreadId != null &&
                (resolvedSelectedThreadId != state.selectedThreadId || !hasCachedMessages)
            if (shouldRefreshSelectedThread) {
                selectedThreadIdToRefresh = resolvedSelectedThreadId
            }
            state.copy(
                threads = threads,
                selectedThreadId = resolvedSelectedThreadId,
                loadingThreadIds = when {
                    resolvedSelectedThreadId == null -> state.loadingThreadIds
                    hasCachedMessages -> state.loadingThreadIds - resolvedSelectedThreadId
                    else -> state.loadingThreadIds + resolvedSelectedThreadId
                },
            )
        }
        gitCoordinator.prefetchGitStatusForThreads(threads)
        selectedThreadIdToRefresh?.let(::requestThreadHistoryRefresh)
    }

    fun hasCompletedOnboarding(): Boolean = storage.hasCompletedOnboarding()

    fun setOnboardingCompleted() = storage.setOnboardingCompleted()

    fun setErrorMessage(message: String?) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    suspend fun startThread(preferredProjectPath: String? = null) {
        val params = buildMap<String, JsonElement> {
            preferredProjectPath
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { put("cwd", JsonPrimitive(it)) }
        }
        val response = sendRequest("thread/start", JsonObject(params))
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

    fun readSidebarProjectOrder(): List<String> = storage.readSidebarProjectOrder()

    fun writeSidebarProjectOrder(order: List<String>) {
        storage.writeSidebarProjectOrder(order)
    }

    suspend fun openThread(threadId: String) {
        _uiState.update { state ->
            val hasCachedMessages = state.messagesByThread[threadId].orEmpty().isNotEmpty()
            state.copy(
                selectedThreadId = threadId,
                errorMessage = null,
                loadingThreadIds = if (hasCachedMessages) {
                    state.loadingThreadIds - threadId
                } else {
                    state.loadingThreadIds + threadId
                },
            )
        }
        requestThreadHistoryRefresh(threadId)
    }

    suspend fun sendTurn(threadId: String, text: String, attachments: List<ImageAttachment> = emptyList()) {
        val trimmed = text.trim()
        val sanitizedAttachments = attachments.map { it.sanitizedForMessage() }
        if (trimmed.isEmpty() && sanitizedAttachments.isEmpty()) {
            return
        }
        val localMessageId = UUID.randomUUID().toString()
        val localMessage = ConversationMessage(
            id = localMessageId,
            threadId = threadId,
            role = MessageRole.USER,
            text = trimmed,
            attachments = sanitizedAttachments,
        )
        appendMessage(threadId, localMessage)
        val inputItems = buildList {
            attachments.forEach { attachment ->
                val payloadDataUrl = attachment.payloadDataUrl?.trim().orEmpty()
                if (payloadDataUrl.isNotEmpty()) {
                    add(
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("image"),
                                "image_url" to JsonPrimitive(payloadDataUrl),
                            ),
                        ),
                    )
                }
            }
            if (trimmed.isNotEmpty()) {
                add(
                    JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("text"),
                            "text" to JsonPrimitive(trimmed),
                        ),
                    ),
                )
            }
        }
        val input = JsonArray(inputItems)
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
            updateMessageTurnId(threadId, localMessageId, turnId)
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

    private suspend fun refreshThreadHistory(threadId: String) {
        val response = sendRequest(
            method = "thread/read",
            params = JsonObject(
                mapOf(
                    "threadId" to JsonPrimitive(threadId),
                    "includeTurns" to JsonPrimitive(true),
                ),
            ),
        )
        val threadObject = response.result.jsonObjectOrNull()?.get("thread")?.jsonObjectOrNull()
        if (threadObject == null) {
            _uiState.update { state -> state.copy(loadingThreadIds = state.loadingThreadIds - threadId) }
            return
        }
        val contextWindowUsage = extractContextWindowUsage(threadObject)
            ?: extractContextWindowUsage(threadObject["usage"]?.jsonObjectOrNull())
        val history = withContext(Dispatchers.Default) {
            RemodexConversationHistoryParser.parseThreadHistory(threadId, threadObject)
        }
        var shouldPersist = false
        _uiState.update { state ->
            val mergedHistory = RemodexConversationHistoryParser.mergeThreadHistory(
                existing = state.messagesByThread[threadId].orEmpty(),
                history = history,
            )
            shouldPersist = mergedHistory != state.messagesByThread[threadId]
            state.copy(
                messagesByThread = state.messagesByThread + (threadId to mergedHistory),
                loadingThreadIds = state.loadingThreadIds - threadId,
                contextWindowUsageByThread = if (contextWindowUsage != null) {
                    state.contextWindowUsageByThread + mapOf(threadId to contextWindowUsage)
                } else {
                    state.contextWindowUsageByThread
                },
            )
        }
        if (shouldPersist) {
            scheduleMessageHistorySave()
        }
    }

    private fun requestThreadHistoryRefresh(threadId: String) {
        threadHistoryRefreshJobs[threadId]?.cancel()
        threadHistoryRefreshJobs[threadId] = scope.launch {
            try {
                refreshThreadHistory(threadId)
            } catch (error: Exception) {
                logError("refreshThreadHistory failed", error)
                _uiState.update { state ->
                    state.copy(
                        errorMessage = if (state.selectedThreadId == threadId) {
                            userFacingConnectionError(error)
                        } else {
                            state.errorMessage
                        },
                        loadingThreadIds = state.loadingThreadIds - threadId,
                    )
                }
            } finally {
                threadHistoryRefreshJobs.remove(threadId)
            }
        }
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
        threadHistoryRefreshJobs.values.forEach { it.cancel() }
        threadHistoryRefreshJobs.clear()
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
                runCatching { refreshThreadHistory(threadId) }
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
                        runCatching { refreshThreadHistory(threadId) }
                    }
                }
            }
        }
    }

    // ── Git actions ──────────────────────────────────────────────────

    suspend fun gitStatus(cwd: String): GitRepoSyncResult? {
        return gitCoordinator.gitStatus(cwd)
    }

    suspend fun gitCommit(cwd: String, message: String? = null): GitCommitResult? {
        return gitCoordinator.gitCommit(cwd, message)
    }

    suspend fun gitPush(cwd: String): GitPushResult? {
        return gitCoordinator.gitPush(cwd)
    }

    suspend fun gitPull(cwd: String): GitPullResult? {
        return gitCoordinator.gitPull(cwd)
    }

    suspend fun gitBranches(cwd: String): GitBranchesResult? {
        return gitCoordinator.gitBranches(cwd)
    }

    suspend fun gitDiff(cwd: String): GitDiffResult? {
        return gitCoordinator.gitDiff(cwd)
    }

    suspend fun gitCheckout(cwd: String, branch: String): GitCheckoutResult? {
        return gitCoordinator.gitCheckout(cwd, branch)
    }

    suspend fun gitCreateBranch(cwd: String, name: String): GitCreateBranchResult? {
        return gitCoordinator.gitCreateBranch(cwd, name)
    }

    suspend fun refreshUsageStatus(threadId: String?) {
        threadId?.trim()?.takeIf(String::isNotEmpty)?.let { refreshContextWindowUsage(it) }
        refreshRateLimits()
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

    // ── Usage status ────────────────────────────────────────────────

    suspend fun refreshContextWindowUsage(threadId: String) {
        val trimmedThreadId = threadId.trim()
        if (trimmedThreadId.isEmpty()) {
            return
        }

        val params = buildMap<String, JsonElement> {
            put("threadId", JsonPrimitive(trimmedThreadId))
            _uiState.value.activeTurnIdByThread[trimmedThreadId]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { put("turnId", JsonPrimitive(it)) }
        }

        runCatching {
            val response = sendRequest("thread/contextWindow/read", JsonObject(params))
            val resultObject = response.result.jsonObjectOrNull() ?: return
            val usage = extractContextWindowUsage(resultObject["usage"]?.jsonObjectOrNull())
                ?: extractContextWindowUsage(resultObject)
            if (usage != null) {
                _uiState.update {
                    it.copy(contextWindowUsageByThread = it.contextWindowUsageByThread + mapOf(trimmedThreadId to usage))
                }
            }
        }
    }

    suspend fun refreshRateLimits() {
        _uiState.update {
            it.copy(
                isLoadingRateLimits = true,
                rateLimitsErrorMessage = null,
            )
        }

        try {
            val response = fetchRateLimitsWithCompatRetry()
            val resultObject = response.result.jsonObjectOrNull()
                ?: throw IllegalStateException("account/rateLimits/read response missing payload")
            applyRateLimitsPayload(resultObject, mergeWithExisting = false)
            _uiState.update {
                it.copy(
                    hasResolvedRateLimitsSnapshot = true,
                    rateLimitsErrorMessage = null,
                )
            }
        } catch (error: Exception) {
            val recoveredPayload = recoverRateLimitsPayloadFromError(error.message)
            if (recoveredPayload != null) {
                applyRateLimitsPayload(recoveredPayload, mergeWithExisting = false)
                _uiState.update {
                    it.copy(
                        hasResolvedRateLimitsSnapshot = true,
                        rateLimitsErrorMessage = null,
                    )
                }
            } else {
                val message = sanitizedRateLimitsErrorMessage(error.message)
                _uiState.update {
                    it.copy(
                        rateLimitBuckets = emptyList(),
                        hasResolvedRateLimitsSnapshot = false,
                        rateLimitsErrorMessage = message,
                    )
                }
            }
        } finally {
            _uiState.update { it.copy(isLoadingRateLimits = false) }
        }
    }

    fun handleRateLimitsUpdated(params: JsonObject?) {
        params ?: return
        applyRateLimitsPayload(params, mergeWithExisting = true)
        _uiState.update {
            it.copy(
                hasResolvedRateLimitsSnapshot = true,
                rateLimitsErrorMessage = null,
            )
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
        if (_uiState.value.connectionState == RelayConnectionState.CONNECTING ||
            _uiState.value.connectionState == RelayConnectionState.HANDSHAKING) {
            return
        }
        Log.d("Remodex", "Attempting auto-reconnect on foreground resume")
        runCatching { connectFromSavedSession() }
    }

    private suspend fun fetchRateLimitsWithCompatRetry(): RpcMessage {
        return try {
            sendRequest("account/rateLimits/read", null)
        } catch (error: Exception) {
            if (!shouldRetryRateLimitsWithEmptyParams(error)) {
                throw error
            }
            sendRequest("account/rateLimits/read", JsonObject(emptyMap()))
        }
    }

    private fun applyRateLimitsPayload(
        payloadObject: JsonObject,
        mergeWithExisting: Boolean,
    ) {
        val decodedBuckets = decodeRateLimitBuckets(payloadObject)
        val resolvedBuckets = if (mergeWithExisting) {
            mergeRateLimitBuckets(_uiState.value.rateLimitBuckets, decodedBuckets)
        } else {
            decodedBuckets
        }
        val sortedBuckets = resolvedBuckets.sortedWith(
            compareBy<RateLimitBucket> { it.sortDurationMins }
                .thenBy { it.displayLabel.lowercase() },
        )
        _uiState.update { it.copy(rateLimitBuckets = sortedBuckets) }
    }

    private fun shouldRetryRateLimitsWithEmptyParams(error: Exception): Boolean {
        val lowered = error.message?.lowercase().orEmpty()
        return lowered.contains("invalid params") ||
            lowered.contains("invalid param") ||
            lowered.contains("failed to parse") ||
            lowered.contains("expected") ||
            lowered.contains("missing field `params`") ||
            lowered.contains("missing field params")
    }

    private fun recoverRateLimitsPayloadFromError(message: String?): JsonObject? {
        val rawMessage = message?.trim().orEmpty()
        if (rawMessage.isEmpty()) {
            return null
        }

        val bodyMarker = rawMessage.indexOf("body=")
        if (bodyMarker < 0) {
            return null
        }

        val jsonText = rawMessage.substring(bodyMarker + "body=".length).trim()
        if (!jsonText.startsWith("{")) {
            return null
        }

        return runCatching {
            json.parseToJsonElement(jsonText).jsonObjectOrNull()
        }.getOrNull()
    }

    private fun sanitizedRateLimitsErrorMessage(message: String?): String {
        val rawMessage = message?.trim().orEmpty()
        if (rawMessage.isEmpty()) {
            return "Unable to load rate limits."
        }

        val lowered = rawMessage.lowercase()
        return when {
            lowered.contains("failed to fetch codex rate limits") ||
                lowered.contains("decode error") ||
                lowered.contains("unknown variant") -> {
                    "Rate limits are temporarily unavailable for this account."
                }

            else -> rawMessage.lineSequence().firstOrNull()?.take(180)?.ifBlank {
                "Unable to load rate limits."
            } ?: "Unable to load rate limits."
        }
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
                if (error.code == "invalid_envelope") {
                    Log.d("Remodex", "Ignoring recoverable bridge secureError: ${error.code}")
                    return
                }
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
        if (envelope.sender != "mac") {
            _uiState.update {
                it.copy(
                    connectionState = RelayConnectionState.RECONNECT_REQUIRED,
                    errorMessage = "The secure Remodex payload could not be verified.",
                )
            }
            return
        }
        if (envelope.counter <= session.lastInboundCounter) {
            Log.d(
                "Remodex",
                "Ignoring stale secure envelope counter=${envelope.counter} last=${session.lastInboundCounter}",
            )
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
                realtimeCoordinator.handleServerRequest(message)
            } else {
                realtimeCoordinator.handleNotification(message.method, message.params.jsonObjectOrNull())
            }
            return
        }
        val requestKey = idKey(message.id)
        pendingRequests.remove(requestKey)?.complete(message)
    }

    private fun appendMessage(threadId: String, message: ConversationMessage) {
        _uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty() + message
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
        scheduleMessageHistorySave()
    }

    private fun updateMessageTurnId(threadId: String, messageId: String, turnId: String) {
        _uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty()
            val updated = messages.map { message ->
                if (message.id == messageId) {
                    message.copy(turnId = turnId)
                } else {
                    message
                }
            }
            state.copy(messagesByThread = state.messagesByThread + (threadId to updated))
        }
        scheduleMessageHistorySave()
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
            throw IllegalStateException("Relay WebSocket is not connected")
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

    private fun scheduleMessageHistorySave() {
        messageHistorySaveJob?.cancel()
        messageHistorySaveJob = scope.launch {
            delay(1_000L)
            messageHistoryStore.save(_uiState.value.messagesByThread)
        }
    }

    private fun logError(message: String, error: Throwable) {
        Log.e("Remodex", message, error)
    }

}

package com.remodex.android.core.data

import android.util.Log
import com.remodex.android.core.model.AccessMode
import com.remodex.android.core.model.ConversationMessage
import com.remodex.android.core.model.MessageKind
import com.remodex.android.core.model.MessageRole
import com.remodex.android.core.model.PendingApproval
import com.remodex.android.core.model.PlanState
import com.remodex.android.core.model.RemodexUiState
import com.remodex.android.core.model.RelayConnectionState
import com.remodex.android.core.model.RpcError
import com.remodex.android.core.model.RpcMessage
import com.remodex.android.core.model.StructuredUserInputRequest
import com.remodex.android.core.model.namespacedConversationMessageId
import com.remodex.android.core.model.jsonArrayOrNull
import com.remodex.android.core.model.jsonObjectOrNull
import com.remodex.android.core.model.parsePlanSteps
import com.remodex.android.core.model.parseStructuredInputQuestions
import com.remodex.android.core.model.stringOrNull
import com.remodex.android.core.model.threadSummaryFromJson
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class RemodexRealtimeCoordinator(
    private val uiState: MutableStateFlow<RemodexUiState>,
    private val isAppInForeground: AtomicBoolean,
    private val notificationServiceProvider: () -> com.remodex.android.core.notification.RemodexNotificationService?,
    private val sendRpc: suspend (RpcMessage) -> Unit,
    private val refreshGitStatus: suspend (String) -> Unit,
    private val requestThreadHistoryRefresh: (String) -> Unit,
    private val handleRateLimitsUpdated: (JsonObject?) -> Unit,
    private val scheduleMessageHistorySave: () -> Unit,
) {
    suspend fun handleServerRequest(message: RpcMessage) {
        val method = message.method ?: return
        val params = message.params.jsonObjectOrNull()

        if (method.endsWith("requestApproval")) {
            if (uiState.value.selectedAccessMode == AccessMode.FULL_ACCESS) {
                sendRpc(
                    RpcMessage(
                        id = message.id,
                        result = JsonObject(mapOf("decision" to JsonPrimitive("accept"))),
                    ),
                )
                return
            }
            uiState.update {
                it.copy(
                    pendingApproval = PendingApproval(
                        requestKey = com.remodex.android.core.model.idKey(message.id),
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

        if (method == "item/tool/requestUserInput" || method == "tool/requestUserInput") {
            if (params != null) {
                val questions = parseStructuredInputQuestions(params)
                if (questions.isNotEmpty()) {
                    val request = StructuredUserInputRequest(
                        requestId = message.id ?: JsonNull,
                        threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: "",
                        turnId = extractTurnId(params),
                        itemId = extractItemId(params),
                        questions = questions,
                    )
                    uiState.update {
                        it.copy(structuredInputRequests = it.structuredInputRequests + request)
                    }
                    if (!isAppInForeground.get()) {
                        notificationServiceProvider()?.notifyStructuredInput(
                            request.threadId,
                            threadTitle(request.threadId),
                            questions.size,
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

    suspend fun handleNotification(method: String, params: JsonObject?) {
        when (method) {
            "thread/started" -> {
                val thread = params?.get("thread")?.jsonObjectOrNull()?.let(::threadSummaryFromJson)
                    ?: params?.let(::threadSummaryFromJson)
                if (thread != null) {
                    uiState.update {
                        it.copy(threads = listOf(thread) + it.threads.filterNot { existing -> existing.id == thread.id })
                    }
                }
            }

            "thread/listChanged" -> {
                val threadsArray = params?.get("threads")?.jsonArrayOrNull()
                if (threadsArray != null) {
                    val threads = threadsArray.mapNotNull { it.jsonObjectOrNull()?.let(::threadSummaryFromJson) }
                    uiState.update { it.copy(threads = threads) }
                }
            }

            "turn/started" -> {
                val threadId = extractThreadId(params) ?: return
                val turnId = extractTurnId(params) ?: return
                uiState.update {
                    it.copy(
                        activeTurnIdByThread = it.activeTurnIdByThread + (threadId to turnId),
                        runningThreadIds = it.runningThreadIds + threadId,
                    )
                }
            }

            "turn/completed", "turn/failed" -> {
                val threadId = extractThreadId(params) ?: return
                val isSuccess = method == "turn/completed"
                uiState.update {
                    it.copy(
                        activeTurnIdByThread = it.activeTurnIdByThread - threadId,
                        runningThreadIds = it.runningThreadIds - threadId,
                        planStateByThread = it.planStateByThread - threadId,
                    )
                }
                requestThreadHistoryRefresh(threadId)
                if (!isAppInForeground.get()) {
                    notificationServiceProvider()?.notifyRunCompletion(threadId, threadTitle(threadId), isSuccess)
                }
            }

            "turn/plan/updated", "turn/planUpdated" -> {
                val turnId = extractTurnId(params) ?: return
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                val explanation = params?.stringOrNull("explanation")
                val steps = params?.let(::parsePlanSteps).orEmpty()
                uiState.update {
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
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                val delta = extractDelta(params) ?: return
                uiState.update { state ->
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

            "item/reasoning/delta", "codex/event/reasoning_delta" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                appendThinkingDelta(threadId, extractTurnId(params), extractItemId(params), extractDelta(params) ?: return)
            }

            "item/fileChange/outputDelta" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                appendFileChangeDelta(
                    threadId = threadId,
                    turnId = extractTurnId(params),
                    itemId = extractItemId(params),
                    delta = extractDelta(params) ?: return,
                )
            }

            "item/toolCall/outputDelta",
            "item/toolCall/output_delta",
            "item/tool_call/outputDelta",
            "item/tool_call/output_delta" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                val decoded = RemodexFileChangeFormatter.decodePayloadText(params)
                val delta = decoded ?: extractDelta(params) ?: return
                appendFileChangeDelta(
                    threadId = threadId,
                    turnId = extractTurnId(params),
                    itemId = extractItemId(params),
                    delta = delta,
                )
            }

            "item/toolUse/started", "item/tool/started", "codex/event/tool_use_begin" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                val turnId = extractTurnId(params)
                val itemId = extractItemId(params)
                val toolName = params?.stringOrNull("name", "tool", "toolName") ?: "tool"
                appendMessage(threadId, ConversationMessage(
                    id = itemId?.let {
                        namespacedConversationMessageId(it, MessageRole.ASSISTANT, MessageKind.TOOL_ACTIVITY)
                    } ?: "tool-${UUID.randomUUID()}",
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
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                val itemId = extractItemId(params) ?: return
                var shouldPersist = false
                uiState.update { state ->
                    val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
                    val idx = messages.indexOfLast { it.itemId == itemId && it.kind == MessageKind.TOOL_ACTIVITY }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(isStreaming = false)
                        shouldPersist = true
                        state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
                    } else {
                        state
                    }
                }
                if (shouldPersist) {
                    scheduleMessageHistorySave()
                }
            }

            "item/commandExecution/outputDelta",
            "item/command_execution/outputDelta",
            "item/commandExecution/terminalInteraction",
            "item/command_execution/terminalInteraction",
            "codex/event/exec_command_begin",
            "codex/event/exec_command_output_delta",
            "codex/event/exec_command_end" -> {
                handleCommandExecutionEvent(method, params)
            }

            "item/agentMessage/delta", "codex/event/agent_message_content_delta", "codex/event/agent_message_delta" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                appendAssistantDelta(threadId, extractTurnId(params), extractItemId(params), extractDelta(params) ?: return)
            }

            "item/completed", "codex/event/item_completed", "codex/event/agent_message" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                val fileChangeText = RemodexFileChangeFormatter.decodePayloadText(params)
                if (!fileChangeText.isNullOrBlank()) {
                    completeFileChangeMessage(
                        threadId = threadId,
                        turnId = extractTurnId(params),
                        itemId = extractItemId(params),
                        text = fileChangeText,
                    )
                    return
                }
                val text = extractCompletedText(params)
                if (!text.isNullOrBlank()) {
                    completeAssistantMessage(threadId, extractTurnId(params), extractItemId(params), text)
                }
            }

            "turn/diff/updated", "codex/event/turn_diff_updated", "codex/event/turn_diff" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
                val diffText = params?.stringOrNull("diff", "unified_diff")
                    ?: params?.get("event")?.jsonObjectOrNull()?.stringOrNull("diff", "unified_diff")
                    ?: return
                completeFileChangeMessage(
                    threadId = threadId,
                    turnId = extractTurnId(params),
                    itemId = extractItemId(params),
                    text = RemodexFileChangeFormatter.decodePayloadText(
                        JsonObject(mapOf("diff" to JsonPrimitive(diffText))),
                    ) ?: return,
                )
            }

            "repo/changed", "repo/refreshSignal" -> {
                val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId
                val cwd = threadId?.let { tid -> uiState.value.threads.find { it.id == tid }?.cwd }
                if (cwd != null) {
                    runCatching { refreshGitStatus(cwd) }
                }
            }

            "account/rateLimitsUpdated", "account/rateLimits/updated" -> {
                handleRateLimitsUpdated(params)
            }

            "ui/bridgeUpdateAvailable" -> {
                val message = params?.stringOrNull("message") ?: "A bridge update is available."
                uiState.update { it.copy(errorMessage = message) }
            }

            "error", "turn/error", "codex/event/error" -> {
                uiState.update { it.copy(errorMessage = extractCompletedText(params) ?: "Runtime error.") }
            }
        }
    }

    private fun appendAssistantDelta(threadId: String, turnId: String?, itemId: String?, delta: String) {
        uiState.update { state ->
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
                    id = itemId?.let {
                        namespacedConversationMessageId(it, MessageRole.ASSISTANT, MessageKind.CHAT)
                    } ?: "assistant-${UUID.randomUUID()}",
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
        scheduleMessageHistorySave()
    }

    private fun completeAssistantMessage(threadId: String, turnId: String?, itemId: String?, text: String) {
        uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
            val existingIndex = messages.indexOfLast {
                it.role == MessageRole.ASSISTANT &&
                    ((itemId != null && it.itemId == itemId) || (turnId != null && it.turnId == turnId))
            }
            if (existingIndex >= 0) {
                messages[existingIndex] = messages[existingIndex].copy(text = text, isStreaming = false)
            } else {
                messages += ConversationMessage(
                    id = itemId?.let {
                        namespacedConversationMessageId(it, MessageRole.ASSISTANT, MessageKind.CHAT)
                    } ?: "assistant-${UUID.randomUUID()}",
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
        scheduleMessageHistorySave()
    }

    private fun appendThinkingDelta(threadId: String, turnId: String?, itemId: String?, delta: String) {
        uiState.update { state ->
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
                    id = itemId?.let {
                        namespacedConversationMessageId(it, MessageRole.ASSISTANT, MessageKind.THINKING)
                    } ?: "thinking-${UUID.randomUUID()}",
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
        scheduleMessageHistorySave()
    }

    private fun appendFileChangeDelta(threadId: String, turnId: String?, itemId: String?, delta: String) {
        uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
            val existingIndex = messages.indexOfLast {
                it.kind == MessageKind.FILE_CHANGE &&
                    ((itemId != null && it.itemId == itemId) || (turnId != null && it.turnId == turnId)) &&
                    it.isStreaming
            }
            if (existingIndex >= 0) {
                val current = messages[existingIndex]
                messages[existingIndex] = current.copy(text = current.text + delta, isStreaming = true)
            } else {
                messages += ConversationMessage(
                    id = itemId?.let {
                        namespacedConversationMessageId(it, MessageRole.SYSTEM, MessageKind.FILE_CHANGE)
                    } ?: "file-change-${UUID.randomUUID()}",
                    threadId = threadId,
                    role = MessageRole.SYSTEM,
                    kind = MessageKind.FILE_CHANGE,
                    text = delta,
                    turnId = turnId,
                    itemId = itemId,
                    isStreaming = true,
                )
            }
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
        scheduleMessageHistorySave()
    }

    private fun completeFileChangeMessage(threadId: String, turnId: String?, itemId: String?, text: String) {
        uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
            val existingIndex = messages.indexOfLast {
                it.kind == MessageKind.FILE_CHANGE &&
                    ((itemId != null && it.itemId == itemId) || (turnId != null && it.turnId == turnId))
            }
            if (existingIndex >= 0) {
                messages[existingIndex] = messages[existingIndex].copy(
                    role = MessageRole.SYSTEM,
                    text = text,
                    turnId = turnId ?: messages[existingIndex].turnId,
                    itemId = itemId ?: messages[existingIndex].itemId,
                    isStreaming = false,
                )
            } else {
                messages += ConversationMessage(
                    id = itemId?.let {
                        namespacedConversationMessageId(it, MessageRole.SYSTEM, MessageKind.FILE_CHANGE)
                    } ?: "file-change-${UUID.randomUUID()}",
                    threadId = threadId,
                    role = MessageRole.SYSTEM,
                    kind = MessageKind.FILE_CHANGE,
                    text = text,
                    turnId = turnId,
                    itemId = itemId,
                    isStreaming = false,
                )
            }
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
        scheduleMessageHistorySave()
    }

    private fun handleCommandExecutionEvent(method: String, params: JsonObject?) {
        val threadId = extractThreadId(params) ?: uiState.value.selectedThreadId ?: return
        val turnId = extractTurnId(params)
        val itemId = extractItemId(params)
        val state = RemodexCommandExecutionFormatter.decode(method, params)
        val existing = itemId?.let { existingCommandExecutionMessage(threadId, it) }

        if (existing != null && !existing.isStreaming && state.phase == CommandExecutionPhase.RUNNING) {
            return
        }
        if (existing == null && state.shortCommand == "command" && state.phase == CommandExecutionPhase.RUNNING) {
            return
        }

        upsertCommandExecutionMessage(
            threadId = threadId,
            turnId = turnId,
            itemId = itemId,
            text = "${state.phase.label} ${state.shortCommand}",
            isStreaming = state.phase == CommandExecutionPhase.RUNNING,
        )
    }

    private fun existingCommandExecutionMessage(threadId: String, itemId: String): ConversationMessage? {
        return uiState.value.messagesByThread[threadId]
            ?.lastOrNull { it.itemId == itemId && it.kind == MessageKind.COMMAND_EXECUTION }
    }

    private fun upsertCommandExecutionMessage(
        threadId: String,
        turnId: String?,
        itemId: String?,
        text: String,
        isStreaming: Boolean,
    ) {
        uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty().toMutableList()
            val existingIndex = messages.indexOfLast {
                it.kind == MessageKind.COMMAND_EXECUTION &&
                    (
                        (itemId != null && it.itemId == itemId) ||
                            (itemId == null && turnId != null && it.turnId == turnId)
                        )
            }
            if (existingIndex >= 0) {
                messages[existingIndex] = messages[existingIndex].copy(
                    role = MessageRole.SYSTEM,
                    text = text,
                    turnId = turnId ?: messages[existingIndex].turnId,
                    itemId = itemId ?: messages[existingIndex].itemId,
                    isStreaming = isStreaming,
                )
            } else {
                messages += ConversationMessage(
                    id = itemId?.let {
                        namespacedConversationMessageId(it, MessageRole.SYSTEM, MessageKind.COMMAND_EXECUTION)
                    } ?: "command-${UUID.randomUUID()}",
                    threadId = threadId,
                    role = MessageRole.SYSTEM,
                    kind = MessageKind.COMMAND_EXECUTION,
                    text = text,
                    turnId = turnId,
                    itemId = itemId,
                    isStreaming = isStreaming,
                )
            }
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
        scheduleMessageHistorySave()
    }

    private fun appendMessage(threadId: String, message: ConversationMessage) {
        uiState.update { state ->
            val messages = state.messagesByThread[threadId].orEmpty() + message
            state.copy(messagesByThread = state.messagesByThread + (threadId to messages))
        }
        scheduleMessageHistorySave()
    }

    private fun threadTitle(threadId: String): String {
        return uiState.value.threads.find { it.id == threadId }?.title ?: "Dex"
    }
}

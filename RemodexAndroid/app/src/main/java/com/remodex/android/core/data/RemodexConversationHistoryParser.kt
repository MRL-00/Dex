package com.remodex.android.core.data

import com.remodex.android.core.attachment.ImageAttachmentPipeline
import com.remodex.android.core.model.ConversationMessage
import com.remodex.android.core.model.ImageAttachment
import com.remodex.android.core.model.MessageKind
import com.remodex.android.core.model.MessageRole
import com.remodex.android.core.model.jsonArrayOrNull
import com.remodex.android.core.model.jsonObjectOrNull
import com.remodex.android.core.model.longOrNull
import com.remodex.android.core.model.namespacedConversationMessageId
import com.remodex.android.core.model.stringOrNull
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal object RemodexConversationHistoryParser {
    fun parseThreadHistory(threadId: String, threadObject: JsonObject): List<ConversationMessage> {
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

    fun mergeThreadHistory(
        existing: List<ConversationMessage>,
        history: List<ConversationMessage>,
    ): List<ConversationMessage> {
        if (existing.isEmpty()) {
            return history.sortedBy { it.createdAtMillis }
        }
        if (history.isEmpty()) {
            return existing.sortedBy { it.createdAtMillis }
        }

        val merged = existing.toMutableList()
        for (message in history) {
            val existingIndex = merged.indexOfLast { candidate -> matchesHistoryMessage(candidate, message) }
            if (existingIndex >= 0) {
                val current = merged[existingIndex]
                val preservedText = if (current.isStreaming && current.text.length > message.text.length) {
                    current.text
                } else {
                    message.text
                }
                merged[existingIndex] = message.copy(
                    text = preservedText,
                    attachments = if (message.attachments.isNotEmpty()) message.attachments else current.attachments,
                    isStreaming = current.isStreaming && preservedText == current.text,
                    createdAtMillis = minOf(current.createdAtMillis, message.createdAtMillis),
                )
            } else {
                merged += message
            }
        }
        return merged.sortedBy { it.createdAtMillis }
    }

    private fun parseTurnInput(
        threadId: String,
        turnId: String?,
        turnObject: JsonObject,
        out: MutableList<ConversationMessage>,
        seen: MutableSet<String>,
    ) {
        val inputItems = turnObject["input"].jsonArrayOrNull() ?: return
        val attachments = inputItems.mapNotNull { item ->
            parseImageAttachment(item.jsonObjectOrNull())
        }
        val text = inputItems.mapNotNull { item ->
            item.jsonObjectOrNull()?.stringOrNull("text")
        }.joinToString("\n").trim()
        if (text.isBlank() && attachments.isEmpty()) {
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
            attachments = attachments,
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

    private fun matchesHistoryMessage(
        existing: ConversationMessage,
        incoming: ConversationMessage,
    ): Boolean {
        if (existing.id == incoming.id) {
            return true
        }
        if (existing.itemId != null && existing.itemId == incoming.itemId && existing.kind == incoming.kind) {
            return true
        }
        return existing.role == MessageRole.USER &&
            incoming.role == MessageRole.USER &&
            existing.turnId != null &&
            existing.turnId == incoming.turnId &&
            existing.text == incoming.text
    }

    private fun historyMessageFromObject(threadId: String, turnId: String?, item: JsonObject): ConversationMessage? {
        val type = item.stringOrNull("type")?.lowercase().orEmpty()
        val isCommandExecution = type.contains("commandexecution")
            || type.contains("command_execution")
            || type.contains("exec_command")
        val kind = when {
            type.contains("reasoning") -> MessageKind.THINKING
            isCommandExecution -> MessageKind.COMMAND_EXECUTION
            else -> MessageKind.CHAT
        }
        val role = when {
            isCommandExecution -> MessageRole.SYSTEM
            item.stringOrNull("role") == "user" || type.contains("user") -> MessageRole.USER
            item.stringOrNull("role") == "assistant" || type.contains("agent_message") || type.contains("assistant") -> MessageRole.ASSISTANT
            else -> MessageRole.SYSTEM
        }
        val text = if (kind == MessageKind.COMMAND_EXECUTION) {
            RemodexCommandExecutionFormatter.historyText(item, type)
        } else {
            extractCompletedText(item) ?: extractDelta(item)
        } ?: return null
        val id = item.stringOrNull("id", "itemId", "item_id", "messageId", "message_id")
            ?.let { rawId -> namespacedConversationMessageId(rawId, role, kind) }
            ?: "${role.name.lowercase()}-${UUID.randomUUID()}"
        return ConversationMessage(
            id = id,
            threadId = threadId,
            role = role,
            kind = kind,
            text = text,
            attachments = emptyList(),
            turnId = extractTurnId(item) ?: turnId,
            itemId = item.stringOrNull("itemId", "item_id", "callId", "call_id"),
            createdAtMillis = item.longOrNull("createdAt", "created_at") ?: System.currentTimeMillis(),
            isStreaming = false,
        )
    }

    private fun parseImageAttachment(item: JsonObject?): ImageAttachment? {
        item ?: return null
        val type = item.stringOrNull("type")?.lowercase().orEmpty()
        if (type != "image" && type != "local_image" && type != "localimage") {
            return null
        }
        val source = item.stringOrNull("image_url", "url", "path") ?: return null
        return if (source.startsWith("data:image", ignoreCase = true)) {
            ImageAttachmentPipeline.makeAttachmentFromDataUrl(source, includePayloadDataUrl = false)
        } else {
            null
        }
    }
}

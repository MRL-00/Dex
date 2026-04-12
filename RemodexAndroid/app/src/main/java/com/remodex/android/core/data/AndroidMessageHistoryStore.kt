package com.remodex.android.core.data

import android.content.Context
import com.remodex.android.core.model.ConversationMessage
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AndroidMessageHistoryStore(
    context: Context,
    private val json: Json,
) {
    private val storeFile = File(context.filesDir, "message-history-v1.json")
    private val tempFile = File(context.filesDir, "message-history-v1.tmp")

    fun load(): Map<String, List<ConversationMessage>> {
        val payload = runCatching { storeFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        return runCatching {
            json.decodeFromString(PersistedConversationHistory.serializer(), payload).messagesByThread
        }.getOrDefault(emptyMap())
    }

    fun save(messagesByThread: Map<String, List<ConversationMessage>>) {
        val payload = runCatching {
            json.encodeToString(
                PersistedConversationHistory.serializer(),
                PersistedConversationHistory(messagesByThread = messagesByThread),
            )
        }.getOrNull() ?: return

        runCatching {
            tempFile.writeText(payload, Charsets.UTF_8)
            tempFile.copyTo(storeFile, overwrite = true)
            tempFile.delete()
        }
    }
}

@Serializable
private data class PersistedConversationHistory(
    val messagesByThread: Map<String, List<ConversationMessage>> = emptyMap(),
)

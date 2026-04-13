package com.mattl_nz.dex.core.data

import com.mattl_nz.dex.core.model.jsonArrayOrNull
import com.mattl_nz.dex.core.model.jsonObjectOrNull
import com.mattl_nz.dex.core.model.stringOrNull
import kotlinx.serialization.json.JsonObject

internal fun extractThreadId(payload: JsonObject?): String? {
    payload ?: return null
    return payload.stringOrNull("threadId", "thread_id", "conversationId", "conversation_id")
        ?: payload["thread"].jsonObjectOrNull()?.stringOrNull("id")
        ?: payload["turn"].jsonObjectOrNull()?.stringOrNull("threadId", "thread_id")
        ?: payload["event"].jsonObjectOrNull()?.stringOrNull("threadId", "thread_id")
}

internal fun extractTurnId(payload: JsonObject?): String? {
    payload ?: return null
    return payload.stringOrNull("turnId", "turn_id")
        ?: payload["turn"].jsonObjectOrNull()?.stringOrNull("id")
        ?: payload["event"].jsonObjectOrNull()?.stringOrNull("turnId", "turn_id")
        ?: payload["item"].jsonObjectOrNull()?.stringOrNull("turnId", "turn_id")
}

internal fun extractItemId(payload: JsonObject?): String? {
    payload ?: return null
    return payload.stringOrNull("itemId", "item_id", "callId", "call_id", "messageId", "message_id")
        ?: payload["item"].jsonObjectOrNull()?.stringOrNull("id", "itemId", "item_id")
}

internal fun extractDelta(payload: JsonObject?): String? {
    payload ?: return null
    return payload.stringOrNull("delta", "text", "summary", "part")
        ?: payload["event"].jsonObjectOrNull()?.stringOrNull("delta", "text")
        ?: payload["item"].jsonObjectOrNull()?.stringOrNull("delta", "text")
}

internal fun extractCompletedText(payload: JsonObject?): String? {
    payload ?: return null
    payload.stringOrNull("message", "text", "summary")?.let { return it }
    payload["content"].jsonArrayOrNull()?.mapNotNull { element ->
        element.jsonObjectOrNull()?.stringOrNull("text")
    }?.joinToString("")?.takeIf { it.isNotBlank() }?.let { return it }
    payload["item"].jsonObjectOrNull()?.stringOrNull("text", "message")?.let { return it }
    payload["event"].jsonObjectOrNull()?.stringOrNull("text", "message", "summary")?.let { return it }
    return payload["error"].jsonObjectOrNull()?.stringOrNull("message")
}

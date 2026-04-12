package com.remodex.android.core.model

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class ModelOption(
    val id: String,
    val model: String,
    val displayName: String,
    val isDefault: Boolean,
    val supportedReasoningEfforts: List<String>,
    val defaultReasoningEffort: String?,
)

enum class ReasoningEffort(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high");

    companion object {
        fun fromString(value: String?): ReasoningEffort? = when (value?.lowercase()) {
            "low" -> LOW
            "medium" -> MEDIUM
            "high" -> HIGH
            else -> null
        }
    }
}

enum class ServiceTier(val value: String) {
    DEFAULT("default"),
    FAST("fast");

    companion object {
        fun fromString(value: String?): ServiceTier = when (value?.lowercase()) {
            "fast" -> FAST
            else -> DEFAULT
        }
    }
}

enum class AccessMode(val policyValue: String) {
    ON_REQUEST("on-request"),
    FULL_ACCESS("never");

    companion object {
        fun fromString(value: String?): AccessMode = when (value?.lowercase()) {
            "never", "fullaccess", "full_access" -> FULL_ACCESS
            else -> ON_REQUEST
        }
    }
}

data class ThreadRuntimeOverride(
    val model: String? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val serviceTier: ServiceTier? = null,
)

fun parseModelOptions(items: List<*>): List<ModelOption> {
    return items.mapNotNull { item ->
        val obj = (item as? kotlinx.serialization.json.JsonElement)?.jsonObject ?: return@mapNotNull null
        val id = obj.stringOrNull("id") ?: return@mapNotNull null
        val model = obj.stringOrNull("model") ?: id
        val displayName = obj.stringOrNull("displayName", "display_name") ?: model
        val isDefault = obj.boolOrNull("isDefault", "is_default") ?: false
        val efforts = obj["supportedReasoningEfforts"]?.jsonArray?.mapNotNull { e ->
            e.jsonObject.stringOrNull("reasoningEffort", "reasoning_effort")
        } ?: emptyList()
        val defaultEffort = obj.stringOrNull("defaultReasoningEffort", "default_reasoning_effort")

        ModelOption(
            id = id,
            model = model,
            displayName = displayName,
            isDefault = isDefault,
            supportedReasoningEfforts = efforts,
            defaultReasoningEffort = defaultEffort,
        )
    }
}

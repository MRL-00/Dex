package com.mattl_nz.dex.core.model

import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ContextWindowUsage(
    val tokensUsed: Int,
    val tokenLimit: Int,
) {
    val tokensRemaining: Int
        get() = maxOf(0, tokenLimit - tokensUsed)

    val fractionUsed: Float
        get() = if (tokenLimit > 0) {
            (tokensUsed.toFloat() / tokenLimit.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val percentUsed: Int
        get() = (fractionUsed * 100f).roundToInt()

    val percentRemaining: Int
        get() = maxOf(0, 100 - percentUsed)

    val tokensUsedFormatted: String
        get() = formatCompactTokenCount(tokensUsed)

    val tokenLimitFormatted: String
        get() = formatCompactTokenCount(tokenLimit)
}

data class RateLimitWindow(
    val usedPercent: Int,
    val windowDurationMins: Int?,
    val resetsAtEpochMillis: Long?,
) {
    val clampedUsedPercent: Int
        get() = usedPercent.coerceIn(0, 100)

    val remainingPercent: Int
        get() = maxOf(0, 100 - clampedUsedPercent)
}

data class RateLimitDisplayRow(
    val id: String,
    val label: String,
    val window: RateLimitWindow,
)

data class RateLimitBucket(
    val limitId: String,
    val limitName: String?,
    val primary: RateLimitWindow?,
    val secondary: RateLimitWindow?,
) {
    val id: String
        get() = limitId

    val primaryOrSecondary: RateLimitWindow?
        get() = primary ?: secondary

    val displayRows: List<RateLimitDisplayRow>
        get() = buildList {
            primary?.let { window ->
                add(
                    RateLimitDisplayRow(
                        id = "$limitId-primary",
                        label = durationLabel(window.windowDurationMins) ?: (limitName ?: limitId),
                        window = window,
                    ),
                )
            }
            secondary?.let { window ->
                add(
                    RateLimitDisplayRow(
                        id = "$limitId-secondary",
                        label = durationLabel(window.windowDurationMins) ?: (limitName ?: limitId),
                        window = window,
                    ),
                )
            }
        }

    val sortDurationMins: Int
        get() = primaryOrSecondary?.windowDurationMins ?: Int.MAX_VALUE

    val displayLabel: String
        get() = durationLabel(primaryOrSecondary?.windowDurationMins)
            ?: limitName?.trim()?.takeIf(String::isNotEmpty)
            ?: limitId

    companion object {
        fun visibleDisplayRows(buckets: List<RateLimitBucket>): List<RateLimitDisplayRow> {
            val dedupedByLabel = linkedMapOf<String, RateLimitDisplayRow>()
            for (row in buckets.flatMap { it.displayRows }) {
                val current = dedupedByLabel[row.label]
                dedupedByLabel[row.label] = if (current == null) {
                    row
                } else {
                    preferredDisplayRow(current, row)
                }
            }

            return dedupedByLabel.values.sortedWith(
                compareBy<RateLimitDisplayRow> { it.window.windowDurationMins ?: Int.MAX_VALUE }
                    .thenBy { it.label.lowercase() },
            )
        }

        private fun preferredDisplayRow(
            current: RateLimitDisplayRow,
            candidate: RateLimitDisplayRow,
        ): RateLimitDisplayRow {
            if (candidate.window.clampedUsedPercent != current.window.clampedUsedPercent) {
                return if (candidate.window.clampedUsedPercent > current.window.clampedUsedPercent) {
                    candidate
                } else {
                    current
                }
            }

            return when {
                current.window.resetsAtEpochMillis == null && candidate.window.resetsAtEpochMillis != null -> candidate
                current.window.resetsAtEpochMillis != null && candidate.window.resetsAtEpochMillis == null -> current
                current.window.resetsAtEpochMillis != null &&
                    candidate.window.resetsAtEpochMillis != null &&
                    candidate.window.resetsAtEpochMillis < current.window.resetsAtEpochMillis -> candidate
                else -> current
            }
        }
    }
}

fun extractContextWindowUsage(payload: JsonObject?): ContextWindowUsage? {
    payload ?: return null

    val root = JsonSearchRoot(JsonSearchValue.ObjectWrapper(payload))
    val tokensUsed = firstIntForAnyKey(
        keys = listOf(
            "tokensUsed",
            "tokens_used",
            "totalTokens",
            "total_tokens",
            "usedTokens",
            "used_tokens",
            "inputTokens",
            "input_tokens",
        ),
        root = root,
    )

    val explicitLimit = firstIntForAnyKey(
        keys = listOf(
            "tokenLimit",
            "token_limit",
            "maxTokens",
            "max_tokens",
            "contextWindow",
            "context_window",
            "contextSize",
            "context_size",
            "maxContextTokens",
            "max_context_tokens",
            "inputTokenLimit",
            "input_token_limit",
            "maxInputTokens",
            "max_input_tokens",
        ),
        root = root,
    )

    val tokensRemaining = firstIntForAnyKey(
        keys = listOf(
            "tokensRemaining",
            "tokens_remaining",
            "remainingTokens",
            "remaining_tokens",
            "remainingInputTokens",
            "remaining_input_tokens",
        ),
        root = root,
    )

    val resolvedTokensUsed = maxOf(0, tokensUsed ?: 0)
    val resolvedTokenLimit = explicitLimit ?: tokensRemaining?.let { resolvedTokensUsed + maxOf(0, it) }
    if (resolvedTokenLimit == null || resolvedTokenLimit <= 0) {
        return null
    }

    return ContextWindowUsage(
        tokensUsed = minOf(resolvedTokensUsed, resolvedTokenLimit),
        tokenLimit = resolvedTokenLimit,
    )
}

fun extractContextWindowUsageFromTokenCountPayload(payload: JsonObject?): ContextWindowUsage? {
    payload ?: return null

    val infoObject = payload["info"]?.jsonObjectOrNull() ?: payload
    val infoRoot = JsonSearchRoot(JsonSearchValue.ObjectWrapper(infoObject))
    val lastUsageRoot = firstValueForAnyKey(listOf("last_token_usage", "lastTokenUsage"), infoRoot)
    val totalUsageRoot = firstValueForAnyKey(
        listOf("total_token_usage", "totalTokenUsage", "last_token_usage", "lastTokenUsage"),
        infoRoot,
    ) ?: JsonSearchValue.ObjectWrapper(infoObject)
    val preferredUsageRoot = lastUsageRoot ?: totalUsageRoot

    val explicitTotal = firstIntForAnyKey(listOf("total_tokens", "totalTokens"), JsonSearchRoot(preferredUsageRoot))
    val inputTokens = firstIntForAnyKey(listOf("input_tokens", "inputTokens"), JsonSearchRoot(preferredUsageRoot)) ?: 0
    val outputTokens = firstIntForAnyKey(listOf("output_tokens", "outputTokens"), JsonSearchRoot(preferredUsageRoot)) ?: 0
    val reasoningTokens = firstIntForAnyKey(
        listOf("reasoning_output_tokens", "reasoningOutputTokens"),
        JsonSearchRoot(preferredUsageRoot),
    ) ?: 0
    val tokenLimit = firstIntForAnyKey(
        listOf(
            "model_context_window",
            "modelContextWindow",
            "context_window",
            "contextWindow",
            "tokenLimit",
            "token_limit",
        ),
        infoRoot,
    )

    if (tokenLimit == null || tokenLimit <= 0) {
        return null
    }

    val resolvedTokensUsed = explicitTotal ?: (inputTokens + outputTokens + reasoningTokens)
    return ContextWindowUsage(
        tokensUsed = minOf(resolvedTokensUsed, tokenLimit),
        tokenLimit = tokenLimit,
    )
}

fun decodeRateLimitBuckets(payloadObject: JsonObject): List<RateLimitBucket> {
    decodeWhamUsageBuckets(payloadObject).takeIf { it.isNotEmpty() }?.let { return it }

    val keyedBuckets = payloadObject["rateLimitsByLimitId"]?.jsonObjectOrNull()
        ?: payloadObject["rate_limits_by_limit_id"]?.jsonObjectOrNull()
    if (keyedBuckets != null) {
        return keyedBuckets.mapNotNull { (limitId, value) ->
            decodeRateLimitBucket(limitId, value)
        }
    }

    val nestedBuckets = payloadObject["rateLimits"]?.jsonObjectOrNull()
        ?: payloadObject["rate_limits"]?.jsonObjectOrNull()
    if (nestedBuckets != null) {
        if (containsDirectRateLimitWindows(nestedBuckets)) {
            return decodeDirectRateLimitBuckets(nestedBuckets)
        }

        decodeRateLimitBucket(null, nestedBuckets)?.let { return listOf(it) }
    }

    payloadObject["result"]?.jsonObjectOrNull()?.let { nestedResult ->
        return decodeRateLimitBuckets(nestedResult)
    }

    if (containsDirectRateLimitWindows(payloadObject)) {
        return decodeDirectRateLimitBuckets(payloadObject)
    }

    return emptyList()
}

fun mergeRateLimitBuckets(
    existing: List<RateLimitBucket>,
    incoming: List<RateLimitBucket>,
): List<RateLimitBucket> {
    if (existing.isEmpty()) return incoming
    if (incoming.isEmpty()) return existing

    val mergedById = existing.associateByTo(linkedMapOf(), RateLimitBucket::limitId).toMutableMap()
    for (bucket in incoming) {
        val current = mergedById[bucket.limitId]
        mergedById[bucket.limitId] = if (current == null) {
            bucket
        } else {
            RateLimitBucket(
                limitId = bucket.limitId,
                limitName = bucket.limitName ?: current.limitName,
                primary = bucket.primary ?: current.primary,
                secondary = bucket.secondary ?: current.secondary,
            )
        }
    }
    return mergedById.values.toList()
}

fun durationLabel(minutes: Int?): String? {
    minutes ?: return null
    if (minutes <= 0) return null

    val weekMinutes = 7 * 24 * 60
    val dayMinutes = 24 * 60

    return when {
        minutes % weekMinutes == 0 -> if (minutes == weekMinutes) "Weekly" else "${minutes / weekMinutes}w"
        minutes % dayMinutes == 0 -> "${minutes / dayMinutes}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}

fun formatCompactTokenCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> {
            val value = count / 1_000.0
            if (value % 1.0 == 0.0) "${value.toInt()}K" else String.format("%.1fK", value)
        }
        else -> count.toString()
    }
}

private fun decodeRateLimitBucket(
    explicitLimitId: String?,
    value: JsonElement,
): RateLimitBucket? {
    val objectValue = value.jsonObjectOrNull() ?: return null
    val limitId = explicitLimitId
        ?: objectValue.stringOrNull("limitId", "limit_id", "id")
        ?: UUID.randomUUID().toString()
    val primary = decodeRateLimitWindow(objectValue["primary"] ?: objectValue["primary_window"])
    val secondary = decodeRateLimitWindow(objectValue["secondary"] ?: objectValue["secondary_window"])
    if (primary == null && secondary == null) {
        return null
    }

    return RateLimitBucket(
        limitId = limitId,
        limitName = objectValue.stringOrNull("limitName", "limit_name", "name"),
        primary = primary,
        secondary = secondary,
    )
}

private fun decodeDirectRateLimitBuckets(objectValue: JsonObject): List<RateLimitBucket> {
    val buckets = mutableListOf<RateLimitBucket>()

    decodeRateLimitWindow(objectValue["primary"] ?: objectValue["primary_window"])?.let { primary ->
        buckets += RateLimitBucket(
            limitId = "primary",
            limitName = objectValue.stringOrNull("limitName", "limit_name", "name"),
            primary = primary,
            secondary = null,
        )
    }

    decodeRateLimitWindow(objectValue["secondary"] ?: objectValue["secondary_window"])?.let { secondary ->
        buckets += RateLimitBucket(
            limitId = "secondary",
            limitName = objectValue.stringOrNull("secondaryName", "secondary_name"),
            primary = secondary,
            secondary = null,
        )
    }

    return buckets
}

private fun decodeRateLimitWindow(value: JsonElement?): RateLimitWindow? {
    val objectValue = value.jsonObjectOrNull() ?: return null
    val usedPercent = objectValue.intOrNull("usedPercent", "used_percent") ?: 0
    val windowDurationMins = objectValue.intOrNull(
        "windowDurationMins",
        "window_duration_mins",
        "windowMinutes",
        "window_minutes",
    ) ?: objectValue.longOrNull("limit_window_seconds")?.let { seconds ->
        ((seconds + 59L) / 60L).toInt()
    }
    val rawResetsAt = objectValue.doubleOrNull("resetsAt", "resets_at")
        ?: objectValue.doubleOrNull("reset_at")
        ?: objectValue.longOrNull("reset_after_seconds")?.toDouble()?.let { seconds ->
            (System.currentTimeMillis() / 1000.0) + seconds
        }
    val resetsAtEpochMillis = rawResetsAt?.toLong()?.let { raw ->
        if (raw >= 10_000_000_000L) raw else raw * 1000L
    }

    return RateLimitWindow(
        usedPercent = usedPercent,
        windowDurationMins = windowDurationMins,
        resetsAtEpochMillis = resetsAtEpochMillis,
    )
}

private fun containsDirectRateLimitWindows(objectValue: JsonObject): Boolean {
    return objectValue["primary"] != null ||
        objectValue["secondary"] != null ||
        objectValue["primary_window"] != null ||
        objectValue["secondary_window"] != null ||
        objectValue["rate_limit"] != null
}

private fun decodeWhamUsageBuckets(payloadObject: JsonObject): List<RateLimitBucket> {
    val buckets = mutableListOf<RateLimitBucket>()

    payloadObject["rate_limit"]?.jsonObjectOrNull()?.let { rateLimit ->
        decodeWhamRateLimitBucket(
            limitId = payloadObject.stringOrNull("metered_feature") ?: "codex",
            limitName = payloadObject.stringOrNull("limit_name") ?: "Codex",
            rateLimit = rateLimit,
        )?.let(buckets::add)
    }

    payloadObject["additional_rate_limits"]?.jsonArrayOrNull()?.forEachIndexed { index, element ->
        val objectValue = element.jsonObjectOrNull() ?: return@forEachIndexed
        val rateLimit = objectValue["rate_limit"]?.jsonObjectOrNull() ?: return@forEachIndexed
        decodeWhamRateLimitBucket(
            limitId = objectValue.stringOrNull("metered_feature")
                ?: objectValue.stringOrNull("limit_name")
                ?: "additional_$index",
            limitName = objectValue.stringOrNull("limit_name"),
            rateLimit = rateLimit,
        )?.let(buckets::add)
    }

    return buckets
}

private fun decodeWhamRateLimitBucket(
    limitId: String,
    limitName: String?,
    rateLimit: JsonObject,
): RateLimitBucket? {
    val primary = decodeRateLimitWindow(rateLimit["primary"] ?: rateLimit["primary_window"])
    val secondary = decodeRateLimitWindow(rateLimit["secondary"] ?: rateLimit["secondary_window"])
    if (primary == null && secondary == null) {
        return null
    }

    return RateLimitBucket(
        limitId = limitId,
        limitName = limitName,
        primary = primary,
        secondary = secondary,
    )
}

private fun JsonObject.intOrNull(vararg keys: String): Int? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.content.toIntOrNull()?.let { return it }
    }
    return null
}

private fun JsonObject.doubleOrNull(vararg keys: String): Double? {
    for (key in keys) {
        val value = this[key] as? JsonPrimitive ?: continue
        value.content.toDoubleOrNull()?.let { return it }
    }
    return null
}

private sealed class JsonSearchValue {
    data class ObjectWrapper(val value: JsonObject) : JsonSearchValue()
    data class ArrayWrapper(val value: JsonArray) : JsonSearchValue()
    data class PrimitiveWrapper(val value: JsonPrimitive) : JsonSearchValue()
    data object NullWrapper : JsonSearchValue()
}

private data class JsonSearchRoot(val value: JsonSearchValue)

private fun kotlinx.serialization.json.JsonElement?.toWrappedJson(): JsonSearchValue = when (this) {
    is JsonObject -> JsonSearchValue.ObjectWrapper(this)
    is JsonArray -> JsonSearchValue.ArrayWrapper(this)
    is JsonPrimitive -> JsonSearchValue.PrimitiveWrapper(this)
    null, JsonNull -> JsonSearchValue.NullWrapper
}

private fun firstIntForAnyKey(keys: List<String>, root: JsonSearchRoot, maxDepth: Int = 8): Int? {
    return keys.firstNotNullOfOrNull { key ->
        firstValueForKey(key, root.value, maxDepth)?.let(::intValue)
    }
}

private fun firstValueForAnyKey(keys: List<String>, root: JsonSearchRoot, maxDepth: Int = 8): JsonSearchValue? {
    return keys.firstNotNullOfOrNull { key ->
        firstValueForKey(key, root.value, maxDepth)
    }
}

private fun firstValueForKey(key: String, root: JsonSearchValue, maxDepth: Int): JsonSearchValue? {
    if (maxDepth < 0) return null

    return when (root) {
        is JsonSearchValue.ObjectWrapper -> {
            root.value[key]?.toWrappedJson()?.takeUnless(::isEmptyJsonValue)
                ?: root.value.values.firstNotNullOfOrNull { value ->
                    firstValueForKey(key, value.toWrappedJson(), maxDepth - 1)
                }
        }

        is JsonSearchValue.ArrayWrapper -> root.value.firstNotNullOfOrNull { value ->
            firstValueForKey(key, value.toWrappedJson(), maxDepth - 1)
        }

        is JsonSearchValue.PrimitiveWrapper, JsonSearchValue.NullWrapper -> null
    }
}

private fun intValue(value: JsonSearchValue): Int? = when (value) {
    is JsonSearchValue.PrimitiveWrapper -> value.value.content.toDoubleOrNull()?.toInt()
    else -> null
}

private fun isEmptyJsonValue(value: JsonSearchValue): Boolean = when (value) {
    is JsonSearchValue.NullWrapper -> true
    is JsonSearchValue.PrimitiveWrapper -> value.value.content.isBlank()
    is JsonSearchValue.ArrayWrapper -> value.value.isEmpty()
    is JsonSearchValue.ObjectWrapper -> value.value.isEmpty()
}

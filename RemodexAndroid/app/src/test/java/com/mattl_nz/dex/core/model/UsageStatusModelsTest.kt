package com.mattl_nz.dex.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageStatusModelsTest {
    @Test
    fun extractContextWindowUsageFromTokenCountPayloadPrefersLastUsage() {
        val usage = extractContextWindowUsageFromTokenCountPayload(
            JsonObject(
                mapOf(
                    "info" to JsonObject(
                        mapOf(
                            "total_token_usage" to JsonObject(
                                mapOf("total_tokens" to JsonPrimitive(123_884_753)),
                            ),
                            "last_token_usage" to JsonObject(
                                mapOf("total_tokens" to JsonPrimitive(200_930)),
                            ),
                            "model_context_window" to JsonPrimitive(258_400),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(200_930, usage?.tokensUsed)
        assertEquals(258_400, usage?.tokenLimit)
    }

    @Test
    fun decodeRateLimitBucketsParsesKeyedPayload() {
        val buckets = decodeRateLimitBuckets(
            JsonObject(
                mapOf(
                    "rateLimitsByLimitId" to JsonObject(
                        mapOf(
                            "codex_5h" to JsonObject(
                                mapOf(
                                    "limitId" to JsonPrimitive("codex_5h"),
                                    "primary" to JsonObject(
                                        mapOf(
                                            "usedPercent" to JsonPrimitive(3),
                                            "windowDurationMins" to JsonPrimitive(300),
                                            "resetsAt" to JsonPrimitive(1_742_000_000),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf("codex_5h"), buckets.map { it.limitId })
        assertEquals("5h", buckets.first().displayLabel)
        assertEquals(97, buckets.first().primary?.remainingPercent)
    }

    @Test
    fun decodeRateLimitBucketsParsesWhamUsagePayload() {
        val buckets = decodeRateLimitBuckets(
            JsonObject(
                mapOf(
                    "plan_type" to JsonPrimitive("prolite"),
                    "rate_limit" to JsonObject(
                        mapOf(
                            "primary_window" to JsonObject(
                                mapOf(
                                    "used_percent" to JsonPrimitive(17),
                                    "limit_window_seconds" to JsonPrimitive(18_000),
                                    "reset_after_seconds" to JsonPrimitive(9_177),
                                    "reset_at" to JsonPrimitive(1_775_985_067),
                                ),
                            ),
                            "secondary_window" to JsonObject(
                                mapOf(
                                    "used_percent" to JsonPrimitive(5),
                                    "limit_window_seconds" to JsonPrimitive(604_800),
                                    "reset_after_seconds" to JsonPrimitive(396_474),
                                    "reset_at" to JsonPrimitive(1_776_372_364),
                                ),
                            ),
                        ),
                    ),
                    "additional_rate_limits" to kotlinx.serialization.json.JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "limit_name" to JsonPrimitive("GPT-5.3-Codex-Spark"),
                                    "metered_feature" to JsonPrimitive("codex_bengalfox"),
                                    "rate_limit" to JsonObject(
                                        mapOf(
                                            "primary_window" to JsonObject(
                                                mapOf(
                                                    "used_percent" to JsonPrimitive(0),
                                                    "limit_window_seconds" to JsonPrimitive(18_000),
                                                    "reset_after_seconds" to JsonPrimitive(18_000),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(2, buckets.size)
        assertEquals("Codex", buckets.first().limitName)
        assertEquals("5h", buckets.first().primary?.let { durationLabel(it.windowDurationMins) })
        assertEquals("Weekly", buckets.first().secondary?.let { durationLabel(it.windowDurationMins) })
        assertEquals("codex_bengalfox", buckets[1].limitId)
    }

    @Test
    fun mergeRateLimitBucketsPreservesMissingWindows() {
        val merged = mergeRateLimitBuckets(
            existing = listOf(
                RateLimitBucket(
                    limitId = "codex_weekly",
                    limitName = "Codex",
                    primary = null,
                    secondary = RateLimitWindow(
                        usedPercent = 12,
                        windowDurationMins = 10_080,
                        resetsAtEpochMillis = null,
                    ),
                ),
            ),
            incoming = listOf(
                RateLimitBucket(
                    limitId = "codex_weekly",
                    limitName = null,
                    primary = RateLimitWindow(
                        usedPercent = 4,
                        windowDurationMins = 300,
                        resetsAtEpochMillis = null,
                    ),
                    secondary = null,
                ),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals(4, merged.first().primary?.usedPercent)
        assertEquals(12, merged.first().secondary?.usedPercent)
    }

    @Test
    fun contextWindowUsageFormatsThousandsWithUppercaseK() {
        val usage = ContextWindowUsage(tokensUsed = 158_158, tokenLimit = 258_400)

        assertEquals("158.2K", usage.tokensUsedFormatted)
        assertEquals("258.4K", usage.tokenLimitFormatted)
    }

    @Test
    fun durationLabelUsesWeeklyLabel() {
        assertEquals("Weekly", durationLabel(10_080))
        assertNull(durationLabel(null))
    }
}

package com.remodex.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

enum class PlanStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED;

    companion object {
        fun fromString(value: String): PlanStepStatus = when (value.lowercase()) {
            "pending" -> PENDING
            "in_progress", "in-progress", "inprogress" -> IN_PROGRESS
            "completed", "done" -> COMPLETED
            "failed", "error" -> FAILED
            else -> PENDING
        }
    }
}

data class PlanStep(
    val step: String,
    val status: PlanStepStatus,
)

data class PlanState(
    val turnId: String,
    val threadId: String,
    val explanation: String? = null,
    val steps: List<PlanStep> = emptyList(),
    val streamingText: String? = null,
)

data class UserInputOption(
    val label: String,
    val description: String? = null,
)

data class UserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<UserInputOption> = emptyList(),
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
    val selectionLimit: Int? = null,
)

data class StructuredUserInputRequest(
    val requestId: JsonElement,
    val threadId: String,
    val turnId: String?,
    val itemId: String?,
    val questions: List<UserInputQuestion>,
)

fun parsePlanSteps(params: JsonObject): List<PlanStep> {
    val planArray = params["plan"]?.jsonArray ?: return emptyList()
    return planArray.mapNotNull { element ->
        val obj = element.jsonObject
        val step = obj.stringOrNull("step") ?: return@mapNotNull null
        val status = obj.stringOrNull("status") ?: "pending"
        PlanStep(step = step, status = PlanStepStatus.fromString(status))
    }
}

fun parseStructuredInputQuestions(params: JsonObject): List<UserInputQuestion> {
    val questionsArray = params["questions"]?.jsonArray ?: return emptyList()
    return questionsArray.mapNotNull { element ->
        val obj = element.jsonObject
        val id = obj.stringOrNull("id") ?: return@mapNotNull null
        val header = obj.stringOrNull("header") ?: ""
        val question = obj.stringOrNull("question") ?: return@mapNotNull null
        val options = obj["options"]?.jsonArray?.mapNotNull { optEl ->
            val optObj = optEl.jsonObject
            val label = optObj.stringOrNull("label") ?: return@mapNotNull null
            UserInputOption(label = label, description = optObj.stringOrNull("description"))
        } ?: emptyList()
        val isOther = obj.boolOrNull("isOther") ?: false
        val isSecret = obj.boolOrNull("isSecret") ?: false
        val selectionLimit = obj.longOrNull("selectionLimit", "selection_limit")?.toInt()
        UserInputQuestion(
            id = id,
            header = header,
            question = question,
            options = options,
            isOther = isOther,
            isSecret = isSecret,
            selectionLimit = selectionLimit,
        )
    }
}

package com.remodex.android.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModelsParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parsePlanSteps parses valid plan array`() {
        val payload = json.parseToJsonElement("""
            {
                "turnId": "turn-1",
                "plan": [
                    {"step": "Analyze code", "status": "completed"},
                    {"step": "Write tests", "status": "in_progress"},
                    {"step": "Deploy", "status": "pending"}
                ]
            }
        """).jsonObject

        val steps = parsePlanSteps(payload)
        assertEquals(3, steps.size)
        assertEquals("Analyze code", steps[0].step)
        assertEquals(PlanStepStatus.COMPLETED, steps[0].status)
        assertEquals("Write tests", steps[1].step)
        assertEquals(PlanStepStatus.IN_PROGRESS, steps[1].status)
        assertEquals("Deploy", steps[2].step)
        assertEquals(PlanStepStatus.PENDING, steps[2].status)
    }

    @Test
    fun `parsePlanSteps handles missing plan array`() {
        val payload = json.parseToJsonElement("""{"turnId": "turn-1"}""").jsonObject
        val steps = parsePlanSteps(payload)
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `PlanStepStatus fromString handles various casing`() {
        assertEquals(PlanStepStatus.IN_PROGRESS, PlanStepStatus.fromString("in_progress"))
        assertEquals(PlanStepStatus.IN_PROGRESS, PlanStepStatus.fromString("in-progress"))
        assertEquals(PlanStepStatus.IN_PROGRESS, PlanStepStatus.fromString("inprogress"))
        assertEquals(PlanStepStatus.COMPLETED, PlanStepStatus.fromString("done"))
        assertEquals(PlanStepStatus.FAILED, PlanStepStatus.fromString("error"))
        assertEquals(PlanStepStatus.PENDING, PlanStepStatus.fromString("unknown_status"))
    }

    @Test
    fun `parseStructuredInputQuestions parses question with options`() {
        val payload = json.parseToJsonElement("""
            {
                "questions": [
                    {
                        "id": "q1",
                        "header": "Auth",
                        "question": "Which auth method?",
                        "options": [
                            {"label": "OAuth", "description": "Use OAuth 2.0"},
                            {"label": "JWT", "description": "Use JWT tokens"}
                        ],
                        "isOther": true
                    }
                ]
            }
        """).jsonObject

        val questions = parseStructuredInputQuestions(payload)
        assertEquals(1, questions.size)
        val q = questions[0]
        assertEquals("q1", q.id)
        assertEquals("Auth", q.header)
        assertEquals("Which auth method?", q.question)
        assertEquals(2, q.options.size)
        assertEquals("OAuth", q.options[0].label)
        assertEquals("Use OAuth 2.0", q.options[0].description)
        assertTrue(q.isOther)
    }
}

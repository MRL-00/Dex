package com.remodex.android.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillAutocompleteLogicTest {
    @Test
    fun `parses trailing skill token`() {
        val token = SkillAutocompleteLogic.trailingSkillAutocompleteToken("Use \$refa")

        assertNotNull(token)
        assertEquals("refa", token?.query)
    }

    @Test
    fun `rejects numeric trailing skill token`() {
        val token = SkillAutocompleteLogic.trailingSkillAutocompleteToken("Budget \$100")

        assertNull(token)
    }

    @Test
    fun `replaces trailing skill token with selected skill`() {
        val updated = SkillAutocompleteLogic.replacingTrailingSkillAutocompleteToken(
            text = "Use \$refa",
            selectedSkill = "refactor-code",
        )

        assertEquals("Use \$refactor-code ", updated)
    }

    @Test
    fun `filters mentioned skills by text`() {
        val mentions = listOf(
            RemodexSkillMetadata(name = "refactor-code"),
            RemodexSkillMetadata(name = "review-pr"),
        )

        val filtered = SkillAutocompleteLogic.filterMentionedSkills(
            text = "Please run \$review-pr before merge",
            mentions = mentions,
        )

        assertEquals(listOf("review-pr"), filtered.map(RemodexSkillMetadata::name))
        assertTrue(SkillAutocompleteLogic.containsSkillMention("Use \$review-pr", "review-pr"))
        assertFalse(SkillAutocompleteLogic.containsSkillMention("Use review-pr", "review-pr"))
    }
}

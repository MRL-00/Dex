package com.mattl_nz.dex.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandLogicTest {
    @Test
    fun `parses trailing slash token`() {
        val token = SlashCommandLogic.trailingSlashCommandToken("Check /sta")

        assertNotNull(token)
        assertEquals("sta", token?.query)
    }

    @Test
    fun `allows bare slash token`() {
        val token = SlashCommandLogic.trailingSlashCommandToken("/")

        assertNotNull(token)
        assertEquals("", token?.query)
    }

    @Test
    fun `does not trigger for mid-word slash`() {
        val token = SlashCommandLogic.trailingSlashCommandToken("path/to/file")

        assertNull(token)
    }

    @Test
    fun `replaces trailing slash token`() {
        val updated = SlashCommandLogic.replacingTrailingSlashCommandToken(
            text = "Use /suba",
            replacement = "Run subagents now",
        )

        assertEquals("Use Run subagents now", updated)
    }
}

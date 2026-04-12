package com.remodex.android.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitModelsParsingTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parseGitRepoSyncResult parses full status`() {
        val payload = json.parseToJsonElement("""
            {
                "repoRoot": "/Users/test/project",
                "branch": "main",
                "tracking": "origin/main",
                "isDirty": true,
                "ahead": 3,
                "behind": 1,
                "files": [
                    {"path": "src/main.kt", "status": "M"},
                    {"path": "README.md", "status": "A"}
                ],
                "diffTotals": {
                    "additions": 42,
                    "deletions": 7,
                    "filesChanged": 2
                }
            }
        """).jsonObject

        val result = parseGitRepoSyncResult(payload)
        assertEquals("/Users/test/project", result.repoRoot)
        assertEquals("main", result.branch)
        assertTrue(result.isDirty)
        assertEquals(3, result.ahead)
        assertEquals(1, result.behind)
        assertEquals(2, result.files.size)
        assertEquals("src/main.kt", result.files[0].path)
        assertEquals("M", result.files[0].status)
        assertNotNull(result.diffTotals)
        assertEquals(42, result.diffTotals!!.additions)
        assertEquals(7, result.diffTotals!!.deletions)
    }

    @Test
    fun `parseGitRepoSyncResult handles minimal status`() {
        val payload = json.parseToJsonElement("""
            {"branch": "feature", "isDirty": false, "ahead": 0, "behind": 0}
        """).jsonObject

        val result = parseGitRepoSyncResult(payload)
        assertEquals("feature", result.branch)
        assertFalse(result.isDirty)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `parseGitBranchesResult parses branches list`() {
        val payload = json.parseToJsonElement("""
            {
                "branches": [
                    {"name": "main", "isCurrent": true, "isDefault": true, "isCheckedOutElsewhere": false},
                    {"name": "feature", "isCurrent": false, "isDefault": false, "isCheckedOutElsewhere": false}
                ],
                "current": "main",
                "defaultBranch": "main"
            }
        """).jsonObject

        val result = parseGitBranchesResult(payload)
        assertEquals(2, result.branches.size)
        assertEquals("main", result.current)
        assertEquals("main", result.defaultBranch)
        assertTrue(result.branches[0].isCurrent)
        assertTrue(result.branches[0].isDefault)
        assertFalse(result.branches[1].isCurrent)
    }
}

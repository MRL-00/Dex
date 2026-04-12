package com.remodex.android.core.model

import kotlinx.serialization.json.JsonObject

data class GitRepoSyncResult(
    val repoRoot: String?,
    val branch: String?,
    val tracking: String?,
    val isDirty: Boolean,
    val ahead: Int,
    val behind: Int,
    val files: List<GitFileStatus>,
    val diffTotals: GitDiffTotals?,
)

data class GitFileStatus(
    val path: String,
    val status: String,
)

data class GitDiffTotals(
    val additions: Int,
    val deletions: Int,
    val filesChanged: Int,
)

data class GitCommitResult(
    val hash: String?,
    val branch: String?,
    val summary: String?,
)

data class GitPushResult(
    val branch: String?,
    val remote: String?,
    val updated: Boolean,
    val status: String?,
)

data class GitPullResult(
    val status: String?,
)

data class GitBranchInfo(
    val name: String,
    val isCurrent: Boolean,
    val isDefault: Boolean,
    val isCheckedOutElsewhere: Boolean,
    val worktreePath: String?,
)

data class GitBranchesResult(
    val branches: List<GitBranchInfo>,
    val current: String?,
    val defaultBranch: String?,
)

data class GitDiffResult(
    val patch: String?,
)

data class GitCheckoutResult(
    val status: String?,
)

data class GitCreateBranchResult(
    val status: String?,
    val branch: String?,
)

enum class DiffFileAction(val label: String) {
    EDITED("Edited"),
    ADDED("Added"),
    DELETED("Deleted"),
    RENAMED("Renamed"),
}

data class DiffFileChunk(
    val path: String,
    val action: DiffFileAction,
    val additions: Int,
    val deletions: Int,
    val diffText: String,
) {
    val compactPath: String
        get() = path.substringAfterLast('/')

    val directoryPath: String?
        get() {
            val idx = path.lastIndexOf('/')
            return if (idx > 0) path.substring(0, idx) else null
        }
}

fun splitUnifiedDiffByFile(patch: String): List<DiffFileChunk> {
    val lines = patch.lines()
    if (lines.isEmpty()) return emptyList()

    val chunks = mutableListOf<DiffFileChunk>()
    val currentLines = mutableListOf<String>()

    fun flushChunk() {
        if (currentLines.isEmpty()) return
        val path = extractDiffPath(currentLines)
        if (path.isEmpty()) {
            currentLines.clear()
            return
        }
        val action = detectDiffAction(currentLines)
        val additions = currentLines.count { it.startsWith("+") && !it.startsWith("+++") }
        val deletions = currentLines.count { it.startsWith("-") && !it.startsWith("---") }
        chunks.add(
            DiffFileChunk(
                path = path,
                action = action,
                additions = additions,
                deletions = deletions,
                diffText = currentLines.joinToString("\n"),
            ),
        )
        currentLines.clear()
    }

    for (line in lines) {
        if (line.startsWith("diff --git ") && currentLines.isNotEmpty()) {
            flushChunk()
        }
        currentLines.add(line)
    }
    flushChunk()
    return chunks
}

private fun extractDiffPath(lines: List<String>): String {
    for (line in lines) {
        if (line.startsWith("+++ ")) {
            val value = normalizeDiffPath(line.removePrefix("+++ "))
            if (value.isNotEmpty() && value != "/dev/null") return value
        }
    }
    for (line in lines) {
        if (line.startsWith("--- ")) {
            val value = normalizeDiffPath(line.removePrefix("--- "))
            if (value.isNotEmpty() && value != "/dev/null") return value
        }
    }
    for (line in lines) {
        if (line.startsWith("diff --git ")) {
            val parts = line.split(" ")
            if (parts.size >= 4) {
                val value = normalizeDiffPath(parts[3])
                if (value.isNotEmpty()) return value
            }
        }
    }
    return ""
}

private fun normalizeDiffPath(raw: String): String {
    var value = raw.trim()
    if (value.startsWith("a/") || value.startsWith("b/")) {
        value = value.substring(2)
    }
    return value
}

private fun detectDiffAction(lines: List<String>): DiffFileAction {
    if (lines.any { it.startsWith("rename from ") || it.startsWith("rename to ") }) {
        return DiffFileAction.RENAMED
    }
    if (lines.any { it.startsWith("new file mode ") || it == "--- /dev/null" }) {
        return DiffFileAction.ADDED
    }
    if (lines.any { it.startsWith("deleted file mode ") || it == "+++ /dev/null" }) {
        return DiffFileAction.DELETED
    }
    return DiffFileAction.EDITED
}

fun parseGitRepoSyncResult(obj: JsonObject): GitRepoSyncResult {
    val filesArray = obj["files"]?.jsonArrayOrNull()
    val files = filesArray?.mapNotNull { f ->
        val fo = f.jsonObjectOrNull() ?: return@mapNotNull null
        val path = fo.stringOrNull("path") ?: return@mapNotNull null
        val status = fo.stringOrNull("status") ?: "?"
        GitFileStatus(path, status)
    } ?: emptyList()

    val diffObj = obj["diff"]?.jsonObjectOrNull() ?: obj["diffTotals"]?.jsonObjectOrNull()
    val diffTotals = diffObj?.let {
        GitDiffTotals(
            additions = it.longOrNull("additions")?.toInt() ?: 0,
            deletions = it.longOrNull("deletions")?.toInt() ?: 0,
            filesChanged = it.longOrNull("filesChanged", "binaryFiles")?.toInt() ?: 0,
        )
    }?.takeIf { totals -> totals.additions > 0 || totals.deletions > 0 || totals.filesChanged > 0 }

    return GitRepoSyncResult(
        repoRoot = obj.stringOrNull("repoRoot", "repo_root"),
        branch = obj.stringOrNull("branch"),
        tracking = obj.stringOrNull("tracking"),
        isDirty = obj.boolOrNull("isDirty", "dirty") ?: false,
        ahead = obj.longOrNull("ahead")?.toInt() ?: 0,
        behind = obj.longOrNull("behind")?.toInt() ?: 0,
        files = files,
        diffTotals = diffTotals,
    )
}

fun parseGitBranchesResult(obj: JsonObject): GitBranchesResult {
    val branchesArray = obj["branches"]?.jsonArrayOrNull()
    val branches = branchesArray?.mapNotNull { b ->
        val bo = b.jsonObjectOrNull() ?: return@mapNotNull null
        val name = bo.stringOrNull("name") ?: return@mapNotNull null
        GitBranchInfo(
            name = name,
            isCurrent = bo.boolOrNull("isCurrent") ?: false,
            isDefault = bo.boolOrNull("isDefault") ?: false,
            isCheckedOutElsewhere = bo.boolOrNull("isCheckedOutElsewhere") ?: false,
            worktreePath = bo.stringOrNull("worktreePath"),
        )
    } ?: emptyList()

    return GitBranchesResult(
        branches = branches,
        current = obj.stringOrNull("current"),
        defaultBranch = obj.stringOrNull("default", "defaultBranch"),
    )
}

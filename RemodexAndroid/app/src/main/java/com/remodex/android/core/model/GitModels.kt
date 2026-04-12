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

fun parseGitRepoSyncResult(obj: JsonObject): GitRepoSyncResult {
    val filesArray = obj["files"]?.jsonArrayOrNull()
    val files = filesArray?.mapNotNull { f ->
        val fo = f.jsonObjectOrNull() ?: return@mapNotNull null
        val path = fo.stringOrNull("path") ?: return@mapNotNull null
        val status = fo.stringOrNull("status") ?: "?"
        GitFileStatus(path, status)
    } ?: emptyList()

    val diffObj = obj["diffTotals"]?.jsonObjectOrNull()
    val diffTotals = diffObj?.let {
        GitDiffTotals(
            additions = it.longOrNull("additions")?.toInt() ?: 0,
            deletions = it.longOrNull("deletions")?.toInt() ?: 0,
            filesChanged = it.longOrNull("filesChanged")?.toInt() ?: 0,
        )
    }

    return GitRepoSyncResult(
        repoRoot = obj.stringOrNull("repoRoot"),
        branch = obj.stringOrNull("branch"),
        tracking = obj.stringOrNull("tracking"),
        isDirty = obj.boolOrNull("isDirty") ?: false,
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

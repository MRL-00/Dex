package com.mattl_nz.dex.core.data

import com.mattl_nz.dex.core.model.GitBranchesResult
import com.mattl_nz.dex.core.model.GitCheckoutResult
import com.mattl_nz.dex.core.model.GitCommitResult
import com.mattl_nz.dex.core.model.GitCreateBranchResult
import com.mattl_nz.dex.core.model.GitDiffResult
import com.mattl_nz.dex.core.model.GitPullResult
import com.mattl_nz.dex.core.model.GitPushResult
import com.mattl_nz.dex.core.model.GitRepoSyncResult
import com.mattl_nz.dex.core.model.RemodexUiState
import com.mattl_nz.dex.core.model.RpcMessage
import com.mattl_nz.dex.core.model.ThreadSummary
import com.mattl_nz.dex.core.model.boolOrNull
import com.mattl_nz.dex.core.model.jsonObjectOrNull
import com.mattl_nz.dex.core.model.parseGitBranchesResult
import com.mattl_nz.dex.core.model.parseGitRepoSyncResult
import com.mattl_nz.dex.core.model.stringOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class RemodexGitCoordinator(
    private val sendRequest: suspend (String, JsonObject?) -> RpcMessage,
    private val uiState: MutableStateFlow<RemodexUiState>,
    private val scope: CoroutineScope,
) {
    suspend fun gitStatus(cwd: String): GitRepoSyncResult? {
        val response = sendRequest("git/status", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        val result = parseGitRepoSyncResult(obj)
        val matchingThreadIds = matchingThreadIdsForCwd(cwd)
        uiState.update { state ->
            state.copy(
                gitStatus = result,
                gitStatusByThread = state.gitStatusByThread + matchingThreadIds.associateWith { result },
            )
        }
        return result
    }

    fun prefetchGitStatusForThreads(threads: List<ThreadSummary>) {
        val state = uiState.value
        val candidateCwds = threads
            .asSequence()
            .mapNotNull { it.cwd?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .filter { cwd -> state.gitStatusByThread.none { entry -> entry.key in matchingThreadIdsForCwd(cwd) } }
            .take(20)
            .toList()
        candidateCwds.forEach { cwd ->
            scope.launch {
                runCatching { gitStatus(cwd) }
            }
        }
    }

    suspend fun gitCommit(cwd: String, message: String? = null): GitCommitResult? {
        val params = mutableMapOf<String, JsonElement>("cwd" to JsonPrimitive(cwd))
        message?.let { params["message"] = JsonPrimitive(it) }
        val response = sendRequest("git/commit", JsonObject(params))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitCommitResult(
            hash = obj.stringOrNull("hash"),
            branch = obj.stringOrNull("branch"),
            summary = obj.stringOrNull("summary"),
        )
    }

    suspend fun gitPush(cwd: String): GitPushResult? {
        val response = sendRequest("git/push", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitPushResult(
            branch = obj.stringOrNull("branch"),
            remote = obj.stringOrNull("remote"),
            updated = obj.boolOrNull("updated") ?: false,
            status = obj.stringOrNull("status"),
        )
    }

    suspend fun gitPull(cwd: String): GitPullResult? {
        val response = sendRequest("git/pull", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitPullResult(status = obj.stringOrNull("status"))
    }

    suspend fun gitBranches(cwd: String): GitBranchesResult? {
        val response = sendRequest("git/branches", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        val result = parseGitBranchesResult(obj)
        uiState.update { it.copy(gitBranches = result) }
        return result
    }

    suspend fun gitDiff(cwd: String): GitDiffResult? {
        val response = sendRequest("git/diff", JsonObject(mapOf("cwd" to JsonPrimitive(cwd))))
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitDiffResult(patch = obj.stringOrNull("patch"))
    }

    suspend fun gitCheckout(cwd: String, branch: String): GitCheckoutResult? {
        val response = sendRequest(
            "git/checkout",
            JsonObject(mapOf("cwd" to JsonPrimitive(cwd), "branch" to JsonPrimitive(branch))),
        )
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitCheckoutResult(status = obj.stringOrNull("status"))
    }

    suspend fun gitCreateBranch(cwd: String, name: String): GitCreateBranchResult? {
        val response = sendRequest(
            "git/createBranch",
            JsonObject(mapOf("cwd" to JsonPrimitive(cwd), "name" to JsonPrimitive(name))),
        )
        val obj = response.result.jsonObjectOrNull() ?: return null
        return GitCreateBranchResult(status = obj.stringOrNull("status"), branch = obj.stringOrNull("branch"))
    }

    private fun matchingThreadIdsForCwd(cwd: String): Set<String> {
        val normalizedCwd = cwd.trimEnd('/')
        return uiState.value.threads
            .filter { it.cwd?.trimEnd('/') == normalizedCwd }
            .mapTo(linkedSetOf()) { it.id }
    }
}

package com.remodex.android.core.model

data class ConversationDiffTotals(
    val additions: Int,
    val deletions: Int,
    val distinctDiffCount: Int,
) {
    val hasChanges: Boolean
        get() = additions > 0 || deletions > 0
}

data class ConversationFileChangeSummaryEntry(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val action: DiffFileAction?,
)

data class ConversationFileChangeSummary(
    val entries: List<ConversationFileChangeSummaryEntry>,
)

object ConversationFileChangeSummaryParser {
    private val actionOnlyRegex = Regex("(?i)^(edited|updated|added|created|deleted|removed|renamed|moved)\\s*$")
    private val actionAndTotalsRegex = Regex(
        "(?i)^(edited|updated|added|created|deleted|removed|renamed|moved)\\s+(.+?)\\s+[+＋]\\s*(\\d+)\\s*[-−–—﹣－]\\s*(\\d+)\\s*$",
    )
    private val pathAndTotalsRegex = Regex(
        "^(.+?)\\s+[+＋]\\s*(\\d+)\\s*[-−–—﹣－]\\s*(\\d+)\\s*$",
    )
    private val totalsLineRegex = Regex(
        "(?i)^totals:\\s*[+＋]\\s*(\\d+)\\s*[-−–—﹣－]\\s*(\\d+)\\s*$",
    )

    fun parse(text: String): ConversationFileChangeSummary? {
        parseGroupedSummary(text)?.let { return it }
        parsePathBlockSummary(text)?.let { return it }
        return parseUnifiedDiffSummary(text)
    }

    fun dedupeKey(text: String): String? {
        val summary = parse(text) ?: return null
        return dedupeKey(summary)
    }

    fun dedupeKey(summary: ConversationFileChangeSummary): String {
        return summary.entries
            .sortedWith(compareBy({ it.path }, { it.additions }, { it.deletions }, { it.action?.label.orEmpty() }))
            .joinToString("|") { entry ->
                "${entry.path}:${entry.additions}:${entry.deletions}:${entry.action?.label.orEmpty()}"
            }
    }

    fun chunks(text: String): List<DiffFileChunk> {
        val unifiedDiff = extractUnifiedDiff(text) ?: text.trim().takeIf { it.contains("diff --git ") }
        val explicitChunks = unifiedDiff?.let(::splitUnifiedDiffByFile).orEmpty()
        if (explicitChunks.isNotEmpty()) {
            return explicitChunks
        }

        val summary = parse(text) ?: return emptyList()
        return summary.entries.map { entry ->
            DiffFileChunk(
                path = entry.path,
                action = entry.action ?: DiffFileAction.EDITED,
                additions = entry.additions,
                deletions = entry.deletions,
                diffText = buildFallbackDiffText(entry),
            )
        }
    }

    private fun parseGroupedSummary(text: String): ConversationFileChangeSummary? {
        val entries = mutableListOf<ConversationFileChangeSummaryEntry>()
        var currentAction: DiffFileAction? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                currentAction = null
                continue
            }

            val actionOnlyMatch = actionOnlyRegex.matchEntire(line)
            if (actionOnlyMatch != null) {
                currentAction = parseAction(actionOnlyMatch.groupValues[1])
                continue
            }

            val actionAndTotalsMatch = actionAndTotalsRegex.matchEntire(line)
            if (actionAndTotalsMatch != null) {
                val action = parseAction(actionAndTotalsMatch.groupValues[1])
                val path = actionAndTotalsMatch.groupValues[2].trim()
                if (looksLikeFilePath(path)) {
                    entries += ConversationFileChangeSummaryEntry(
                        path = path,
                        additions = actionAndTotalsMatch.groupValues[3].toInt(),
                        deletions = actionAndTotalsMatch.groupValues[4].toInt(),
                        action = action,
                    )
                }
                continue
            }

            val pathAndTotalsMatch = pathAndTotalsRegex.matchEntire(line)
            if (pathAndTotalsMatch != null) {
                val path = pathAndTotalsMatch.groupValues[1].trim()
                if (looksLikeFilePath(path)) {
                    entries += ConversationFileChangeSummaryEntry(
                        path = path,
                        additions = pathAndTotalsMatch.groupValues[2].toInt(),
                        deletions = pathAndTotalsMatch.groupValues[3].toInt(),
                        action = currentAction,
                    )
                }
                continue
            }

            if (line.contains("```diff") || line.contains("diff --git ")) {
                parseUnifiedDiffSummary(text)?.let { return it }
            }

            if (line.startsWith("Path:", ignoreCase = true) || line.startsWith("Totals:", ignoreCase = true)) {
                parsePathBlockSummary(text)?.let { return it }
            }

            if (line.startsWith("```")) {
                parsePathBlockSummary(text)?.let { return it }
            }
        }

        return consolidate(entries)
    }

    private fun parsePathBlockSummary(text: String): ConversationFileChangeSummary? {
        val orderedPaths = mutableListOf<String>()
        val totalsByPath = linkedMapOf<String, Pair<Int, Int>>()
        val actionByPath = linkedMapOf<String, DiffFileAction?>()
        var currentPath: String? = null
        var sawSignal = false

        fun ensurePath(path: String) {
            if (path !in totalsByPath) {
                orderedPaths += path
                totalsByPath[path] = 0 to 0
            }
        }

        val lines = text.lines()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            when {
                trimmed.isEmpty() -> {
                    index += 1
                }
                trimmed.startsWith("Path:", ignoreCase = true) -> {
                    val path = trimmed.substringAfter(':').trim()
                    if (looksLikeFilePath(path)) {
                        ensurePath(path)
                        currentPath = path
                        sawSignal = true
                    }
                    index += 1
                }
                trimmed.startsWith("Kind:", ignoreCase = true) -> {
                    val path = currentPath
                    if (path != null) {
                        actionByPath[path] = parseAction(trimmed.substringAfter(':').trim())
                        sawSignal = true
                    }
                    index += 1
                }
                totalsLineRegex.matches(trimmed) -> {
                    val match = totalsLineRegex.matchEntire(trimmed)
                    val path = currentPath
                    if (match != null && path != null) {
                        val additions = match.groupValues[1].toInt()
                        val deletions = match.groupValues[2].toInt()
                        val current = totalsByPath[path] ?: (0 to 0)
                        totalsByPath[path] = current.first + additions to current.second + deletions
                        sawSignal = true
                    }
                    index += 1
                }
                trimmed.startsWith("```") -> {
                    index += 1
                    val codeLines = mutableListOf<String>()
                    while (index < lines.size && !lines[index].trim().startsWith("```")) {
                        codeLines += lines[index]
                        index += 1
                    }
                    if (index < lines.size) {
                        index += 1
                    }

                    val code = codeLines.joinToString("\n").trim()
                    if (code.isBlank()) {
                        continue
                    }

                    val chunks = splitUnifiedDiffByFile(code)
                    if (chunks.isNotEmpty()) {
                        chunks.forEach { chunk ->
                            ensurePath(chunk.path)
                            val current = totalsByPath[chunk.path] ?: (0 to 0)
                            totalsByPath[chunk.path] = current.first + chunk.additions to current.second + chunk.deletions
                            actionByPath[chunk.path] = actionByPath[chunk.path] ?: chunk.action
                        }
                        sawSignal = true
                    } else if (currentPath != null) {
                        val additions = codeLines.count { it.startsWith("+") && !it.startsWith("+++") }
                        val deletions = codeLines.count { it.startsWith("-") && !it.startsWith("---") }
                        if (additions > 0 || deletions > 0) {
                            val current = totalsByPath[currentPath] ?: (0 to 0)
                            totalsByPath[currentPath] = current.first + additions to current.second + deletions
                            sawSignal = true
                        }
                    }
                }
                else -> index += 1
            }
        }

        if (!sawSignal) {
            return null
        }

        val entries = orderedPaths.mapNotNull { path ->
            val totals = totalsByPath[path] ?: return@mapNotNull null
            val action = actionByPath[path]
            if (totals.first == 0 && totals.second == 0 && action == null) {
                return@mapNotNull null
            }
            ConversationFileChangeSummaryEntry(
                path = path,
                additions = totals.first,
                deletions = totals.second,
                action = action,
            )
        }
        return consolidate(entries)
    }

    private fun parseUnifiedDiffSummary(text: String): ConversationFileChangeSummary? {
        val unifiedDiff = extractUnifiedDiff(text) ?: text.trim().takeIf { it.contains("diff --git ") }
        val chunks = unifiedDiff?.let(::splitUnifiedDiffByFile).orEmpty()
        if (chunks.isEmpty()) {
            return null
        }
        return ConversationFileChangeSummary(
            entries = chunks.map { chunk ->
                ConversationFileChangeSummaryEntry(
                    path = chunk.path,
                    additions = chunk.additions,
                    deletions = chunk.deletions,
                    action = chunk.action,
                )
            },
        )
    }

    private fun extractUnifiedDiff(text: String): String? {
        val lines = text.lines()
        var index = 0
        while (index < lines.size) {
            if (!lines[index].trim().startsWith("```")) {
                index += 1
                continue
            }
            index += 1
            val codeLines = mutableListOf<String>()
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                codeLines += lines[index]
                index += 1
            }
            val code = codeLines.joinToString("\n").trim()
            if (code.contains("diff --git ") || (code.contains("--- ") && code.contains("+++ "))) {
                return code
            }
            if (index < lines.size) {
                index += 1
            }
        }
        return null
    }

    private fun consolidate(entries: List<ConversationFileChangeSummaryEntry>): ConversationFileChangeSummary? {
        if (entries.isEmpty()) {
            return null
        }
        val orderedPaths = mutableListOf<String>()
        val consolidated = linkedMapOf<String, ConversationFileChangeSummaryEntry>()
        entries.forEach { entry ->
            if (!looksLikeFilePath(entry.path)) {
                return@forEach
            }
            val existing = consolidated[entry.path]
            if (existing == null) {
                orderedPaths += entry.path
                consolidated[entry.path] = entry
            } else {
                consolidated[entry.path] = existing.copy(
                    additions = existing.additions + entry.additions,
                    deletions = existing.deletions + entry.deletions,
                    action = existing.action ?: entry.action,
                )
            }
        }
        val resolved = orderedPaths.mapNotNull { path ->
            val entry = consolidated[path] ?: return@mapNotNull null
            if (entry.additions == 0 && entry.deletions == 0 && entry.action == null) {
                return@mapNotNull null
            }
            entry
        }
        return resolved.takeIf { it.isNotEmpty() }?.let(::ConversationFileChangeSummary)
    }

    private fun parseAction(value: String): DiffFileAction? {
        return when (value.trim().lowercase()) {
            "edited", "updated", "update", "edit" -> DiffFileAction.EDITED
            "added", "created", "create", "add" -> DiffFileAction.ADDED
            "deleted", "removed", "remove", "delete" -> DiffFileAction.DELETED
            "renamed", "moved", "rename", "move" -> DiffFileAction.RENAMED
            else -> null
        }
    }

    private fun looksLikeFilePath(value: String): Boolean {
        return value.contains("/") ||
            value.contains("\\") ||
            value.contains(".") ||
            value in setOf("README", "Dockerfile", "Makefile", "Podfile", "Gemfile")
    }

    private fun buildFallbackDiffText(entry: ConversationFileChangeSummaryEntry): String {
        return buildString {
            appendLine("Path: ${entry.path}")
            entry.action?.let { appendLine("Kind: ${it.label}") }
            append("Totals: +${entry.additions} -${entry.deletions}")
        }
    }
}

object ConversationDiffSummaryCalculator {
    fun totals(messages: List<ConversationMessage>): ConversationDiffTotals? {
        val relevantMessages = messagesAfterMostRecentPush(messages)
        var additions = 0
        var deletions = 0
        var distinctDiffCount = 0
        val seenKeys = linkedSetOf<String>()

        relevantMessages.forEach { message ->
            if (message.isStreaming || message.role != MessageRole.SYSTEM || message.kind != MessageKind.FILE_CHANGE) {
                return@forEach
            }
            val summary = ConversationFileChangeSummaryParser.parse(message.text) ?: return@forEach
            val dedupeKey = ConversationFileChangeSummaryParser.dedupeKey(summary)
            val turnScope = message.turnId?.trim()?.takeIf(String::isNotBlank) ?: "message-id:${message.id}"
            if (!seenKeys.add("$turnScope|$dedupeKey")) {
                return@forEach
            }
            additions += summary.entries.sumOf { it.additions }
            deletions += summary.entries.sumOf { it.deletions }
            distinctDiffCount += 1
        }

        return ConversationDiffTotals(
            additions = additions,
            deletions = deletions,
            distinctDiffCount = distinctDiffCount,
        ).takeIf { it.hasChanges }
    }

    fun chunks(messages: List<ConversationMessage>): List<DiffFileChunk> {
        val relevantMessages = messagesAfterMostRecentPush(messages)
        val seenKeys = linkedSetOf<String>()
        val mergedChunks = linkedMapOf<String, DiffFileChunk>()

        relevantMessages.forEach { message ->
            if (message.isStreaming || message.role != MessageRole.SYSTEM || message.kind != MessageKind.FILE_CHANGE) {
                return@forEach
            }
            val summary = ConversationFileChangeSummaryParser.parse(message.text) ?: return@forEach
            val dedupeKey = ConversationFileChangeSummaryParser.dedupeKey(summary)
            val turnScope = message.turnId?.trim()?.takeIf(String::isNotBlank) ?: "message-id:${message.id}"
            if (!seenKeys.add("$turnScope|$dedupeKey")) {
                return@forEach
            }

            ConversationFileChangeSummaryParser.chunks(message.text).forEach { chunk ->
                val existing = mergedChunks[chunk.path]
                if (existing == null) {
                    mergedChunks[chunk.path] = chunk
                } else {
                    mergedChunks[chunk.path] = existing.copy(
                        additions = existing.additions + chunk.additions,
                        deletions = existing.deletions + chunk.deletions,
                        diffText = listOf(existing.diffText, chunk.diffText)
                            .filter { it.isNotBlank() }
                            .joinToString("\n\n"),
                    )
                }
            }
        }

        return mergedChunks.values.toList()
    }

    private fun messagesAfterMostRecentPush(messages: List<ConversationMessage>): List<ConversationMessage> {
        val lastPushIndex = messages.indexOfLast(::isPushResetMessage)
        return if (lastPushIndex >= 0) messages.drop(lastPushIndex + 1) else messages
    }

    private fun isPushResetMessage(message: ConversationMessage): Boolean {
        if (message.role != MessageRole.SYSTEM) {
            return false
        }
        if (message.itemId == "git.push.reset.marker") {
            return true
        }

        val normalizedText = message.text.trim().lowercase()
        return normalizedText.startsWith("push completed on ") ||
            normalizedText.startsWith("commit & push completed.")
    }
}

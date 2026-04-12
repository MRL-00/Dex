package com.remodex.android.core.data

import com.remodex.android.core.model.jsonArrayOrNull
import com.remodex.android.core.model.jsonObjectOrNull
import com.remodex.android.core.model.splitUnifiedDiffByFile
import com.remodex.android.core.model.stringOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object RemodexFileChangeFormatter {
    private val changeKeys = listOf(
        "changes",
        "file_changes",
        "fileChanges",
        "files",
        "edits",
        "modified_files",
        "modifiedFiles",
        "patches",
    )
    private val diffKeys = listOf("diff", "unified_diff", "unifiedDiff", "patch", "delta")
    private val pathKeys = listOf(
        "path",
        "file",
        "file_path",
        "filePath",
        "relative_path",
        "relativePath",
        "new_path",
        "newPath",
        "to",
        "target",
        "name",
        "old_path",
        "oldPath",
        "from",
    )
    private val additionKeys = listOf(
        "additions",
        "lines_added",
        "line_additions",
        "lineAdditions",
        "added",
        "insertions",
        "inserted",
        "num_added",
    )
    private val deletionKeys = listOf(
        "deletions",
        "lines_deleted",
        "line_deletions",
        "lineDeletions",
        "removed",
        "deleted",
        "num_deleted",
        "num_removed",
    )

    fun decodePayloadText(payload: JsonObject?): String? {
        payload ?: return null
        val candidates = candidateObjects(payload)
        val status = candidates
            .asSequence()
            .mapNotNull(::decodeStatus)
            .firstOrNull()
            ?: "completed"

        val changes = candidates
            .asSequence()
            .mapNotNull { candidate -> decodeFileChangeEntries(extractChanges(candidate)) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (changes.isNotEmpty()) {
            return buildString {
                append("Status: ")
                append(status)
                append("\n\n")
                append(
                    changes.joinToString("\n\n---\n\n") { entry ->
                        buildString {
                            append("Path: ")
                            append(entry.path)
                            append('\n')
                            append("Kind: ")
                            append(entry.kind)
                            entry.inlineTotals?.let { totals ->
                                append('\n')
                                append("Totals: +")
                                append(totals.additions)
                                append(" -")
                                append(totals.deletions)
                            }
                            if (entry.diff.isNotEmpty()) {
                                append("\n\n```diff\n")
                                append(entry.diff)
                                append("\n```")
                            }
                        }
                    },
                )
            }
        }

        val diff = candidates
            .asSequence()
            .mapNotNull(::extractUnifiedDiff)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
        if (diff != null) {
            return renderUnifiedDiffBody(diff = diff, status = status)
        }

        val output = candidates
            .asSequence()
            .mapNotNull(::extractOutputText)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
        if (output != null && looksLikePatchText(output)) {
            return renderUnifiedDiffBody(diff = output, status = status)
        }

        return null
    }

    private fun candidateObjects(payload: JsonObject): List<JsonObject> {
        return listOfNotNull(
            payload,
            payload["item"].jsonObjectOrNull(),
            payload["event"].jsonObjectOrNull(),
            payload["output"].jsonObjectOrNull(),
            payload["result"].jsonObjectOrNull(),
            payload["payload"].jsonObjectOrNull(),
            payload["data"].jsonObjectOrNull(),
            payload["call"].jsonObjectOrNull(),
            payload["tool"].jsonObjectOrNull(),
        ).distinct()
    }

    private fun decodeStatus(obj: JsonObject): String? {
        val nestedOutput = obj["output"].jsonObjectOrNull()
        val nestedResult = obj["result"].jsonObjectOrNull()
        val nestedPayload = obj["payload"].jsonObjectOrNull()
        val nestedData = obj["data"].jsonObjectOrNull()
        return firstNonBlank(
            obj.stringOrNull("status"),
            nestedOutput?.stringOrNull("status"),
            nestedResult?.stringOrNull("status"),
            nestedPayload?.stringOrNull("status"),
            nestedData?.stringOrNull("status"),
        )
    }

    private fun extractChanges(obj: JsonObject): JsonElement? {
        for (key in changeKeys) {
            obj[key]?.let { return it }
        }
        return null
    }

    private fun extractUnifiedDiff(obj: JsonObject): String? {
        return obj.stringOrNull(*diffKeys.toTypedArray())
    }

    private fun extractOutputText(obj: JsonObject): String? {
        val direct = firstNonBlank(
            obj.stringOrNull("output"),
            obj.stringOrNull("result"),
            obj.stringOrNull("text", "message", "summary", "stdout", "stderr", "output_text", "outputText"),
        )
        if (direct != null) {
            return direct
        }

        return firstNonBlank(
            flattenText(obj["content"]),
            flattenText(obj["parts"]),
            flattenText(obj["messages"]),
        )
    }

    private fun decodeFileChangeEntries(rawChanges: JsonElement?): List<FileChangeEntry> {
        val changeObjects = mutableListOf<JsonObject>()
        when {
            rawChanges?.jsonArrayOrNull() != null -> {
                rawChanges.jsonArrayOrNull()?.forEach { element ->
                    element.jsonObjectOrNull()?.let(changeObjects::add)
                }
            }

            rawChanges?.jsonObjectOrNull() != null -> {
                rawChanges.jsonObjectOrNull()
                    ?.entries
                    ?.sortedBy { it.key }
                    ?.forEach { (key, value) ->
                        val obj = value.jsonObjectOrNull() ?: return@forEach
                        changeObjects += if (obj["path"] == null) {
                            JsonObject(obj + ("path" to JsonPrimitive(key)))
                        } else {
                            obj
                        }
                    }
            }
        }

        return changeObjects.map { changeObject ->
            val path = decodePath(changeObject)
            val kind = decodeKind(changeObject)
            var diff = decodeDiff(changeObject)
            val inlineTotals = decodeInlineTotals(changeObject)
            if (diff.isEmpty()) {
                val content = flattenText(changeObject["content"])?.trim()
                if (!content.isNullOrEmpty()) {
                    diff = synthesizeUnifiedDiffFromContent(content, kind, path)
                }
            }
            FileChangeEntry(
                path = path,
                kind = kind,
                diff = diff,
                inlineTotals = inlineTotals,
            )
        }
    }

    private fun decodePath(changeObject: JsonObject): String {
        for (key in pathKeys) {
            val value = changeObject.stringOrNull(key)?.trim()
            if (!value.isNullOrEmpty()) {
                return value
            }
        }
        return "unknown"
    }

    private fun decodeKind(changeObject: JsonObject): String {
        val nestedKind = changeObject["kind"].jsonObjectOrNull()?.stringOrNull("type")?.trim()
        return firstNonBlank(
            changeObject.stringOrNull("kind")?.trim(),
            changeObject.stringOrNull("action")?.trim(),
            nestedKind,
            changeObject.stringOrNull("type")?.trim(),
        ) ?: "update"
    }

    private fun decodeDiff(changeObject: JsonObject): String {
        return changeObject.stringOrNull(*diffKeys.toTypedArray())?.trim().orEmpty()
    }

    private fun decodeInlineTotals(changeObject: JsonObject): InlineTotals? {
        val additions = decodeNumericField(changeObject, additionKeys) ?: 0
        val deletions = decodeNumericField(changeObject, deletionKeys) ?: 0
        return if (additions > 0 || deletions > 0) InlineTotals(additions, deletions) else null
    }

    private fun decodeNumericField(changeObject: JsonObject, keys: List<String>): Int? {
        for (key in keys) {
            val primitive = changeObject[key] as? JsonPrimitive ?: continue
            primitive.content.toIntOrNull()?.let { return it }
            primitive.content.toDoubleOrNull()?.toInt()?.let { return it }
        }
        return null
    }

    private fun synthesizeUnifiedDiffFromContent(content: String, kind: String, path: String): String {
        val contentLines = content.split('\n')
        val normalizedKind = kind.trim().lowercase()

        if (normalizedKind.contains("add") || normalizedKind.contains("create")) {
            return buildList {
                add("diff --git a/$path b/$path")
                add("new file mode 100644")
                add("--- /dev/null")
                add("+++ b/$path")
                addAll(contentLines.map { line -> "+$line" })
            }.joinToString("\n")
        }

        if (normalizedKind.contains("delete") || normalizedKind.contains("remove")) {
            return buildList {
                add("diff --git a/$path b/$path")
                add("deleted file mode 100644")
                add("--- a/$path")
                add("+++ /dev/null")
                addAll(contentLines.map { line -> "-$line" })
            }.joinToString("\n")
        }

        return ""
    }

    private fun renderUnifiedDiffBody(diff: String, status: String): String {
        val chunks = splitUnifiedDiffByFile(diff)
        if (chunks.isEmpty()) {
            return "Status: $status\n\n```diff\n$diff\n```"
        }

        val renderedChanges = chunks.map { chunk ->
            "Path: ${chunk.path}\nKind: update\n\n```diff\n${chunk.diffText}\n```"
        }
        return "Status: $status\n\n" + renderedChanges.joinToString("\n\n---\n\n")
    }

    private fun looksLikePatchText(text: String): Boolean {
        return text.contains("diff --git ") || (text.contains("--- ") && text.contains("+++ "))
    }

    private fun flattenText(value: JsonElement?): String? {
        when (value) {
            null -> return null
            is JsonPrimitive -> {
                return value.content.trim().takeIf(String::isNotEmpty)
            }
            else -> {
                value.jsonObjectOrNull()?.let { obj ->
                    return firstNonBlank(
                        obj.stringOrNull("text"),
                        obj.stringOrNull("content"),
                        flattenText(obj["value"]),
                    )
                }
                value.jsonArrayOrNull()?.let { array ->
                    val parts = array.mapNotNull(::flattenText).filter(String::isNotBlank)
                    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
                }
            }
        }
        return null
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private data class InlineTotals(
        val additions: Int,
        val deletions: Int,
    )

    private data class FileChangeEntry(
        val path: String,
        val kind: String,
        val diff: String,
        val inlineTotals: InlineTotals?,
    )
}

package com.mattl_nz.dex.core.data

import com.mattl_nz.dex.core.model.jsonArrayOrNull
import com.mattl_nz.dex.core.model.jsonObjectOrNull
import com.mattl_nz.dex.core.model.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class CommandExecutionState(
    val phase: CommandExecutionPhase,
    val shortCommand: String,
)

internal enum class CommandExecutionPhase(val label: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
}

internal object RemodexCommandExecutionFormatter {
    fun historyText(item: JsonObject, type: String): String? {
        val methodHint = when {
            type.contains("end") || type.contains("completed") -> "codex/event/exec_command_end"
            type.contains("output") || type.contains("delta") -> "codex/event/exec_command_output_delta"
            else -> "codex/event/exec_command_begin"
        }
        val state = decode(methodHint, item)
        return "${state.phase.label} ${state.shortCommand}".trim()
    }

    fun decode(method: String, payload: JsonObject?): CommandExecutionState {
        val eventObject = payload?.get("event")?.jsonObjectOrNull()
        val itemObject = payload?.get("item")?.jsonObjectOrNull()
        val candidates = listOfNotNull(payload, eventObject, itemObject)

        val status = candidates
            .asSequence()
            .mapNotNull { it.stringOrNull("status", "state", "phase") }
            .firstOrNull()
        val rawCommand = candidates
            .asSequence()
            .mapNotNull(::extractCommand)
            .firstOrNull()
            ?: "command"

        return CommandExecutionState(
            phase = phase(method = method, status = status),
            shortCommand = shortCommandPreview(rawCommand),
        )
    }

    private fun extractCommand(payload: JsonObject): String? {
        val commandElement = payload["command"]
        if (commandElement != null) {
            commandElementToString(commandElement)?.let { return it }
        }

        val keys = listOf("cmd", "raw_command", "rawCommand", "input", "invocation")
        for (key in keys) {
            payload.stringOrNull(key)?.let { return it }
        }
        return null
    }

    private fun commandElementToString(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }
            is JsonArray -> {
                element.mapNotNull { part ->
                    (part as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                }.takeIf { it.isNotEmpty() }?.joinToString(" ")
            }
            is JsonObject -> element.stringOrNull("command", "cmd", "raw_command", "rawCommand")
            else -> null
        }
    }

    private fun phase(method: String, status: String?): CommandExecutionPhase {
        val normalizedMethod = method.lowercase()
        val normalizedStatus = status?.trim()?.lowercase().orEmpty()
        return when {
            normalizedStatus.contains("fail")
                || normalizedStatus.contains("error")
                || normalizedStatus.contains("stop")
                || normalizedStatus.contains("cancel") -> CommandExecutionPhase.FAILED
            normalizedMethod.contains("exec_command_end")
                || normalizedStatus.contains("complete")
                || normalizedStatus.contains("done")
                || normalizedStatus.contains("success") -> CommandExecutionPhase.COMPLETED
            else -> CommandExecutionPhase.RUNNING
        }
    }

    private fun shortCommandPreview(rawCommand: String, maxLength: Int = 92): String {
        val compact = rawCommand.trim()
            .replace(Regex("\\s+"), " ")
            .ifBlank { "command" }
        val unwrapped = unwrapShellCommand(compact)
        return if (unwrapped.length <= maxLength) {
            unwrapped
        } else {
            unwrapped.take(maxLength - 1) + "..."
        }
    }

    private fun unwrapShellCommand(command: String): String {
        val tokens = command.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "command"

        val shellNames = setOf("bash", "zsh", "sh", "fish")
        val normalizedTokens = tokens.map { it.trim() }
        val shellIndex = when {
            normalizedTokens.size >= 2
                && (normalizedTokens[0] == "env" || normalizedTokens[0].endsWith("/env"))
                && shellNames.any { normalizedTokens[1] == it || normalizedTokens[1].endsWith("/$it") } -> 1
            shellNames.any { normalizedTokens[0] == it || normalizedTokens[0].endsWith("/$it") } -> 0
            else -> -1
        }
        if (shellIndex < 0) return command

        val shellArgs = normalizedTokens.drop(shellIndex + 1)
        val commandIndex = shellArgs.indexOfFirst { it == "-c" || it == "-lc" }
        if (commandIndex < 0 || commandIndex + 1 >= shellArgs.size) {
            return command
        }

        val nested = shellArgs.drop(commandIndex + 1).joinToString(" ").trim().trim('"', '\'')
        val afterCd = nested.substringAfter("&&", nested).trim()
        return afterCd.ifBlank { command }
    }
}

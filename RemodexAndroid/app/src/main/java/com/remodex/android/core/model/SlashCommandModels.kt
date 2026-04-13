package com.remodex.android.core.model

enum class ComposerSlashCommand {
    CODE_REVIEW,
    FORK,
    STATUS,
    SUBAGENTS,
    ;

    val title: String
        get() = when (this) {
            CODE_REVIEW -> "Code Review"
            FORK -> "Fork"
            STATUS -> "Status"
            SUBAGENTS -> "Subagents"
        }

    val subtitle: String
        get() = when (this) {
            CODE_REVIEW -> "Run the reviewer on your local changes"
            FORK -> "Fork this thread into local or a new worktree"
            STATUS -> "Show context usage and rate limits"
            SUBAGENTS -> "Insert a canned prompt that asks Codex to delegate work"
        }

    val commandToken: String
        get() = when (this) {
            CODE_REVIEW -> "/review"
            FORK -> "/fork"
            STATUS -> "/status"
            SUBAGENTS -> "/subagents"
        }

    val cannedPrompt: String?
        get() = when (this) {
            SUBAGENTS -> "Run subagents for different tasks. Delegate distinct work in parallel when helpful and then synthesize the results."
            CODE_REVIEW, FORK, STATUS -> null
        }

    val searchBlob: String
        get() = "${title.lowercase()} ${subtitle.lowercase()} ${commandToken.lowercase()}"

    companion object {
        val allCommands: List<ComposerSlashCommand> = entries

        fun filtered(
            query: String,
            commands: List<ComposerSlashCommand> = allCommands,
        ): List<ComposerSlashCommand> {
            val trimmedQuery = query.trim().lowercase()
            if (trimmedQuery.isEmpty()) {
                return commands
            }
            return commands.filter { it.searchBlob.contains(trimmedQuery) }
        }
    }
}

data class TrailingSlashCommandToken(
    val query: String,
    val tokenRange: IntRange,
)

object SlashCommandLogic {
    fun trailingSlashCommandToken(text: String): TrailingSlashCommandToken? {
        if (text.isEmpty()) {
            return null
        }
        val slashIndex = text.lastIndexOf('/')
        if (slashIndex < 0) {
            return null
        }

        if (slashIndex > 0 && !text[slashIndex - 1].isWhitespace()) {
            return null
        }

        val query = text.substring(slashIndex + 1)
        if (query.any(Char::isWhitespace)) {
            return null
        }

        return TrailingSlashCommandToken(
            query = query,
            tokenRange = slashIndex..text.lastIndex,
        )
    }

    fun removingTrailingSlashCommandToken(text: String): String? {
        val token = trailingSlashCommandToken(text) ?: return null
        return text
            .removeRange(token.tokenRange.first, token.tokenRange.last + 1)
            .trim()
    }

    fun replacingTrailingSlashCommandToken(
        text: String,
        replacement: String,
    ): String? {
        val token = trailingSlashCommandToken(text) ?: return null
        val trimmedReplacement = replacement.trim()
        if (trimmedReplacement.isEmpty()) {
            return null
        }
        return text.replaceRange(token.tokenRange.first, token.tokenRange.last + 1, trimmedReplacement)
    }
}

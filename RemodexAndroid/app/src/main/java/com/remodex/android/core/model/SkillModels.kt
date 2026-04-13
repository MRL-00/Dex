package com.remodex.android.core.model

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class RemodexSkillMetadata(
    val name: String,
    val description: String? = null,
    val path: String? = null,
    val scope: String? = null,
    val enabled: Boolean = true,
) {
    val id: String
        get() = normalizedName

    val normalizedName: String
        get() = name.trim().lowercase(Locale.ROOT)

    val searchBlob: String
        get() = buildString {
            append(normalizedName)
            description
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let {
                    append('\n')
                    append(it.lowercase(Locale.ROOT))
                }
            path
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let {
                    append('\n')
                    append(it.lowercase(Locale.ROOT))
                }
            scope
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let {
                    append('\n')
                    append(it.lowercase(Locale.ROOT))
                }
        }
}

data class RemodexTurnSkillMention(
    val id: String,
    val name: String? = null,
    val path: String? = null,
)

data class TrailingSkillAutocompleteToken(
    val query: String,
    val tokenRange: IntRange,
)

object SkillAutocompleteLogic {
    fun trailingSkillAutocompleteToken(text: String): TrailingSkillAutocompleteToken? {
        val token = trailingToken(text, '$') ?: return null
        if (!token.query.any(Char::isLetter)) {
            return null
        }
        return token
    }

    fun replacingTrailingSkillAutocompleteToken(
        text: String,
        selectedSkill: String,
    ): String? {
        val trimmedSkill = selectedSkill.trim()
        val token = trailingSkillAutocompleteToken(text) ?: return null
        if (trimmedSkill.isEmpty()) {
            return null
        }
        return text.replaceRange(token.tokenRange.first, token.tokenRange.last + 1, "\$$trimmedSkill ")
    }

    fun filterMentionedSkills(
        text: String,
        mentions: List<RemodexSkillMetadata>,
    ): List<RemodexSkillMetadata> {
        return mentions.filter { mention ->
            containsSkillMention(text, mention.name)
        }
    }

    fun containsSkillMention(
        text: String,
        skillName: String,
    ): Boolean {
        val trimmedSkillName = skillName.trim()
        if (trimmedSkillName.isEmpty()) {
            return false
        }
        val pattern = "(^|\\s)\\$${Regex.escape(trimmedSkillName)}(?=\\s|$)"
        return Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(text)
    }

    fun removingSkillMention(
        text: String,
        skillName: String,
    ): String {
        val trimmedSkillName = skillName.trim()
        if (trimmedSkillName.isEmpty()) {
            return text
        }
        val pattern = "(^|\\s)\\$${Regex.escape(trimmedSkillName)}(?=\\s|$)"
        return Regex(pattern, setOf(RegexOption.IGNORE_CASE))
            .replace(text) { match ->
                match.groups[1]?.value.orEmpty()
            }
            .replace(Regex(" {2,}"), " ")
            .trimEnd()
    }

    private fun trailingToken(
        text: String,
        trigger: Char,
    ): TrailingSkillAutocompleteToken? {
        if (text.isEmpty()) {
            return null
        }

        val tokenStart = text.indexOfLast { it.isWhitespace() }
            .let { whitespaceIndex -> if (whitespaceIndex >= 0) whitespaceIndex + 1 else 0 }
        if (tokenStart >= text.length || text[tokenStart] != trigger) {
            return null
        }

        val query = text.substring(tokenStart + 1)
        if (query.isEmpty() || query.any(Char::isWhitespace)) {
            return null
        }

        return TrailingSkillAutocompleteToken(
            query = query,
            tokenRange = tokenStart..text.lastIndex,
        )
    }
}

object SkillDisplayNameFormatter {
    fun displayName(skillName: String): String {
        val normalized = skillName
            .trim()
            .split(Regex("[-_\\s]+"))
            .filter(String::isNotBlank)
        if (normalized.isEmpty()) {
            return skillName.trim()
        }
        return normalized.joinToString(" ") { token ->
            token.lowercase(Locale.ROOT).replaceFirstChar { char ->
                if (char.isLowerCase()) {
                    char.titlecase(Locale.ROOT)
                } else {
                    char.toString()
                }
            }
        }
    }
}
